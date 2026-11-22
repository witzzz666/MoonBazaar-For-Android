package top.witzzz.moonbazaar.data

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

// App-level constants.
object AppConfig {
    const val BASE_URL: String = "https://mnb.witzzz.top"

    // Host without scheme, used to detect /auth/done page inside the login WebView.
    const val BASE_HOST: String = "mnb.witzzz.top"

    // 官网（App 内 WebView 打开，和登录流程共用 Cookie）
    const val SITE_URL: String = "https://moonbazaar.xyz"
    const val SITE_HOME_URL: String = "$SITE_URL/home"

    // 官网问卷列表接口（PowerShell/PHP 端点，用 zlex_token + PHPSESSID 鉴权，不经 Worker）
    const val SITE_SURVEYS_URL: String = "$SITE_HOME_URL/get_surveys.php"

    // Custom-scheme callback resuming the app after OAuth.
    const val CALLBACK_URI: String = "mnb://auth/callback"

    // Recognised scheme / authority of our deep link. All three parts must match
    // the intent-filter declared in AndroidManifest.xml.
    const val CALLBACK_SCHEME: String = "mnb"
    const val CALLBACK_HOST: String = "auth"
    const val CALLBACK_PATH: String = "/callback"

    // Logging tag when calling the Worker.
    const val SOURCE_APP: String = "android"
}

/**
 * 一个已登录的账号。
 *
 * 同一个账号会同时保存三套凭证，彼此独立、互不覆盖：
 * - [token]：Worker 的 X-Client-Session（调 API 用）；
 * - [siteToken] / [siteSession]：官网 moonbazaar.xyz 的 `zlex_token` 与 `PHPSESSID` Cookie（直连官网问卷接口用）；
 * - [mbAccessToken] / [mbRefreshToken] / [mbExpiresAt]：`GET /home/mb_token` 申请的 OAuth token（expiresAt 为服务器给的秒级时间戳）。
 */
data class Account(
    val uid: String = "",
    val username: String = "",
    val token: String = "",
    val siteToken: String = "",
    val siteSession: String = "",
    val mbAccessToken: String = "",
    val mbRefreshToken: String = "",
    val mbExpiresAt: Long = 0L
) {
    /** 稳定主键：优先 UID；登录早期还没拿到 UID 时用 token 前缀。 */
    val key: String
        get() = if (uid.isNotBlank()) "uid:$uid" else "tok:${token.take(12)}"

    val displayName: String
        get() = when {
            username.isNotBlank() -> username
            uid.isNotBlank() -> "UID $uid"
            else -> "未命名账号"
        }

    val hasSiteToken: Boolean get() = siteToken.isNotBlank()
    val hasSiteSession: Boolean get() = siteSession.isNotBlank()
    val hasMbToken: Boolean get() = mbAccessToken.isNotBlank()
}

/**
 * 会话数据的本地加解密。
 *
 * 密钥由 Android Keystore 生成并保管（不出进程、不随备份导出），
 * 落盘格式为 "v1:" + base64(iv) + ":" + base64(ciphertext)。
 */
internal object SessionCrypto {

    private const val KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "moonbazaar_session_key"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128

    /** 加密后数据的前缀，用于区分历史明文格式。 */
    const val PREFIX = "v1:"

    fun isEncrypted(stored: String): Boolean = stored.startsWith(PREFIX)

    fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val iv = cipher.iv
        val body = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return PREFIX +
            Base64.encodeToString(iv, Base64.NO_WRAP) + ":" +
            Base64.encodeToString(body, Base64.NO_WRAP)
    }

    /** 解密失败（密钥失效 / 数据损坏）返回 null，由调用方决定降级策略。 */
    fun decrypt(stored: String): String? = try {
        if (!isEncrypted(stored)) {
            null
        } else {
            val parts = stored.removePrefix(PREFIX).split(":")
            if (parts.size != 2) null
            else {
                val iv = Base64.decode(parts[0], Base64.NO_WRAP)
                if (iv.size != IV_BYTES) null
                else {
                    val body = Base64.decode(parts[1], Base64.NO_WRAP)
                    val cipher = Cipher.getInstance(TRANSFORMATION)
                    cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(TAG_BITS, iv))
                    String(cipher.doFinal(body), Charsets.UTF_8)
                }
            }
        }
    } catch (_: Exception) {
        null
    }

    private fun secretKey(): SecretKey {
        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (ks.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }
}

/**
 * 多账号 session 存储。
 * 每个账号保存各自的 X-Client-Session token，可随时切换当前账号。
 * token 以 Keystore 加密后的密文落盘，且不参与系统备份（见 res/xml/backup_rules.xml）。
 */
object Session {
    private const val PREFS = "moonbazaar_session"
    private const val KEY_ACCOUNTS = "accounts_json"
    private const val KEY_CURRENT = "current_key"
    private const val LEGACY_TOKEN = "session_token"

    @Volatile
    private var prefs: SharedPreferences? = null

    private val _accounts = MutableStateFlow<List<Account>>(emptyList())
    val accounts: StateFlow<List<Account>> = _accounts.asStateFlow()

