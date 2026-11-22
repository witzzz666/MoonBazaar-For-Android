package top.witzzz.moonbazaar.data

import android.content.Context
import android.util.Log
import android.webkit.CookieManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.Collections
import java.util.concurrent.TimeUnit

/**
 * 官网登录态保活。
 *
 * moonbazaar.xyz 的 `zlex_token` / `PHPSESSID` **每小时轮换一次**：访问 `/home` 时服务端会下发新的
 * Cookie。过期后再去调 `/home/get_surveys.php` 拿到的不是 JSON，而是 `/home` 的 HTML 页面
 * （或者 `{"status":"error","message":"Not logged in"}`）。
 *
 * 因此这里做三件事：
 * 1. App 启动即刷新一次；
 * 2. 进程存活期间每 45 分钟后台刷新一次（45 < 60，留出余量）；
 * 3. 问卷请求失败时可 `force = true` 强制刷新后重试。
 *
 * 刷新 = 带当前 Cookie GET `/home`（跟随 302），把响应里的 `Set-Cookie` 写回 WebView 的
 * CookieManager，再让 [SiteCookie] 同步进账号存档。
 */
object SiteRefresh {

    private const val TAG = "SiteRefresh"
    const val INTERVAL_MS: Long = 45 * 60 * 1000L

    @Volatile private var appContext: Context? = null
    @Volatile private var lastRefreshAt: Long = 0L
    @Volatile private var started: Boolean = false

    private var scope: CoroutineScope? = null

    /** 每次刷新的 Set-Cookie（网络拦截器逐跳收集，含 302 跳转）。 */
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

    /** 上次成功刷新距今是否已超过 [maxAgeMs]。 */
    fun isStale(maxAgeMs: Long = INTERVAL_MS): Boolean =
        lastRefreshAt == 0L || System.currentTimeMillis() - lastRefreshAt > maxAgeMs

    /** 陈旧才刷新，返回是否真的发了请求。 */
    suspend fun refreshIfStale(maxAgeMs: Long = INTERVAL_MS): Boolean =
        if (isStale(maxAgeMs)) refresh() else false

    /**
     * 访问 `/home` 取新 Cookie 并落盘。
     * @return 是否拿到了可用的 `zlex_token`
     */
    suspend fun refresh(): Boolean = withContext(Dispatchers.IO) {
        val ctx = appContext ?: return@withContext false
        return@withContext try {
            // 优先用 WebView 里的 Cookie；没有（例如清过数据或只从存档恢复）就用存档里的凭证。
            val cookie = withContext(Dispatchers.Main) {
                SiteCookie.raw().orEmpty().ifBlank {
                    val t = Session.currentSiteToken.orEmpty()
                    if (t.isBlank()) "" else buildString {
                        append("zlex_token=").append(t)
                        val s = Session.currentSiteSession.orEmpty()
                        if (s.isNotBlank()) append("; PHPSESSID=").append(s)
                    }
                }
            }
            pending.clear()
            val req = Request.Builder()
                .url(AppConfig.SITE_HOME_URL)
                .header("User-Agent", withContext(Dispatchers.Main) { userAgent(ctx) })
                .header("Accept", "text/html,application/xhtml+xml")
            if (cookie.isNotBlank()) req.header("Cookie", cookie)
            val code = client.newCall(req.build()).execute().use { it.code }
            val sets = synchronized(pending) { pending.toList() }.distinct()
            withContext(Dispatchers.Main) {
                val cm = CookieManager.getInstance()
                sets.forEach { cm.setCookie(AppConfig.SITE_URL, it) }
                cm.flush()
            }
            val synced = withContext(Dispatchers.Main) { SiteCookie.sync() }
            lastRefreshAt = System.currentTimeMillis()
            Log.i(TAG, "refresh http=$code setCookies=${sets.size} synced=$synced")
            withContext(Dispatchers.Main) { SiteCookie.read() } != null
        } catch (e: Exception) {
            Log.w(TAG, "refresh failed: ${e.message}")
            false
        }
    }

    private fun userAgent(ctx: Context): String =
        android.webkit.WebSettings.getDefaultUserAgent(ctx)

    /** 账户是否已经有官网凭证（没有就不用刷新）。 */
    fun hasCredentials(): Boolean = Session.currentSiteToken != null
}
