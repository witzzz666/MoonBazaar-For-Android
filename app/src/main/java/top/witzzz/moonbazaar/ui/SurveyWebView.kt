package top.witzzz.moonbazaar.ui

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.Uri
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayInputStream
import java.util.concurrent.TimeUnit

// ============================ 问卷 WebView ============================
// 问卷在 App 内打开，并在 WebView 内对请求做重写 / 拦截，避免依赖被墙的域名。
//
// 重写（仅替换域名/路径前缀，其余原样保留，包含全部 query 参数）：
//   google.com/recaptcha   -> recaptcha.net/recaptcha
//   ajax.googleapis.com    -> ajax.loli.net
//   api.ipify.org          -> api64.ipify.org
//
// 拦截（直接阻止加载，不做任何响应改写）：
//   www.google.com/jsapi
//   firebase.googleapis.com/*
//   accounts.google.com/gsi/client
//   analytics.tiktok.com/*
//   connect.facebook.net/*

private const val RECAPTCHA_FROM = "google.com/recaptcha"
private const val RECAPTCHA_TO = "recaptcha.net/recaptcha"
private const val AJAX_FROM = "ajax.googleapis.com"
private const val AJAX_TO = "ajax.loli.net"
private const val IPIFY_FROM = "api.ipify.org"
private const val IPIFY_TO = "api64.ipify.org"

/** 命中则直接拦截（纯函数，便于单测）。 */
internal fun isBlockedHostPath(host: String, path: String): Boolean {
    val h = host.lowercase()
    val p = path.ifEmpty { "/" }
    return when {
        // 整个域名下所有路径
        h == "firebase.googleapis.com" -> true
        h == "analytics.tiktok.com" -> true
        h == "connect.facebook.net" -> true
        // 精确路径（允许其子路径），避免误伤同前缀的其它路径
        h == "www.google.com" && (p == "/jsapi" || p.startsWith("/jsapi/")) -> true
        h == "accounts.google.com" &&
            (p == "/gsi/client" || p.startsWith("/gsi/client/")) -> true
        else -> false
    }
}

/** 命中则直接拦截。 */
internal fun isBlockedSurveyUrl(url: String): Boolean {
    val u = Uri.parse(url)
    val host = u.host ?: return false
    return isBlockedHostPath(host, u.path.orEmpty())
}

/** 按规则重写 URL；参数与其余部分保持不变。 */
internal fun rewriteSurveyUrl(url: String): String {
    var out = url
    if (out.contains(RECAPTCHA_FROM)) out = out.replace(RECAPTCHA_FROM, RECAPTCHA_TO)
    if (out.contains(AJAX_FROM)) out = out.replace(AJAX_FROM, AJAX_TO)
    if (out.contains(IPIFY_FROM)) out = out.replace(IPIFY_FROM, IPIFY_TO)
    return out
}

// 用于代替 WebView 去实际拉取重写后的子资源
private val surveyProxyClient: OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(20, TimeUnit.SECONDS)
    .readTimeout(30, TimeUnit.SECONDS)
    .writeTimeout(30, TimeUnit.SECONDS)
    .followRedirects(true)
    .build()

/** 拦截时返回的空响应（403，空 body）。 */
private fun blockedResponse(): WebResourceResponse =
    WebResourceResponse(
        "text/plain",
        "utf-8",
        403,
        "Blocked",
        emptyMap<String, String>(),
        ByteArrayInputStream(ByteArray(0))
    )

/** 用重写后的 URL 代取资源并回填给 WebView。 */
private fun proxyFetch(newUrl: String, request: WebResourceRequest): WebResourceResponse? {
    return try {
        val b = Request.Builder().url(newUrl)
        request.requestHeaders.forEach { (k, v) ->
            // Host 会由 OkHttp 按新地址重算；Accept-Encoding 固定 identity 以拿到原始字节
            if (!k.equals("Host", true) && !k.equals("Accept-Encoding", true)) {
                runCatching { b.header(k, v) }
            }
        }
        b.header("Accept-Encoding", "identity")

        val resp = surveyProxyClient.newCall(b.build()).execute()
        val body = resp.body ?: return null
        val ct = body.contentType()
        val mime = if (ct != null) "${ct.type}/${ct.subtype}" else "text/plain"
        val charset = ct?.charset()?.name() ?: "utf-8"
        val headers = HashMap<String, String>()
        resp.headers.forEach { (k, v) -> headers[k] = v }
        WebResourceResponse(
            mime,
            charset,
            resp.code,
            resp.message.ifEmpty { "OK" },
            headers,
            body.byteStream()
        )
    } catch (_: Exception) {
        null
    }
}