    private val _currentKey = MutableStateFlow<String?>(null)
    val currentKey: StateFlow<String?> = _currentKey.asStateFlow()

    private val _token = MutableStateFlow<String?>(null)
    val token: StateFlow<String?> = _token.asStateFlow()
    val tokenValue: String? get() = _token.value

    val currentAccount: Account?
        get() = _accounts.value.firstOrNull { it.key == _currentKey.value }

    val isValid: Boolean get() = !tokenValue.isNullOrBlank()

    // Initialise once at Application.onCreate.
    fun init(context: Context) {
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = p
        val list = readAccounts(p).toMutableList()

        // 迁移旧版本的单账号 token
        val legacy = p.getString(LEGACY_TOKEN, null)
        if (!legacy.isNullOrBlank() && list.none { it.token == legacy }) {
            list.add(Account(token = legacy))
        }

        var current = p.getString(KEY_CURRENT, null)
        if (current == null || list.none { it.key == current }) {
            current = list.firstOrNull()?.key
        }
        // persist 会以加密格式重写，顺带完成历史明文数据的迁移
        persist(p, list, current)
        p.edit().remove(LEGACY_TOKEN).apply()

        _accounts.value = list
        _currentKey.value = current
        _token.value = list.firstOrNull { it.key == current }?.token
    }

    /** 新登录成功：加入账号并设为当前。 */
    fun addAndSelect(token: String) {
        val t = token.trim()
        if (t.isBlank()) return
        val p = prefs ?: return
        val list = _accounts.value.toMutableList()
        val idx = list.indexOfFirst { it.token == t }
        val acct = if (idx >= 0) list[idx] else Account(token = t)
        if (idx < 0) list.add(acct)
        persist(p, list, acct.key)
        _accounts.value = list
        _currentKey.value = acct.key
        _token.value = acct.token
    }

    /**
     * 登录后拿到真实 uid/username，回填**发起该请求的那个账号**（按 token 定位，按 uid 去重）。
     *
     * 之所以按 token 而不是按“当前账号”回填：请求发出后用户可能已切换账号，
     * 若仍写到当前账号上就会把 A 的 UID 写到 B 名下。
     * 仅当被回填的账号正好是当前账号时，才同步 currentKey / token。
     */
    fun identify(token: String, uid: String, username: String) {
        val t = token.trim()
        val u = uid.trim()
        if (t.isBlank() || u.isBlank()) return
        val p = prefs ?: return

        val list = _accounts.value.toMutableList()
        val idx = list.indexOfFirst { it.token == t }
        if (idx < 0) return   // 该账号已被移除（例如中途登出），不再回填

        val curBefore = _currentKey.value
        val targetKeyBefore = list[idx].key
        val dupIdx = list.indexOfFirst { it.uid == u && it.token != t }
        val dupKeyBefore = if (dupIdx >= 0) list[dupIdx].key else null

        val newKey: String
        if (dupIdx >= 0) {
            // 同一账号重复登录：合并到已有条目，使用最新的 token
            val merged = list[dupIdx].copy(
                token = t,
                username = username.ifBlank { list[dupIdx].username }
            )
            list[dupIdx] = merged
            list.removeAt(idx)
            newKey = merged.key
        } else {
            list[idx] = list[idx].copy(
                uid = u,
                username = username.ifBlank { list[idx].username }
            )
            newKey = list[idx].key
        }

        val curAfter = when {
            curBefore == targetKeyBefore -> newKey
            dupKeyBefore != null && curBefore == dupKeyBefore -> newKey
            else -> curBefore
        }
        persist(p, list, curAfter)
        _accounts.value = list
        _currentKey.value = curAfter
        _token.value = list.firstOrNull { it.key == curAfter }?.token
    }

    /** 切换当前账号。 */
    fun selectByKey(key: String) {
        if (key == _currentKey.value) return
        val acct = _accounts.value.firstOrNull { it.key == key } ?: return
        prefs?.let { persist(it, _accounts.value, acct.key) }
        _currentKey.value = acct.key
        _token.value = acct.token
    }

    /** 移除某个账号；若移除的是当前账号，则自动切到剩余的第一个。 */
    fun removeByKey(key: String) {
        val p = prefs ?: return
        val list = _accounts.value.toMutableList()
        list.removeAll { it.key == key }
        var cur = _currentKey.value
        if (cur == key || list.none { it.key == cur }) {
            cur = list.firstOrNull()?.key
        }
        persist(p, list, cur)
        _accounts.value = list
        _currentKey.value = cur
        _token.value = list.firstOrNull { it.key == cur }?.token
    }

    /** 清空所有账号（完全登出）。 */
    fun clear() {
        val p = prefs ?: return
        persist(p, emptyList(), null)
        _accounts.value = emptyList()
        _currentKey.value = null
        _token.value = null
    }

    /** 当前账号在官网 WebView 里拿到的 zlex_token（没有则 null）。 */
    val currentSiteToken: String?
        get() = currentAccount?.siteToken?.takeIf { it.isNotBlank() }

