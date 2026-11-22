package top.witzzz.moonbazaar.data

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.Collections
import java.util.concurrent.TimeUnit

/**
 * 官网登录态保活。
 *
 * moonbazaar.xyz 的 `zlex_token` / `PHPSESSID` **每小时轮换**：后台访问一次 `/home`，
 * 服务端就会下发新的 Cookie。不刷新的话，`/home/get_surveys.php` 会返回
 * `{"status":"error","message":"Not logged in"}` 而不是问卷 JSON。
 *
 * 刷新方式优先用**隐藏 WebView**访问 `/home`：站点 Cookie 就是 WebView 里那一份，页面脚本
 * 该做的事（写 Cookie、续期）都能正常完成；WebView 失败时才退回 OkHttp 裸请求。
 *
 * 触发时机：
 *  - App 每次进入前台（[onForeground]）；
 *  - 进程存活期间每 45 分钟（[init] 里的循环）；
 *  - 问卷请求前发现凭证陈旧（[refreshIfStale]）或请求失败（强制刷新重试一次）。
 */
object SiteRefresh {

    private const val TAG = "SiteRefresh"
    const val INTERVAL_MS: Long = 45 * 60 * 1000L

    /** 隐藏 WebView 打开 `/home` 后等页面脚本写 Cookie 的时间。 */
    private const val WAIT_MS: Long = 9_000L

    @Volatile private var appContext: Context? = null
    @Volatile private var lastRefreshAt: Long = 0L
    @Volatile private var started: Boolean = false

    private var scope: CoroutineScope? = null

    /** 串行化刷新，避免启动瞬间同时开多个隐藏 WebView。 */
    private val mutex = Mutex()

    /** 每次 OkHttp 刷新的 Set-Cookie（网络拦截器逐跳收集，含 302 跳转）。 */
    private val pending = Collections.synchronizedList(mutableListOf<String>())

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .addNetworkInterceptor(Interceptor { chain ->
            val resp = chain.proceed(chain.request())
            resp.headers("Set-Cookie").forEach { pending.add(it) }
            resp
        })
        .build()

