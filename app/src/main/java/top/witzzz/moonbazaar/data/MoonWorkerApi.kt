package top.witzzz.moonbazaar.data

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 对 MoonBazaar Worker 的轻量 HTTP 客户端（session 鉴权版）。
 *
 *  - App 一律请求 [AppConfig.BASE_URL]（自己部署的 Cloudflare Worker），由 Worker 转发
 *    到 MoonBazaar 上游并自动注入 X-API-Key / Authorization(Bearer)。
 *  - 客户端身份使用 Worker 发放的 session token（见 [Session]），每次请求自动带：
 *        X-Client-Session: <session_token>
 *  - 写操作统一走 [proxyPost]，并强制携带 [idempotencyKey]（MoonBazaar 要求 8-128 字符）。
 */
class MoonWorkerApi {

    private val jsonType = "application/json; charset=utf-8".toMediaType()

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .writeTimeout(45, TimeUnit.SECONDS)
        .build()

    data class CallResult(
        val ok: Boolean,               // HTTP 是否 2xx
        val httpStatus: Int = -1,
        val body: String = "",         // Worker / 上游原始响应文本
        val replayed: Boolean = false, // Idempotent-Replayed == true
        val thrown: String? = null     // 本地网络/IO 错误
    ) {
        val json: JSONObject?
            get() = try {
                if (body.isBlank()) null else JSONObject(body)
            } catch (_: Exception) {
                null
            }

        val upError: JSONObject?
            get() = json?.optJSONObject("error")

        /** 对 UI 友好：优先取上游 error.message，其次本地 thrown/HTTP。 */
        val readable: String
            get() = upError?.optString("message")?.takeIf { it.isNotBlank() }
                ?: thrown
                ?: if (!ok) "HTTP $httpStatus" else ""
    }

    /** Worker 自带的只读接口（如 /status、/user/tokens）。 */
    fun get(path: String, token: String? = null): CallResult = exec("GET", path, null, null, token)

    /** Worker 自带写接口（如 DELETE /user/tokens 登出）。 */
    fun del(path: String, token: String? = null): CallResult = exec("DELETE", path, null, null, token)

    /** 经 Worker 代理的只读 GET：path 不带 /proxy 前缀，如 account/status。 */
    fun proxyGet(path: String, token: String? = null): CallResult =
        exec("GET", "/proxy/$path", null, null, token)

    /** 经 Worker 代理的写操作，body 可为 null，需幂等键。path 不带 /proxy。 */
    fun proxyPost(
        path: String,
        body: JSONObject?,
        idempotencyKey: String,
        token: String? = null
    ): CallResult = exec("POST", "/proxy/$path", body, idempotencyKey, token)

    /** 经 Worker 代理、**无需幂等键**的写操作（如 account/email-verification）。 */
    fun proxyPostNoIdem(path: String, body: JSONObject?, token: String? = null): CallResult =
        exec("POST", "/proxy/$path", body, null, token)

    /**
     * @param token 该请求所属账号的 session token。由调用方在**发起时**快照传入，
     *              避免请求真正执行时账号已被切换、从而把 A 的操作发到 B 名下。
     *              为 null 时退回使用当前全局 session。
     */
    private fun exec(
        method: String,
        urlPath: String,
        body: JSONObject?,
        idem: String?,
        token: String? = null
    ): CallResult {
        return try {
            val url = AppConfig.BASE_URL.trimEnd('/') + urlPath
            val b = Request.Builder()
                .url(url)
                .header("X-Source-App", AppConfig.SOURCE_APP)
                .header("Accept", "application/json")
            val sess = token ?: Session.tokenValue
            if (sess != null) b.header("X-Client-Session", sess)
            if (idem != null) b.header("Idempotency-Key", idem)

            val req = when (method.uppercase()) {
                "GET" -> b.get().build()
                "DELETE" -> b.delete().build()
                else -> {
                    val rb = if (body == null) "{}".toRequestBody(jsonType)
                    else body.toString().toRequestBody(jsonType)
                    b.post(rb).build()
                }
            }

            client.newCall(req).execute().use { resp ->
                CallResult(
                    ok = resp.isSuccessful,
                    httpStatus = resp.code,
                    body = resp.body?.string() ?: "",
                    replayed = resp.header("Idempotent-Replayed") == "true"
                )
            }
        } catch (e: Exception) {
            CallResult(ok = false, httpStatus = -1, body = "",
                thrown = "网络请求失败：${e.message}")
        }
    }
}
