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

    /**
     * 官网首页的 Cookie 串（`a=b; c=d`）。
     *
     * 优先读 WebView 的 CookieManager；里面没有（冷启动、刚清过 WebView 数据、Cookie 还没落盘）时
     * 用账号存档里的凭证拼一份，保证调用方永远拿得到可用的登录态。
     */
    fun raw(url: String = AppConfig.SITE_URL): String? {
        val fromWeb = try {
            CookieManager.getInstance().getCookie(url)?.takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            null
        }
        if (fromWeb != null) return fromWeb
        val token = Session.currentSiteToken.orEmpty()
        if (token.isBlank()) return null
        val sess = Session.currentSiteSession.orEmpty()
        return if (sess.isBlank()) "$TOKEN_NAME=$token" else "$TOKEN_NAME=$token; $SESSION_NAME=$sess"
    }

    /**
     * 把账号存档里的凭证写回 CookieManager（主线程调用）。
     *
     * 隐藏 WebView 打开 `/home` 前必须先做这一步：CookieManager 为空时服务端只返回登录页，
     * 既不会续期也不会下发新 Cookie，看起来就像「Cookie 没有保存」。
     *
     * @return 是否真的写入了。
     */
    fun seed(url: String = AppConfig.SITE_URL): Boolean {
        val cm = try {
            CookieManager.getInstance()
        } catch (_: Exception) {
            return false
        }
        val existing = try {
            cm.getCookie(url)
        } catch (_: Exception) {
            null
        }
        val token = Session.currentSiteToken.orEmpty()
        if (token.isBlank()) return false
        val sess = Session.currentSiteSession.orEmpty()
        // CookieManager 里的 token 与当前账号一致：不用动。
        // 不一致（切换账号、或 Cookie 被清掉）时必须覆盖，否则会拿上一个账号的登录态去请求。
        if (pick(existing, TOKEN_NAME) == token &&
            pick(existing, SESSION_NAME).orEmpty() == sess
        ) {
            return false
        }
        cm.setCookie(url, "$TOKEN_NAME=$token; path=/")
        if (sess.isNotBlank()) cm.setCookie(url, "$SESSION_NAME=$sess; path=/")
        cm.flush()
        return true
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
