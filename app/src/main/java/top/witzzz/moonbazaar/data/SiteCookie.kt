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
     * 用账号存档里的完整 Cookie 串，再退回到 `zlex_token` / `PHPSESSID` 拼一份，
     * 保证调用方永远拿得到可用的登录态。
     */
    fun raw(url: String = AppConfig.SITE_URL): String? {
        val fromWeb = try {
            CookieManager.getInstance().getCookie(url)?.takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            null
        }
        if (fromWeb != null) return fromWeb
        Session.currentCookies?.let { return it }
        val token = Session.currentSiteToken.orEmpty()
        if (token.isBlank()) return null
        val sess = Session.currentSiteSession.orEmpty()
        return if (sess.isBlank()) "$TOKEN_NAME=$token" else "$TOKEN_NAME=$token; $SESSION_NAME=$sess"
    }

    /**
     * 把账号存档里的**全部** Cookie 写回 CookieManager（主线程调用）。
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
        val stored = Session.currentCookies ?: run {
            val token = Session.currentSiteToken.orEmpty()
            if (token.isBlank()) return false
            val sess = Session.currentSiteSession.orEmpty()
            if (sess.isBlank()) "$TOKEN_NAME=$token" else "$TOKEN_NAME=$token; $SESSION_NAME=$sess"
        }
        val existing = try {
            cm.getCookie(url)
        } catch (_: Exception) {
            null
        }
        if (existing != null && samePairs(existing, stored)) return false
        var wrote = false
        stored.split(';').forEach { part ->
            val kv = part.trim()
            if (kv.isEmpty() || !kv.contains('=')) return@forEach
            cm.setCookie(url, "$kv; path=/")
            wrote = true
        }
        if (wrote) cm.flush()
        return wrote
    }

    private fun samePairs(a: String, b: String): Boolean {
        fun parse(s: String): Map<String, String> = s.split(';')
            .mapNotNull { part ->
                val kv = part.trim()
                val i = kv.indexOf('=')
                if (i <= 0) null else kv.substring(0, i).trim() to kv.substring(i + 1).trim()
            }
            .toMap()
        return parse(a) == parse(b)
    }

    /** 从 Cookie 串里按名字取值。 */
    fun pick(cookie: String?, name: String): String? = cookie
        ?.split("; ")
        ?.firstOrNull { it.startsWith("$name=") }
        ?.substringAfter('=')
        ?.trim()
        ?.takeIf { it.isNotEmpty() }

    fun read(url: String = AppConfig.SITE_URL): String? = pick(raw(url), TOKEN_NAME)

    /** 读出完整 Cookie 串（含 `zlex_token`、`PHPSESSID` 等全部条目）并存进当前账号。 */
    fun sync(sessionToken: String? = null): Boolean {
        val cookie = raw() ?: return false
        val token = pick(cookie, TOKEN_NAME) ?: return false
        val sess = pick(cookie, SESSION_NAME).orEmpty()
        if (sessionToken == null && token == Session.currentSiteToken &&
            sess == Session.currentSiteSession.orEmpty() && cookie == Session.currentCookies
        ) {
            return false
        }
        Session.updateSiteCredentials(token, sess, sessionToken, cookie)
        return true
    }
}
