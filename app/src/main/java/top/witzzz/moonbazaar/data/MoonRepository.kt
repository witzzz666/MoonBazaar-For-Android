package top.witzzz.moonbazaar.data

import android.util.Log
import org.json.JSONObject
import org.json.JSONTokener
import java.net.URLEncoder

// Lightweight typed/call wrappers around the Worker API.
// Every call talks to the Worker so the app never holds the MoonBazaar API key
// or the user token; Session is sent as X-Client-Session by MoonWorkerApi.
//
// 所有方法都接受可选的 `token`：调用方应在**发起请求时**快照当前账号的令牌并传入，
// 这样即使中途切换账号，请求也不会带着另一个账号的身份执行。
object MoonRepository {

    private val api = MoonWorkerApi()

    data class SessionStatus(
        val okResponse: Boolean,
        val sessionValid: Boolean,
        val hasTokens: Boolean,
        val uid: String?,
        val apiKeyConfigured: Boolean,
        val rawText: String
    )

    // /status
    fun status(token: String? = null): Pair<SessionStatus?, String> {
        val r = api.get("/status", token)
        if (!r.ok) return null to (r.readable.ifEmpty { "HTTP ${r.httpStatus}" })
        val j = r.json ?: return null to "无法解析返回 JSON"
        return SessionStatus(
            okResponse = j.optBoolean("ok", false),
            sessionValid = j.optBoolean("session_valid", false),
            hasTokens = j.optBoolean("has_tokens", false),
            uid = j.optString("uid").ifBlank { null },
            apiKeyConfigured = j.optBoolean("api_key_configured", false),
            rawText = r.body
        ) to ""
    }

    // /proxy/account/*
    fun accountStatus(token: String? = null): Pair<JSONObject?, String> =
        proxyObj("account/status", token)
    fun accountBalance(token: String? = null): Pair<JSONObject?, String> =
        proxyObj("account/balance", token)

    // /proxy/transactions
    fun transactions(limit: Int = 30, offset: Int = 0, token: String? = null): Pair<JSONObject?, String> =
        proxyObj("transactions?limit=$limit&offset=$offset", token)

    // /proxy/referrals and /proxy/referrals/link
    fun referrals(limit: Int = 50, offset: Int = 0, token: String? = null): Pair<JSONObject?, String> =
        proxyObj("referrals?limit=$limit&offset=$offset", token)
    fun referralLink(token: String? = null): Pair<JSONObject?, String> =
        proxyObj("referrals/link", token)

    // /proxy/products (withdraw products)
    fun products(token: String? = null): Pair<JSONObject?, String> = proxyObj("products", token)

    // /proxy/surveys (main surveys). Worker auto-fills client_ip.
    fun surveys(token: String? = null): Pair<JSONObject?, String> = proxyObj("surveys", token)

    /**
     * 直连官网问卷列表 `https://moonbazaar.xyz/home/get_surveys.php`（不经 Worker）。
     *
     * 鉴权只需要站点 Cookie：`zlex_token`（必须）+ `PHPSESSID`（有就带）。
     * 站点返回的是**带转义反斜杠**的 JSON（形如 `{\"ok\":true,...}`），由 [parseSiteJson] 兼容。
     *
     * @return (JSONObject?, 错误信息)。成功时错误信息为空串。
     */
    fun siteSurveys(siteToken: String, phpSessId: String): Pair<JSONObject?, String> {
        if (siteToken.isBlank()) return null to "缺少官网登录凭证，请重新登录"
        val cookie = buildString {
            append("zlex_token=").append(siteToken)
            if (phpSessId.isNotBlank()) append("; PHPSESSID=").append(phpSessId)
        }
        // 首选：Cookie 头
        val r1 = api.siteGet(AppConfig.SITE_SURVEYS_URL, cookie)
        val j1 = parseSiteJson(r1.body)
        if (j1 != null && isSurveyPayload(j1)) {
            Log.i("SiteSurveys", "ok via cookie http=${r1.httpStatus}")
            return j1 to ""
        }
        // 备用：把凭证放进查询参数（部分 PHP 端点不认 Cookie 头）
        val alt = buildString {
            append(AppConfig.SITE_SURVEYS_URL)
            append("?token=").append(urlEnc(siteToken))
            if (phpSessId.isNotBlank()) append("&PHPSESSID=").append(urlEnc(phpSessId))
        }
        val r2 = api.siteGet(alt, cookie)
        val j2 = parseSiteJson(r2.body)
        if (j2 != null && isSurveyPayload(j2)) {
            Log.i("SiteSurveys", "ok via query http=${r2.httpStatus}")
            return j2 to ""
        }
        val head = (r1.body.ifBlank { r2.body }).replace(Regex("\\s+"), " ")
        Log.w(
            "SiteSurveys",
            "failed cookie=http${r1.httpStatus} query=http${r2.httpStatus} body=${head.take(200)}"
        )
        val msg: String = when {
            r1.thrown.orEmpty().isNotBlank() -> r1.thrown.orEmpty()
            j1?.optJSONObject("error")?.optString("message")?.isNotBlank() == true ->
                j1.optJSONObject("error")!!.optString("message")
            j1?.optString("message")?.isNotBlank() == true -> j1.optString("message")
            j1?.optString("status") == "error" -> "官网登录态已失效，请重新登录后再试"
            else -> "官网返回异常（HTTP ${r1.httpStatus}），请重新登录后再试"
        }
        return null to msg
    }