    /** 当前账号在官网的 PHPSESSID（没有则 null）。 */
    val currentSiteSession: String?
        get() = currentAccount?.siteSession?.takeIf { it.isNotBlank() }

    /** 按 session token 取账号（请求发起时的快照，避免中途切换账号读错人）。 */
    fun accountFor(sessionToken: String?): Account? {
        val t = sessionToken ?: return currentAccount
        return _accounts.value.firstOrNull { it.token == t }
    }

    /**
     * 保存某个账号在官网 moonbazaar.xyz 拿到的 `zlex_token` 和 `PHPSESSID`。
     *
     * 与 Worker session token 并存保存，不互相覆盖——两者是不同体系的凭证。
     * [sessionToken] 为空时表示当前账号；传入发起请求时的 token 可避免切换账号后写错人。
     */
    fun updateSiteCredentials(
        siteToken: String,
        siteSession: String = "",
        sessionToken: String? = null
    ) {
        val v = siteToken.trim()
        if (v.isBlank()) return
        val s = siteSession.trim()
        updateAccount(sessionToken) {
            it.copy(
                siteToken = v,
                siteSession = s.ifBlank { it.siteSession }
            )
        }
    }

    /** 兼容旧调用：只更新 zlex_token。 */
    fun updateSiteToken(siteToken: String, sessionToken: String? = null) {
        updateSiteCredentials(siteToken, "", sessionToken)
    }

    /** 保存某个账号的 `/home/mb_token` 结果（access / refresh / 过期时间）。 */
    fun updateMbToken(
        accessToken: String,
        refreshToken: String,
        expiresAt: Long,
        sessionToken: String? = null
    ) {
        if (accessToken.isBlank()) return
        updateAccount(sessionToken) {
            it.copy(
                mbAccessToken = accessToken,
                mbRefreshToken = refreshToken.ifBlank { it.mbRefreshToken },
                mbExpiresAt = expiresAt
            )
        }
    }
    /** 按 session token 定位账号并改写它；当前账号与 token 流保持不变。 */
    private fun updateAccount(sessionToken: String?, transform: (Account) -> Account) {
        val p = prefs ?: return
        val t = sessionToken ?: _token.value ?: return
        val list = _accounts.value.toMutableList()
        val idx = list.indexOfFirst { it.token == t }
        if (idx < 0) return
        val untouched = list[idx]
        val updated = transform(untouched)
        if (updated == untouched) return
        list[idx] = updated
        persist(p, list, _currentKey.value)
        _accounts.value = list
        // 只记录「存了哪一类凭证」，不打印 token 本体
        Log.i(
            "Account",
            "saved ${updated.key} site=${updated.siteToken != untouched.siteToken} " +
                "sess=${updated.siteSession != untouched.siteSession} " +
                "mb=${updated.mbAccessToken != untouched.mbAccessToken}"
        )
    }

    private fun readAccounts(p: SharedPreferences): List<Account> {
        val raw = p.getString(KEY_ACCOUNTS, null) ?: return emptyList()
        // 新格式：Keystore 加密；旧格式：明文 JSON（首次读取时自动迁移）。
        // 密文无法解开（密钥失效 / 数据损坏）时返回空列表，强制重新登录，避免误读脏数据。
        val plain = if (SessionCrypto.isEncrypted(raw)) SessionCrypto.decrypt(raw) ?: return emptyList()
        else raw
        return parseAccounts(plain)
    }

    private fun parseAccounts(raw: String): List<Account> = decodeAccounts(raw)

    internal fun decodeAccounts(raw: String): List<Account> {
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val token = o.optString("token")
                if (token.isBlank()) null
                else Account(
                    uid = o.optString("uid"),
                    username = o.optString("username"),
                    token = token,
                    siteToken = o.optString("site_token"),
                    siteSession = o.optString("site_session"),
                    mbAccessToken = o.optString("mb_access_token"),
                    mbRefreshToken = o.optString("mb_refresh_token"),
                    mbExpiresAt = o.optLong("mb_expires_at", 0L)
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** 账号列表 → 明文 JSON（三套 token 都写进去，由调用方负责加密）。 */
    internal fun encodeAccounts(list: List<Account>): String {
        val arr = JSONArray()
        list.forEach { a ->
            arr.put(
                JSONObject().apply {
                    put("uid", a.uid)
                    put("username", a.username)
                    put("token", a.token)
                    put("site_token", a.siteToken)
                    put("site_session", a.siteSession)
                    put("mb_access_token", a.mbAccessToken)
                    put("mb_refresh_token", a.mbRefreshToken)
                    put("mb_expires_at", a.mbExpiresAt)
                }
            )
        }
        return arr.toString()
    }

    private fun persist(p: SharedPreferences, list: List<Account>, current: String?) {
        val editor = p.edit().putString(KEY_CURRENT, current)
        // 加密失败时宁可不落盘，也不把明文 token 写进 SharedPreferences。
        val encrypted = try {
            SessionCrypto.encrypt(encodeAccounts(list))
        } catch (_: Exception) {
            null
        }
        if (encrypted != null) editor.putString(KEY_ACCOUNTS, encrypted)
        editor.apply()
    }
}