// ==================== 问卷完成 / 退出 回调拦截 ====================
// 普通形式：https://moonbazaar.xyz/home/surveys?status=a&gc=b
//   status: 1=成功  2=不符合条件
//   gc    : 获得的 GC（整数）；也可能是 check_history（则不显示金额，提示查看历史）
// CPX 形式：https://moonbazaar.xyz/home/surveys?type=out&GC=补偿金额&cpx_message_id=...&from=cpx
//   type  : out（被踢出）或 complete（完成）
//   GC    : 成功/不符合条件时的补偿金额（可能没有）
//   cpx_message_id: 一般是网址，成功时用于打开评价页
//   from  : 忽略

enum class SurveyOutcome { SUCCESS, INELIGIBLE, OUT, NEUTRAL }

internal data class SurveyResult(
    val outcome: SurveyOutcome = SurveyOutcome.NEUTRAL,
    val gcText: String? = null,          // 非空则显示金额
    val checkHistory: Boolean = false,   // gc=check_history
    val messageId: String? = null        // CPX 评价链接
) {
    /** 评价链接看起来像网址时才提供入口。 */
    val ratingUrl: String?
        get() = messageId?.takeIf { it.startsWith("http://", true) || it.startsWith("https://", true) }
}

private const val RESULT_HOST = "moonbazaar.xyz"
private const val RESULT_PATH = "/home/surveys"

/** 纯函数版本，便于单测。非问卷回调时返回 null。 */
internal fun parseSurveyResult(
    host: String,
    path: String,
    query: Map<String, String>
): SurveyResult? {
    if (!host.equals(RESULT_HOST, ignoreCase = true)) return null
    if (path.trimEnd('/') != RESULT_PATH) return null

    val status = query["status"]?.trim()
    val type = query["type"]?.trim()?.lowercase()
    val gcRaw = (query["gc"] ?: query["GC"])?.trim()
    val msgId = query["cpx_message_id"]?.trim()?.takeIf { it.isNotEmpty() }

    // 必须至少命中一个可识别信号，避免劫持正常的浏览导航
    val recognized = status == "1" || status == "2" ||
        type == "complete" || type == "out" || msgId != null
    if (!recognized) return null

    val outcome = when {
        type == "complete" -> SurveyOutcome.SUCCESS
        type == "out" -> SurveyOutcome.OUT
        status == "1" -> SurveyOutcome.SUCCESS
        status == "2" -> SurveyOutcome.INELIGIBLE
        else -> SurveyOutcome.NEUTRAL
    }
    val checkHistory = gcRaw?.equals("check_history", ignoreCase = true) == true
    val gcText = if (gcRaw.isNullOrEmpty() || checkHistory) null else gcRaw

    return SurveyResult(
        outcome = outcome,
        gcText = gcText,
        checkHistory = checkHistory,
        messageId = msgId
    )
}

internal fun parseSurveyResultUrl(url: String): SurveyResult? {
    val u = Uri.parse(url)
    val host = u.host ?: return null
    val path = u.path ?: return null
    val q = HashMap<String, String>()
    u.queryParameterNames?.forEach { name ->
        u.getQueryParameter(name)?.let { q[name] = it }
    }
    return parseSurveyResult(host, path, q)
}