    /**
     * 站点 JSON 里只要出现问卷数组就认为拿到了列表。
     *
     * 两种真实形态：
     *  - Worker 代理：`{"ok":true,"data":{"surveys":[…]}}`
     *  - 官网直连：`{"status":"success","data":[{…survey…}],"country_code":"CN",…}`
     */
    internal fun isSurveyPayload(o: JSONObject?): Boolean {
        if (o == null) return false
        if (o.optJSONArray("surveys") != null) return true
        if (o.optJSONArray("data") != null) return true
        return o.optJSONObject("data")?.optJSONArray("surveys") != null
    }

    /**
     * 解析站点返回的 JSON，兼容三种形态：
     * 1. 正常 JSON：`{"ok":true,"surveys":[…]}`；
     * 2. 每个引号都带反斜杠：`{\"ok\":true,\"surveys\":[…]} `；
     * 3. 整体是 JSON 字符串，内容才是 JSON：`"{\"ok\":true}"`。
     */
    internal fun parseSiteJson(body: String): JSONObject? {
        val t = body.trim()
        if (t.isEmpty()) return null
        // 1) 正常 JSON
        runCatching { return JSONObject(t) }
        // 2) 整体被包成 JSON 字符串（"{\"ok\":true}"）：先解成普通字符串再解析
        if (t.startsWith("\"")) {
            runCatching {
                val inner = JSONTokener(t).nextValue()
                if (inner is String) return JSONObject(inner.trim())
            }
        }
        // 3) 每个引号前都有反斜杠（站点对 JSON 做了二次转义）
        val unescaped = t.replace("\\\"", "\"").replace("\\\\", "\\")
        runCatching { return JSONObject(unescaped) }
        runCatching {
            val inner = JSONTokener(unescaped).nextValue()
            if (inner is String) return JSONObject(inner.trim())
        }
        return null
    }

    private fun urlEnc(s: String): String = try {
        URLEncoder.encode(s, "UTF-8")
    } catch (_: Exception) {
        s
    }

    data class WriteResult(val body: String, val http: Int, val okHttp: Boolean) {
        val json: JSONObject?
            get() = try {
                if (body.isBlank()) null else JSONObject(body)
            } catch (_: Exception) {
                null
            }
        val errorMsg: String
            get() = json?.optJSONObject("error")?.optString("message")
                ?.takeIf { it.isNotBlank() }
                ?: (if (okHttp) "" else "HTTP $http")
    }

    // POST /proxy/withdrawals (idempotency-required)
    fun submitWithdrawal(
        productId: Long,
        amount: String?,
        deliveryDetails: String,
        note: String?,
        token: String? = null
    ): WriteResult {
        val b = JSONObject().apply {
            put("product_id", productId)
            if (!amount.isNullOrBlank()) put("amount", amount.trim())
            put("delivery_details", deliveryDetails)
            if (!note.isNullOrBlank()) put("note", note)
        }
        return write("withdrawals", b, token = token)
    }

    // POST /proxy/codes/redeem (idempotency-required); usually HTTP 202,
    // returns a confirmation_url the user must open to finalise.
    fun redeemCode(code: String, token: String? = null): WriteResult {
        val b = JSONObject().put("code", code.trim())
        return write("codes/redeem", b, token = token)
    }

