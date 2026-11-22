package top.witzzz.moonbazaar.data

import android.webkit.CookieManager

/**
 * 官网 moonbazaar.xyz 的登录态 Cookie 读取。
 *
 * 直连官网问卷接口（`/home/get_surveys.php`）只需要两样东西：
 *  - `zlex_token`：站点登录 token（id.zlexq.com 上叫 `token`，moonbazaar.xyz 上叫 `zlex_token`）；
 *  - `PHPSESSID`：站点会话 id。
 * 这里把它们从 App 内 WebView 里取出来，交给 [Session] 与 Worker session token 一起加密保存。
 *
 * 注意：`CookieManager.getCookie` 需要在主线程调用（由 Compose / Activity 回调触发即可）。
 */
object SiteCookie {

    const val TOKEN_NAME: String = "zlex_token"
    const val SESSION_NAME: String = "PHPSESSID"

    /** 官网首页的 Cookie 串（`a=b; c=d`），没有则 null。 */
    fun raw(url: String = AppConfig.SITE_URL): String? = try {
        CookieManager.getInstance().getCookie(url)?.takeIf { it.isNotBlank() }
    } catch (_: Exception) {
        null
    }

    /** 从 Cookie 串里按名字取值。 */
    fun pick(cookie: String?, name: String): String? = cookie
        ?.split("; ")
        ?.firstOrNull { it.startsWith("$name=") }
        ?.substringAfter('=')
        ?.trim()
        ?.takeIf { it.isNotEmpty() }

    fun read(url: String = AppConfig.SITE_URL): String? = pick(raw(url), TOKEN_NAME)

    /** 读出 zlex_token（必须）与 PHPSESSID（可选），存进当前账号。 */
    fun sync(sessionToken: String? = null): Boolean {
        val cookie = raw() ?: return false
        val token = pick(cookie, TOKEN_NAME) ?: return false
        val sess = pick(cookie, SESSION_NAME).orEmpty()
        if (sessionToken == null && token == Session.currentSiteToken &&
            sess == Session.currentSiteSession.orEmpty()
        ) {
            return false
        }
        Session.updateSiteCredentials(token, sess, sessionToken)
        return true
    }
}
