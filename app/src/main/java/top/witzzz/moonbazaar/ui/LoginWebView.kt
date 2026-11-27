package top.witzzz.moonbazaar.ui

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebViewDatabase
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.viewinterop.AndroidView
import top.witzzz.moonbazaar.data.AppConfig

// ---- In-app WebView OAuth ----
// The Worker finishes auth on an HTML page: /auth/done?auth=ok&uid=..&session_token=..
// A system browser cannot jump back, so we keep the flow inside a WebView and the
// moment the page lands on /auth/done we capture the token and close.
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebLoginScreen(
    startUrl: String,
    onToken: (String) -> Unit,
    onClose: () -> Unit
) {
    var loading by remember { mutableStateOf(true) }
    var captured by remember { mutableStateOf(false) }  // block after we got the token
    var shownUrl by remember { mutableStateOf(startUrl) }
    var webRef by remember { mutableStateOf<WebView?>(null) }
    // WebView 的创建（清 Cookie / 清缓存 / loadUrl）都在主线程同步执行，耗时可达数百毫秒。
    // 若在同一帧里创建，首帧会被它卡住，表现为「标题栏不显示、整屏白」。
    // 所以先让标题栏绘制一帧，再创建 WebView。
    var webReady by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        // 再等一小段，确保「标题栏」这一帧真的上屏后再创建 WebView，
        // 否则 WebView 初始化的主线程开销会把首帧一起吞掉（整屏白）。
        kotlinx.coroutines.delay(250)
        webReady = true
    }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        // 顶部：退出 / 刷新 / 当前域名 / 加载指示
        WebViewTopBar(
            title = Uri.parse(shownUrl).host ?: shownUrl,
            loading = loading,
            onRefresh = { webRef?.reload() },
            onClose = onClose,
            closeText = "✕ 退出",
            background = MaterialTheme.colorScheme.surfaceContainerHigh
        )
        // 占满标题栏以下的剩余空间（不要用 fillMaxSize，否则会超出可视区域）
        Box(Modifier.fillMaxWidth().weight(1f)) {
            if (loading) {
                CircularProgressIndicator(Modifier.align(Alignment.Center),
                    color = Color(0xFF6650a4))
            }
            if (webReady) {
            AndroidView(
            // 给 AndroidView 自己的 modifier 限定尺寸，避免原生 WebView 按 MATCH_PARENT
            // 撑到整个窗口、压住上面的标题栏。
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                // 每次打开登录页（含「添加账号」）先清空 WebView 的 Cookie 与浏览器数据，
                // 避免复用上一个账号的登录态、直接跳过授权。
                runCatching {
                    CookieManager.getInstance().apply {
                        removeAllCookies(null)
                        flush()
                    }
                    WebStorage.getInstance().deleteAllData()
                    WebViewDatabase.getInstance(ctx).apply {
                        clearFormData()
                        clearHttpAuthUsernamePassword()
                    }
                }

                WebView(ctx).apply {
                    webRef = this
                    // 不要手动设 MATCH_PARENT 的 layoutParams：Compose 会自己给
                    // AndroidView 分配尺寸与位置，手动设置会让 WebView 首帧铺满
                    // 整个窗口，把上面的标题栏盖住（页面加载完才恢复）。
                    // 首帧先不显示：WebView 刚创建时会闪一下白屏盖住标题栏，
                    // 等页面真正画出内容再显示。
                    visibility = View.INVISIBLE
                    setBackgroundColor(android.graphics.Color.TRANSPARENT)
                    // 清掉该 WebView 实例的缓存与历史
                    clearCache(true)
                    clearHistory()
                    clearFormData()
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.useWideViewPort = true
                    settings.loadWithOverviewMode = true
                    settings.cacheMode = android.webkit.WebSettings.LOAD_NO_CACHE
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(
                            view: WebView?, request: WebResourceRequest?
                        ): Boolean {
                            val u = request?.url?.toString() ?: return false
                            return tryFinish(u, view)
                        }

                        @Deprecated("Deprecated in Java")
                        override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                            return tryFinish(url ?: return false, view)
                        }

                        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                            if (url != null) {
                                shownUrl = url
                                tryFinish(url, view)
                            }
                            loading = true
                        }

                        // 页面已画出第一帧内容：此时再显示 WebView，避免白屏闪一下。
                        override fun onPageCommitVisible(view: WebView?, url: String?) {
                            if (view != null) view.visibility = View.VISIBLE
                        }

                        override fun onPageFinished(view: WebView?, url: String?) {
                            if (url != null) shownUrl = url
                            loading = false
                            view?.visibility = View.VISIBLE
                        }

                        // returns true means we handled (stop) the load
                        private fun tryFinish(url: String, view: WebView?): Boolean {
                            val u = Uri.parse(url)
                            if (u.scheme?.equals("https", true) == true &&
                                u.host?.equals(AppConfig.BASE_HOST, true) == true &&
                                u.path.orEmpty().startsWith("/auth/done")
                            ) {
                                if (!captured) {
                                    captured = true
                                    val token = u.getQueryParameter("session_token")
                                    if (!token.isNullOrBlank()) {
                                        onToken(token)
                                    } else {
                                        onClose()   // reached done but no token
                                    }
                                    if (view != null) {
                                        view.stopLoading()
                                        (view.parent as? ViewGroup)?.removeView(view)
                                    }
                                }
                                return true
                            }
                            return false
                        }
                    }
                    loadUrl(startUrl)
                }
                },
                update = { }
            )
            }
        }
    }
}