/** 顶部状态提示条：按状态着色并展示信息。 */
@Composable
private fun SurveyResultBanner(
    result: SurveyResult,
    onOpenRating: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val container: Color
    val onContainer: Color
    val title: String
    when (result.outcome) {
        SurveyOutcome.SUCCESS -> {
            container = Color(0xFFE3F5E8); onContainer = Color(0xFF1B5E20); title = "问卷已完成"
        }
        SurveyOutcome.INELIGIBLE -> {
            container = Color(0xFFFDE7E6); onContainer = Color(0xFFB3261E); title = "不符合条件"
        }
        SurveyOutcome.OUT -> {
            container = Color(0xFFFFF3E0); onContainer = Color(0xFF8A5300); title = "已退出问卷"
        }
        SurveyOutcome.NEUTRAL -> {
            container = Color(0xFFE8EEF7); onContainer = Color(0xFF274B78); title = "问卷状态更新"
        }
    }

    val isCompensation = result.outcome != SurveyOutcome.SUCCESS
    val amountLine: String? = when {
        result.checkHistory ->
            if (isCompensation) "如有补偿，请到「历史记录」查看"
            else "获得奖励请到「历史记录」查看"
        result.gcText != null ->
            if (isCompensation) "补偿 ${result.gcText} GC" else "获得 ${result.gcText} GC"
        else -> null
    }

    Surface(color = container, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, color = onContainer, fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.bodyMedium)
                if (amountLine != null) {
                    Spacer(Modifier.height(2.dp))
                    Text(amountLine, color = onContainer,
                        style = MaterialTheme.typography.bodySmall)
                }
            }
            result.ratingUrl?.let { url ->
                TextButton(onClick = { onOpenRating(url) }) {
                    Text("评价问卷", color = onContainer)
                }
            }
            TextButton(onClick = onDismiss) {
                Text("✕", color = onContainer)
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun SurveyWebScreen(
    startUrl: String,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    var loading by remember { mutableStateOf(true) }
    var shownUrl by remember { mutableStateOf(startUrl) }
    var webRef by remember { mutableStateOf<WebView?>(null) }
    // 问卷完成/退出回调：非空时在顶部展示状态提示条
    var result by remember { mutableStateOf<SurveyResult?>(null) }

    // 注意：必须接收调用方的 modifier（Scaffold 的 innerPadding），
    // 否则本页自己的标题栏会被 Scaffold 顶栏盖住而"消失"。
    Column(modifier.fillMaxSize()) {
        // 顶部：返回 / 刷新 / 当前域名 / 加载指示
        WebViewTopBar(
            title = Uri.parse(shownUrl).host ?: shownUrl,
            loading = loading,
            onRefresh = { webRef?.reload() },
            onClose = onClose
        )

        // 状态提示条（成功 / 不符合条件 / 退出）
        result?.let { r ->
            SurveyResultBanner(
                result = r,
                onOpenRating = { url ->
                    result = null
                    webRef?.loadUrl(url)
                },
                onDismiss = { result = null }
            )
        }

        AndroidView(
            // 占满标题栏以下的剩余空间（不要用 fillMaxSize，否则会超出可视区域）
            modifier = Modifier.fillMaxWidth().weight(1f),
            factory = { ctx ->
                WebView(ctx).apply {
                    webRef = this
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.useWideViewPort = true
                    settings.loadWithOverviewMode = true
                    settings.javaScriptCanOpenWindowsAutomatically = true
                    settings.setSupportMultipleWindows(false)
                    settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                    settings.userAgentString = settings.userAgentString + " MoonBazaarAndroid"

                    webViewClient = object : WebViewClient() {

                        // 主框架导航：问卷回调拦截 → 状态提示；其它则拦截/重写
                        override fun shouldOverrideUrlLoading(
                            view: WebView?,
                            request: WebResourceRequest?
                        ): Boolean {
                            val u = request?.url?.toString() ?: return false
                            parseSurveyResultUrl(u)?.let {
                                result = it
                                return true          // 不加载目标页，仅在顶部提示
                            }
                            if (isBlockedSurveyUrl(u)) return true          // 直接拦截，不加载
                            val rw = rewriteSurveyUrl(u)
                            if (rw != u) {
                                view?.loadUrl(rw)
                                return true
                            }
                            return false
                        }

                        @Deprecated("Deprecated in Java")
                        override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                            val u = url ?: return false
                            parseSurveyResultUrl(u)?.let {
                                result = it
                                return true
                            }
                            if (isBlockedSurveyUrl(u)) return true
                            val rw = rewriteSurveyUrl(u)
                            if (rw != u) {
                                view?.loadUrl(rw)
                                return true
                            }
                            return false
                        }

                        // 子资源（JS / XHR / 图片等）：拦截 & 重写
                        override fun shouldInterceptRequest(
                            view: WebView?,
                            request: WebResourceRequest?
                        ): WebResourceResponse? {
                            val u = request?.url?.toString() ?: return null
                            if (isBlockedSurveyUrl(u)) return blockedResponse()
                            val rw = rewriteSurveyUrl(u)
                            if (rw != u) return proxyFetch(rw, request)
                            return null
                        }

                        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                            loading = true
                            if (url != null) shownUrl = url
                        }

                        override fun onPageFinished(view: WebView?, url: String?) {
                            loading = false
                            if (url != null) shownUrl = url
                        }
                    }

                    loadUrl(startUrl)
                }
            }
        )
    }
}
