package top.witzzz.moonbazaar.data

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
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

/** 一个已登录的账号（保存它的 Worker session token）。 */
data class Account(
    val uid: String = "",
    val username: String = "",
    val token: String = ""
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

    private fun readAccounts(p: SharedPreferences): List<Account> {
        val raw = p.getString(KEY_ACCOUNTS, null) ?: return emptyList()
        // 新格式：Keystore 加密；旧格式：明文 JSON（首次读取时自动迁移）。
        // 密文无法解开（密钥失效 / 数据损坏）时返回空列表，强制重新登录，避免误读脏数据。
        val plain = if (SessionCrypto.isEncrypted(raw)) SessionCrypto.decrypt(raw) ?: return emptyList()
        else raw
        return parseAccounts(plain)
    }

    private fun parseAccounts(raw: String): List<Account> {
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val token = o.optString("token")
                if (token.isBlank()) null
                else Account(
                    uid = o.optString("uid"),
                    username = o.optString("username"),
                    token = token
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun persist(p: SharedPreferences, list: List<Account>, current: String?) {
        val arr = JSONArray()
        list.forEach { a ->
            arr.put(
                JSONObject().apply {
                    put("uid", a.uid)
                    put("username", a.username)
                    put("token", a.token)
                }
            )
        }
        val editor = p.edit().putString(KEY_CURRENT, current)
        // 加密失败时宁可不落盘，也不把明文 token 写进 SharedPreferences。
        val encrypted = try {
            SessionCrypto.encrypt(arr.toString())
        } catch (_: Exception) {
            null
        }
        if (encrypted != null) editor.putString(KEY_ACCOUNTS, encrypted)
        editor.apply()
    }
}