    // POST /proxy/account/email-verification (no idempotency header).
    fun requestEmailVerification(token: String? = null): WriteResult {
        val b = JSONObject()
        return write("account/email-verification", b, needIdem = false, token = token)
    }

    /**
     * MoonBazaar 官方 token（由 Worker 的 `/home/mb_token` 用 session_token 换取）。
     *
     * 与 `/user/tokens` 的区别：这里默认**带 refresh_token**，可以在客户端本地续期 /
     * 直接以 Bearer 调 MoonBazaar 官方 API。App 每次启动都会申请一次，只保存在内存中。
     */
    data class MbToken(
        val uid: String = "",
        val accessToken: String = "",
        val refreshToken: String = "",
        val tokenType: String = "Bearer",
        val expiresAt: Long = 0L,
        val expiresIn: Long = 0L,
        val scope: String = "",
        val expired: Boolean = false
    ) {
        val scopeCount: Int
            get() = scope.split(' ', '\t', '\n').count { it.isNotBlank() }
    }

    // GET /home/mb_token —— 用 session_token 换完整 MoonBazaar token（含 refresh_token）
    fun mbToken(token: String? = null): Pair<MbToken?, String> {
        val r = api.get("/home/mb_token", token)
        if (!r.ok) return null to (r.readable.ifEmpty { "HTTP ${r.httpStatus}" })
        return parseMbToken(r.body)
    }

    /** 解析 `/home/mb_token` 的响应体（拆出来便于单元测试，不依赖网络）。 */
    fun parseMbToken(body: String): Pair<MbToken?, String> {
        val j = try {
            if (body.isBlank()) null else JSONObject(body)
        } catch (_: Exception) {
            null
        } ?: return null to "无法解析 token 响应"
        if (!j.optBoolean("ok", true)) {
            val msg = j.optJSONObject("error")?.optString("message").orEmpty()
            return null to msg.ifBlank { "申请 token 失败" }
        }
        val d = j.optJSONObject("data") ?: return null to "响应缺少 data"
        val access = d.optString("access_token")
        if (access.isBlank()) return null to "响应缺少 access_token"
        return MbToken(
            uid = d.optString("uid"),
            accessToken = access,
            refreshToken = d.optString("refresh_token"),
            tokenType = d.optString("token_type").ifBlank { "Bearer" },
            expiresAt = d.optLong("expires_at", 0L),
            expiresIn = d.optLong("expires_in", 0L),
            scope = d.optString("scope"),
            expired = d.optBoolean("expired", false)
        ) to ""
    }

    // DELETE /user/tokens: revoke WORKER session server-side.
    fun logout(token: String? = null): WriteResult {
        val r = api.del("/user/tokens", token)
        return WriteResult(body = r.body, http = r.httpStatus, okHttp = r.ok)
    }

    private fun proxyObj(path: String, token: String? = null): Pair<JSONObject?, String> {
        val r = api.proxyGet(path, token)
        if (!r.ok) return null to (r.readable.ifEmpty { "HTTP ${r.httpStatus}" })
        val j = r.json ?: return null to "上游返回空或非 JSON"
        return j to ""
    }

    private fun write(
        path: String,
        body: JSONObject,
        needIdem: Boolean = true,
        token: String? = null
    ): WriteResult {
        val res = if (needIdem) api.proxyPost(path, body, genIdem(path, token), token)
        else api.proxyPostNoIdem(path, body, token)
        return WriteResult(body = res.body, http = res.httpStatus, okHttp = res.ok)
    }

    private var seq = 0L

    /** 幂等键绑定到具体账号（token 前缀），避免跨账号复用同一键。 */
    fun genIdem(prefix: String = "op", token: String? = null): String {
        val ts = System.currentTimeMillis()
        seq = (seq + 1) % 100000
        val who = token?.take(8) ?: "anon"
        return "$prefix-$ts-$seq-$who"
    }

    // Render a GC (Gascoin) value as an integer string: drop the decimals.
    fun gcInteger(value: Any?): String {
        if (value == null) return "0"
        val d: Double = when (value) {
            is Number -> value.toDouble()
            is String -> value.trim().toDoubleOrNull()
            else -> null
        } ?: return "0"
        if (d < 0) return "0"
        return kotlin.math.floor(d).toLong().toString()
    }
}
