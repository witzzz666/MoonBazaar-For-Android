package top.witzzz.moonbazaar.data

/**
 * 当前账号的 MoonBazaar 官方 token（`/home/mb_token` 的返回）。
 *
 * 只保存在内存里：App 每次启动都会重新申请一次，所以不需要明文落盘
 * （README 也要求 refresh_token 不能明文存储）。
 *
 * 后续需要「直连 MoonBazaar」「本地续期」或「把 token 交给原生层」时，
 * 直接取 [accessToken] / [refreshToken] 即可，不必再关心 session_token。
 */
object MbTokenStore {

    @Volatile
    private var current: MoonRepository.MbToken? = null

    /** 账号切换 / 登出时清理，避免把上一个账号的凭证留给下一个账号。 */
    fun update(token: MoonRepository.MbToken?) {
        current = token
    }

    fun clear() {
        current = null
    }

    fun token(): MoonRepository.MbToken? = current

    fun accessToken(): String? = current?.accessToken?.takeIf { it.isNotBlank() }

    fun refreshToken(): String? = current?.refreshToken?.takeIf { it.isNotBlank() }

    fun isReady(): Boolean = !accessToken().isNullOrBlank()
}
