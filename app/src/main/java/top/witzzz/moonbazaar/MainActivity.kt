package top.witzzz.moonbazaar

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import top.witzzz.moonbazaar.data.AppConfig
import top.witzzz.moonbazaar.data.Session
import top.witzzz.moonbazaar.data.SiteCookie
import top.witzzz.moonbazaar.data.SiteRefresh
import top.witzzz.moonbazaar.data.isTrustedCallbackUri
import top.witzzz.moonbazaar.ui.MainViewModel
import top.witzzz.moonbazaar.ui.MoonApp
import top.witzzz.moonbazaar.ui.WebLoginScreen
import top.witzzz.moonbazaar.ui.theme.MyApplicationTheme

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    /**
     * 只有在本进程内主动点过「登录 / 添加账号」，才接受登录回调。
     *
     * 作用：防止外部应用用 `adb shell am start -a android.intent.action.VIEW -d "mnb://auth/callback?session_token=xxx"`
     * 把任意令牌注入进来；同时让回调令牌具有「一次性」语义——被消费后必须重新发起登录才会再次接受。
     */
    private var loginRequested = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // 隐藏顶部状态栏；内容仍按刘海安全区排版，避免被相机开孔遮挡。
        WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.statusBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        // Still accept a deep link that carries a token (defensive).
        captureSessionFromIntent(intent)

        setContent {
            Root()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        captureSessionFromIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        // 每次打开/切回前台：后台访问一次官网 /home，取回新的 zlex_token 与 PHPSESSID。
        SiteRefresh.onForeground()
    }

    @Composable
    private fun Root() {
        var showLogin by remember { mutableStateOf(false) }

        MyApplicationTheme {
            Box(
                Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.displayCutout)
            ) {
                MoonApp(
                    vm = viewModel,
                    openUrl = ::openExternal,
                    onStartLogin = {
                        // 标记登录已发起，登录回调才会被接受
                        loginRequested = true
                        showLogin = true
                    }
                )

                if (showLogin) {
                    WebLoginScreen(
                        startUrl = AppConfig.BASE_URL.trimEnd('/') + "/auth/login",
                        onToken = { token ->
                            // 新登录：加入账号列表并设为当前，再拉取数据
                            showLogin = false
                            loginRequested = false
                            Session.addAndSelect(token)
                            // 登录流程里站点可能已经下发官网 Cookie，顺手保存（没有则忽略）
                            SiteCookie.sync()
                            viewModel.refreshAuthState()
                            viewModel.reloadAll()
                        },
                        onClose = {
                            showLogin = false
                            loginRequested = false
                        }
                    )
                }
            }
        }
    }

    private fun captureSessionFromIntent(i: Intent?) {
        val data = i?.data ?: return
        // 必须与 AndroidManifest 登记的 scheme / host / path 完全一致，否则直接忽略
        if (!isTrustedCallbackUri(data.scheme, data.host, data.path)) return
        // 未在本进程发起过登录时，一律不接受外部传入的令牌
        if (!loginRequested) return
        val token = data.getQueryParameter("session_token")?.trim().orEmpty()
        if (token.isEmpty()) return

        // 消费掉本次登录状态，使同一回调无法被重放
        loginRequested = false
        Session.addAndSelect(token)
        SiteCookie.sync()
        viewModel.refreshAuthState()
        viewModel.reloadAll()
    }

    private fun openExternal(url: String) {
        try {
            CustomTabsIntent.Builder().setShowTitle(true).build()
                .launchUrl(this, Uri.parse(url))
        } catch (_: Exception) {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }
    }
}