    fun init(context: Context) {
        appContext = context.applicationContext
        runCatching { CookieManager.getInstance().setAcceptCookie(true) }
        if (started) return
        started = true
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO).also { s ->
            s.launch {
                while (isActive) {
                    runCatching { refresh() }
                    delay(INTERVAL_MS)
                }
            }
        }
    }

    /** 上次成功刷新距今是否已经超过 [maxAgeMs]。 */
    fun isStale(maxAgeMs: Long = INTERVAL_MS): Boolean =
        lastRefreshAt == 0L || System.currentTimeMillis() - lastRefreshAt > maxAgeMs

    /** 陈旧才刷新；返回是否真的刷新成功。 */
    suspend fun refreshIfStale(maxAgeMs: Long = INTERVAL_MS): Boolean =
        if (isStale(maxAgeMs)) refresh() else true

    /**
     * App 每次回到前台时调用：后台访问 `/home` 换一份新 Cookie。
     *
     * 10 秒内已经刷过就跳过，避免「进程启动刷新 + onResume 刷新」连打两次。
     */
    fun onForeground() {
        if (lastRefreshAt != 0L && System.currentTimeMillis() - lastRefreshAt < 10_000L) return
        val s = scope ?: return
        s.launch { runCatching { refresh() } }
    }

    /**
     * 刷新一次：隐藏 WebView 优先，失败退 OkHttp。
     *
     * 用 [Mutex] 串行化：启动时 `init` / `onResume` / 问卷页会同时触发，避免同时开多个隐藏 WebView。
     * 距上次成功刷新不足 [minGapMs] 时直接跳过（[minGapMs] = 0 表示强制刷新）。
     */
    suspend fun refresh(minGapMs: Long = 5_000L): Boolean = mutex.withLock {
        if (minGapMs > 0 && lastRefreshAt != 0L &&
            System.currentTimeMillis() - lastRefreshAt < minGapMs
        ) {
            return@withLock true
        }
        val viaWeb = withTimeoutOrNull(20_000L) {
            refreshViaWebView()
        } ?: false
        if (viaWeb) {
            lastRefreshAt = System.currentTimeMillis()
            Log.i(TAG, "refresh via webview ok")
            return@withLock true
        }
        val ok = refreshViaHttp()
        if (ok) lastRefreshAt = System.currentTimeMillis()
        Log.i(TAG, "refresh via http ok=$ok")
        ok
    }

    // ===== 方案一：隐藏 WebView =====

    /**
     * 用隐藏 WebView 打开 `/home`，等页面脚本跑一会儿（它负责写/续期 Cookie），
     * 然后从 CookieManager 里把新的 `zlex_token`/`PHPSESSID` 收进账号存档。
     *
     * 不依赖 `onPageFinished`：站点有长连接/统计请求，该回调经常不触发；固定等 [WAIT_MS] 更稳。
     * 全程在主线程，`destroy()` 也在主线程（WebView 要求同一线程）。
     */
    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun refreshViaWebView(): Boolean = withContext(Dispatchers.Main) {
        val ctx = appContext ?: return@withContext false
        val web = try {
            WebView(ctx)
        } catch (e: Exception) {
            Log.w(TAG, "webview create failed: ${e.message}")
            return@withContext false
        }
        try {
            web.settings.javaScriptEnabled = true
            web.settings.domStorageEnabled = true
            web.settings.cacheMode = WebSettings.LOAD_NO_CACHE
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)
            web.webViewClient = object : WebViewClient() {
                override fun onReceivedError(
                    view: WebView,
                    request: android.webkit.WebResourceRequest,
                    error: android.webkit.WebResourceError
                ) {
                    Log.w(TAG, "webview error: ${error.description}")
                }
            }
            // 关键：先把账号存档里的 zlex_token / PHPSESSID 写回 CookieManager，
            // 否则 CookieManager 为空时 /home 只会返回登录页，既不续期也不下发新 Cookie。
            val seeded = SiteCookie.seed()
            Log.i(TAG, "webview refresh seeded=$seeded cookie=${CookieManager.getInstance().getCookie(AppConfig.SITE_URL) != null}")
            web.loadUrl(AppConfig.SITE_HOME_URL)
            delay(WAIT_MS)
            val cm = CookieManager.getInstance()
            cm.flush()
            val synced = SiteCookie.sync()
            Log.i(TAG, "webview refresh synced=$synced token=${SiteCookie.read() != null}")
            return@withContext SiteCookie.read() != null
        } catch (e: Exception) {
            Log.w(TAG, "webview refresh failed: ${e.message}")
            false
        } finally {
            runCatching { web.stopLoading() }
            runCatching { web.destroy() }
        }
    }

    // ===== 方案二：OkHttp 裸请求（兜底） =====

    private suspend fun refreshViaHttp(): Boolean = withContext(Dispatchers.IO) {
        val ctx = appContext ?: return@withContext false
        try {
            val cookie = withContext(Dispatchers.Main) { currentCookie() }
            pending.clear()
            val req = Request.Builder()
                .url(AppConfig.SITE_HOME_URL)
                .header("User-Agent", withContext(Dispatchers.Main) {
                    WebSettings.getDefaultUserAgent(ctx)
                })
                .header("Accept", "text/html,application/xhtml+xml")
                .header("Referer", AppConfig.SITE_URL + "/")
            if (cookie.isNotBlank()) req.header("Cookie", cookie)
            val code = client.newCall(req.build()).execute().use { it.code }
            val sets = synchronized(pending) { pending.toList() }.distinct()
            withContext(Dispatchers.Main) {
                val cm = CookieManager.getInstance()
                sets.forEach { cm.setCookie(AppConfig.SITE_URL, it) }
                cm.flush()
                SiteCookie.sync()
            }
            Log.i(TAG, "http refresh code=$code setCookies=${sets.size}")
            return@withContext withContext(Dispatchers.Main) { SiteCookie.read() != null }
        } catch (e: Exception) {
            Log.w(TAG, "http refresh failed: ${e.message}")
            return@withContext false
        }
    }

    /** WebView Cookie 优先，其次用账号存档里的凭证。 */
    private fun currentCookie(): String {
        SiteCookie.raw()?.let { if (it.isNotBlank()) return it }
        val t = Session.currentSiteToken.orEmpty()
        if (t.isBlank()) return ""
        val s = Session.currentSiteSession.orEmpty()
        return if (s.isBlank()) "zlex_token=$t" else "zlex_token=$t; PHPSESSID=$s"
    }
}
