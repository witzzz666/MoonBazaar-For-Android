package top.witzzz.moonbazaar.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import top.witzzz.moonbazaar.data.Account
import top.witzzz.moonbazaar.data.MbTokenStore
import top.witzzz.moonbazaar.data.MoonRepository
import top.witzzz.moonbazaar.data.Session
import top.witzzz.moonbazaar.data.SiteRefresh

// 登录/会话
sealed class AuthUi {
    data object Unknown : AuthUi()
    data object LoggedOut : AuthUi()
    data object LoggedIn : AuthUi()
}

// 账号信息 (GET /proxy/account/status)
data class AccountInfo(
    val uid: String = "",
    val username: String = "",
    val emailMasked: String = "",
    val emailVerified: Boolean = true,
    val status: String = "",
    val registeredAt: String = "",
    val isBanned: Boolean = false,
    val banReason: String = ""
)

// 首页 hero
data class HomeUi(
    val loading: Boolean = false,
    val gc: String = "—",
    val account: AccountInfo = AccountInfo()
)

data class BanState(val banned: Boolean = false, val reason: String = "")

// 交易流水一条（金额保留原始小数，不做取整）
data class TransactionRecord(
    val createdAt: String = "",
    val typeName: String = "",
    val description: String = "",
    val amountText: String = "0",
    val isGain: Boolean = true
) {
    /** 展示用：正数补 "+"，负数原样（本身带 "-"）。 */
    val displayAmount: String
        get() = if (isGain && !amountText.startsWith("+")) "+$amountText" else amountText
}

// Withdraw 商品卡
data class WithdrawProduct(
    val id: Long,
    val methodZh: String,
    val labelZh: String,
    val categoryZh: String,
    val descZh: String,
    val unitZh: String,
    val deliveryFieldZh: String,
    val pricingType: String,          // fixed | rate
    val fixedCostGc: String,          // 空串则按 rate
    val gcPerUnit: String,
    val minimumAmount: String,
    val maximumAmount: String,
    val apiOk: Boolean,
    val rawText: String
) {
    val bodyText: String get() = rawText
}

// 问卷一条
data class SurveyItem(
    val provider: String = "",      // 展示用：提供商全名（provider_full，如 "CPX Research"）
    val providerKey: String = "",   // 原始 provider 字段（如 "cpx"），用于 provider_status 过滤
    val surveyId: String = "",
    val reward: String = "0",   // 单位 GC
    val loi: String = "",       // 问卷时长
    val url: String = ""
)

// 主要问卷页面状态
data class SurveysUiState(
    val loading: Boolean = false,
    val message: String = "",
    val countryCode: String = "",
    val qualityScore: Int = 0,
    val surveys: List<SurveyItem> = emptyList()
)

/**
 * 启动时向 Worker 申请的 MoonBazaar 官方 token（GET /home/mb_token）。
 *
 * 这里只记录「拿没拿到、什么时候过期」这类可展示信息，
 * token 本体放在 [top.witzzz.moonbazaar.data.MbTokenStore] 内存里，不进 UI 状态、不落盘。
 */
data class MbTokenUi(
    val loading: Boolean = false,
    val ok: Boolean = false,
    val uid: String = "",
    val expiresAt: Long = 0L,
    val expired: Boolean = false,
    val hasRefresh: Boolean = false,
    val scopeCount: Int = 0,
    val message: String = ""
)

class MainViewModel : ViewModel() {

    private val _auth = MutableStateFlow<AuthUi>(AuthUi.Unknown)
    val auth: StateFlow<AuthUi> = _auth.asStateFlow()
    private val _authNote = MutableStateFlow("")
    val authNote: StateFlow<String> = _authNote.asStateFlow()

    private val _home = MutableStateFlow(HomeUi())
    val home: StateFlow<HomeUi> = _home.asStateFlow()

    private val _ban = MutableStateFlow<BanState>(BanState())
    val ban: StateFlow<BanState> = _ban.asStateFlow()

    // ---- 多账号 ----
    val accounts: StateFlow<List<Account>> = Session.accounts
    val currentKey: StateFlow<String?> = Session.currentKey

    // ==================================================================
    // 账号作用域
    //
    // 切换账号 / 登出时：取消上一个账号的在途请求，并递增世代号。
    // 每个请求在发起时快照「世代号 + 令牌」，网络返回后先校验世代号，
    // 只有仍属于当前账号的响应才允许写入界面状态。
    //
    // 为什么不能只靠取消：OkHttp 的 execute() 是阻塞调用，不响应协程取消，
    // 取消后协程体仍会继续执行到下一个挂起点，所以必须再校验一次世代号。
    // ==================================================================
    private var accountGeneration = 0L
    private val accountJobs = mutableListOf<Job>()

    /** 当前作用域对应的账号令牌，用于发现「账号在别处被改掉」的情况。 */
    private var scopeToken: String? = null

    /** 使当前账号作用域失效：取消在途请求，并让它们的响应无法再写入界面。 */
    private fun invalidateAccountScope() {
        accountJobs.forEach { it.cancel() }
        accountJobs.clear()
        accountGeneration++
        scopeToken = currentToken()
    }

    private fun isCurrentScope(generation: Long): Boolean = generation == accountGeneration

    /** 当前账号的令牌；未登录（空 / 全空白）时返回 null。 */
    private fun currentToken(): String? = Session.tokenValue?.takeIf { it.isNotBlank() }

    /**
     * 每个账号相关请求的入口：返回该请求应绑定的令牌（未登录返回 null）。
     *
     * 若发现当前令牌与作用域记录的令牌不一致，说明账号在**别处**被改过
     * （例如已有账号上「添加账号」成功后 `Session.addAndSelect` 直接切了当前账号），
     * 此时先作废旧作用域，保证旧账号的在途响应不会写进新账号的界面。
     */
    private fun beginAccountRequest(): String? {
        val token = currentToken()
        if (token != scopeToken) invalidateAccountScope()
        return token
    }

    /**
     * 账号已变更后的统一收尾：作废旧作用域、清空界面，并按当前是否仍有账号决定重新拉取还是置为登出。
     * @param note 切换过程中的提示文案
     */
    private fun applyAccountChange(note: String) {
        invalidateAccountScope()
        clearAccountUiState(note)
        if (Session.currentKey.value != null) {
            _auth.value = AuthUi.Unknown
            refreshAuthState()
            reloadAll()
        } else {
            _auth.value = AuthUi.LoggedOut
        }
    }

    /** 在账号作用域内发起请求，回调参数为发起时的世代号。 */
    private fun launchInAccountScope(block: suspend (Long) -> Unit) {
        accountJobs.removeAll { it.isCompleted }
        val generation = accountGeneration
        accountJobs += viewModelScope.launch(Dispatchers.IO) { block(generation) }
    }

    // ---- transactions (paginated) ----
    data class TxUi(
        val loading: Boolean = false,
        val records: List<TransactionRecord> = emptyList(),
        val total: Int = 0,
        val limit: Int = 25,
        val hasMore: Boolean = false,
        val message: String = ""
    )

    private val _tx = MutableStateFlow(TxUi())
    val tx: StateFlow<TxUi> = _tx.asStateFlow()

    // ---- redeem ----
    private val _redeem = MutableStateFlow(RedemptionUiState())
    val redeem: StateFlow<RedemptionUiState> = _redeem.asStateFlow()

    // ---- referral ----
    private val _referral = MutableStateFlow(ReferralUiState())
    val referral: StateFlow<ReferralUiState> = _referral.asStateFlow()

    // ---- withdraw products ----
    private val _products = MutableStateFlow<List<WithdrawProduct>>(emptyList())
    val products: StateFlow<List<WithdrawProduct>> = _products.asStateFlow()

    private val _withdrawMsg = MutableStateFlow<String>("")
    val withdrawMsg: StateFlow<String> = _withdrawMsg.asStateFlow()

    // ---- surveys ----
    private val _surveys = MutableStateFlow(SurveysUiState())
    val surveys: StateFlow<SurveysUiState> = _surveys.asStateFlow()

    // ---- MoonBazaar token（每次启动申请一次） ----
    private val _mbToken = MutableStateFlow(MbTokenUi())
    val mbToken: StateFlow<MbTokenUi> = _mbToken.asStateFlow()

    init {
        refreshAuthState()
        loadHome()
        loadSurveys()
        loadTransactions(0)
        loadReferral()
        loadProducts()
        fetchMbToken()
    }

    // ===== 会话 =====
    fun refreshAuthState() {
        val token = beginAccountRequest()
        _authNote.value = "检查会话…"
        launchInAccountScope { generation ->
            val (s, err) = MoonRepository.status(token)
            if (!isCurrentScope(generation)) return@launchInAccountScope
            when {
                s == null -> {
                    _auth.value = AuthUi.LoggedOut
                    _authNote.value = err.ifEmpty { "无法获取会话" }
                }
                !s.sessionValid || !s.hasTokens -> {
                    _auth.value = AuthUi.LoggedOut
                    _authNote.value = if (s.sessionValid) "已有会话，尚无用户令牌" else "会话无效，请登录"
                }
                else -> {
                    _auth.value = AuthUi.LoggedIn
                    _authNote.value = "已登录"
                }
            }
        }
    }

    // ===== MoonBazaar token =====

    /**
     * 启动时向 Worker 申请 MoonBazaar 官方 token（`GET /home/mb_token`）。
     *
     * - 每次 App 启动（ViewModel 创建）都会调用一次；登录 / 切换账号后 [reloadAll] 也会再调用，
     *   保证内存里的 token 始终属于当前账号。
     * - 只放内存（[MbTokenStore]），不写 SharedPreferences、不打印明文。
     * - 失败（会话过期 / 尚未完成 OAuth）只记录提示，不打断其它页面的加载。
     */
    fun fetchMbToken() {
        val token = beginAccountRequest()
        if (token == null) {
            _mbToken.value = MbTokenUi()
            MbTokenStore.clear()
            return
        }
        _mbToken.value = _mbToken.value.copy(loading = true)
        launchInAccountScope { generation ->
            val (t, err) = MoonRepository.mbToken(token)
            if (!isCurrentScope(generation)) return@launchInAccountScope
            if (t == null) {
                MbTokenStore.clear()
                _mbToken.value = MbTokenUi(
                    loading = false,
                    message = err.ifEmpty { "获取 MoonBazaar Token 失败" }
                )
                Log.w("MbToken", "fetch failed: ${err.take(200)}")
                return@launchInAccountScope
            }
            MbTokenStore.update(t)
            // 与 session token / 官网 zlex_token 一起按账号存档，重启后仍能对得上
            Session.updateMbToken(
                accessToken = t.accessToken,
                refreshToken = t.refreshToken,
                expiresAt = t.expiresAt,
                sessionToken = token
            )
            _mbToken.value = MbTokenUi(
                loading = false,
                ok = true,
                uid = t.uid,
                expiresAt = t.expiresAt,
                expired = t.expired,
                hasRefresh = t.refreshToken.isNotBlank(),
                scopeCount = t.scopeCount,
                message = if (t.expired) "Token 已过期" else "已获取"
            )
            // 只记录元信息，绝不打印 token 本身
            Log.i("MbToken", "ok uid=${t.uid} scope=${t.scopeCount} " +
                "expired=${t.expired} refresh=${t.refreshToken.isNotBlank()}")
        }
    }

    /** 清空所有与账号相关的界面数据（切换账号 / 登出时必须调用，否则会残留上一个账号的信息）。 */
    private fun clearAccountUiState(note: String) {        _home.value = HomeUi()
        _ban.value = BanState()
        _tx.value = TxUi()
        _redeem.value = RedemptionUiState()
        _referral.value = ReferralUiState()
        _products.value = emptyList()
        _withdrawMsg.value = ""
        _surveys.value = SurveysUiState()
        _mbToken.value = MbTokenUi()
        MbTokenStore.clear()
        _authNote.value = note
    }

    /** 重新拉取当前账号的全部数据。 */
    fun reloadAll() {
        loadHome()
        loadSurveys()
        loadTransactions(0)
        loadReferral()
        loadProducts()
        fetchMbToken()
    }

    /** 退出当前账号：吊销服务端 session 并移除本地该账号；若还有其它账号则自动切过去。 */
    fun logout() {
        val key = Session.currentKey.value
        val token = Session.tokenValue
        // 先让旧账号的在途请求全部失效，再发起吊销请求
        invalidateAccountScope()
        launchInAccountScope { generation ->
            MoonRepository.logout(token)     // 用该账号自己的令牌尽力吊销 Worker session
            if (key != null) Session.removeByKey(key) else Session.clear()
            // 期间用户可能已经切到别的账号：本地已移除，但界面交给新的账号作用域负责
            if (!isCurrentScope(generation)) return@launchInAccountScope
            applyAccountChange("未登录")
        }
    }

    /** 切换到指定账号（uid key）。 */
    fun switchAccount(key: String) {
        if (key == Session.currentKey.value) return
        Session.selectByKey(key)
        // 切换后立刻作废旧账号的在途请求，避免旧响应覆盖新账号界面
        applyAccountChange("正在切换账号…")
    }

    /** 移除指定账号；若移除的是当前账号会自动切换到其它账号。 */
    fun removeAccount(key: String) {
        val wasCurrent = key == Session.currentKey.value
        Session.removeByKey(key)
        if (!wasCurrent) return
        applyAccountChange("已移除该账号")
    }

    // ===== 首页 =====
    fun loadHome() {
        val token = beginAccountRequest() ?: return
        _home.value = _home.value.copy(loading = true)
        launchInAccountScope { generation ->
            val (acct, _) = MoonRepository.accountStatus(token)
            val (bal, _) = MoonRepository.accountBalance(token)
            if (!isCurrentScope(generation)) return@launchInAccountScope

            val avail = findVal(bal, "available", "available_gascoin", "balance", "gascoin")
            val acctData = acct?.optJSONObject("data") ?: acct
            val banned = acctData?.optBoolean("is_banned", false) == true
            val acctInfo = AccountInfo(
                uid = acctData?.opt("uid")?.toString() ?: "",
                username = acctData?.optString("username") ?: "",
                emailMasked = acctData?.optString("email_masked") ?: "",
                emailVerified = acctData?.optBoolean("email_verified", true) ?: true,
                status = acctData?.optString("status") ?: "",
                registeredAt = acctData?.optString("registered_at") ?: "",
                isBanned = banned,
                banReason = acctData?.optString("ban_reason")?.ifEmpty { "" }
                    ?: (if (banned) "账号已被封禁" else "")
            )
            _ban.value = BanState(banned = banned, reason = acctInfo.banReason)
            _home.value = HomeUi(loading = false, gc = MoonRepository.gcInteger(avail), account = acctInfo)
            // 回填账号信息（uid / username）到**发起该请求的账号**，而不是当前账号
            if (acctInfo.uid.isNotBlank()) {
                Session.identify(token, acctInfo.uid, acctInfo.username)
            }
        }
    }

    // ===== 交易流水（动态 limit：先探测 total，再按总数拉取） =====
    // 单次请求条数安全上限：total 超过它时回退为多页加载，避免被上游拒绝或卡顿。
    private val txCap = 500
    private val refCap = 500

    /**
     * @param offset 偏移。传 0 表示「刷新」：先用 limit=1 探测请求拿到 total，
     *               再用 limit=min(total, txCap) 拉取记录。
     */
    fun loadTransactions(offset: Int = 0) {
        val token = beginAccountRequest() ?: return
        if (_ban.value.banned) {
            _tx.value = TxUi(message = _ban.value.reason.ifEmpty { "账号已封禁，无法查看" })
            return
        }
        val cur = _tx.value
        if (offset > 0 && (cur.loading || !cur.hasMore)) return
        _tx.value = cur.copy(
            loading = true,
            message = if (offset == 0) "正在获取记录总数…" else cur.message
        )
        launchInAccountScope { generation ->
            // ① 探测请求：拿总数
            var total = cur.total
            if (offset == 0) {
                val (probe, probeErr) = MoonRepository.transactions(limit = 1, offset = 0, token = token)
                if (!isCurrentScope(generation)) return@launchInAccountScope
                val pd = probe?.optJSONObject("data") ?: probe
                if (pd == null) {
                    _tx.value = TxUi(
                        loading = false,
                        message = probeErr.ifEmpty { "无法获取交易记录" }
                    )
                    return@launchInAccountScope
                }
                total = pd.optInt("total", 0)
                if (total <= 0) {
                    _tx.value = TxUi(loading = false, total = 0, hasMore = false,
                        message = "暂无交易记录")
                    return@launchInAccountScope
                }
            }

            // ② 正式请求：limit = min(total, 上限)；翻页时沿用同一页大小
            val pageSize = if (offset == 0) minOf(total, txCap) else cur.limit
            val (data, err) = MoonRepository.transactions(limit = pageSize, offset = offset, token = token)
            if (!isCurrentScope(generation)) return@launchInAccountScope
            val d = data?.optJSONObject("data") ?: data
            val records = d?.optJSONArray("records")
            if (records == null) {
                _tx.value = cur.copy(loading = false,
                    message = err.ifEmpty { "暂无交易数据" })
                return@launchInAccountScope
            }
            val list = (0 until records.length()).map { i ->
                val j = records.optJSONObject(i) ?: JSONObject()
                val amountRaw = j.opt("amount_gc")
                val amountText = numberText(amountRaw).ifEmpty { "0" }
                val gain = (amountRaw as? Number)?.toDouble()?.let { it >= 0 } ?: true
                TransactionRecord(
                    createdAt = j.optString("created_at"),
                    typeName = j.optString("type_name"),
                    description = j.optString("description"),
                    amountText = amountText,
                    isGain = gain
                )
            }
            val combined = if (offset == 0) list else cur.records + list
            _tx.value = TxUi(
                loading = false,
                records = combined,
                total = total,
                limit = pageSize,
                hasMore = combined.size < total,
                message = "共 $total 条记录"
            )
        }
    }

    // ===== redeem =====
    fun redeem(code: String) {
        val b = _ban.value
        if (b.banned) {
            _redeem.value = RedemptionUiState(done = true, okHttp = false,
                error = b.reason.ifEmpty { "账号已封禁，无法兑换" })
            return
        }
        if (code.isBlank()) return
        val token = beginAccountRequest() ?: return
        _redeem.value = RedemptionUiState(submitting = true)
        launchInAccountScope { generation ->
            val r = MoonRepository.redeemCode(code, token)
            if (!isCurrentScope(generation)) return@launchInAccountScope
            val confirmation = r.json?.optJSONObject("data")?.optString("confirmation_url")
                ?: r.json?.optString("confirmation_url") ?: ""
            _redeem.value = if (r.okHttp) {
                RedemptionUiState(done = true, okHttp = true, confirmationUrl = confirmation,
                    body = r.body,
                    note = if (confirmation.isBlank()) "受理成功" else "已受理，请打开官方确认页完成兑换")
            } else {
                RedemptionUiState(done = true, okHttp = false,
                    error = r.errorMsg.ifEmpty { "HTTP ${r.http}" }, body = r.body)
            }
        }
    }

    fun resetRedeem() {
        _redeem.value = RedemptionUiState()
    }

    // ===== referral =====
    fun loadReferral() {
        val token = beginAccountRequest() ?: return
        if (_ban.value.banned) {
            _referral.value = ReferralUiState(
                message = _ban.value.reason.ifEmpty { "账号已封禁，无法查看" })
            return
        }
        _referral.value = _referral.value.copy(loading = true)
        launchInAccountScope { generation ->
            // 邀请链接（uid_link 必有；custom_link 可能未设置）
            val (linkData, linkErr) = MoonRepository.referralLink(token)
            if (!isCurrentScope(generation)) return@launchInAccountScope
            val ld = linkData?.optJSONObject("data") ?: linkData
            val uidLink = ld?.optString("uid_link").orEmpty()
            val customCode = ld?.optString("custom_code").orEmpty()
            val customLink = ld?.optString("custom_link").orEmpty()

            // 邀请统计 + 明细（先探测总数再按总数拉取）
            val invites = mutableListOf<InviteeRow>()
            var totalInvites = 0
            var commission = ""
            var listErrMsg = ""

            val (probe, probeErr) = MoonRepository.referrals(limit = 1, offset = 0, token = token)
            if (!isCurrentScope(generation)) return@launchInAccountScope
            val pd = probe?.optJSONObject("data") ?: probe
            if (pd == null) {
                listErrMsg = probeErr.ifEmpty { "无法获取邀请数据" }
            } else {
                totalInvites = pd.optInt("total_invites", 0)
                commission = numberText(pd.opt("commission_earned_gc"))
                if (totalInvites > 0) {
                    val pageSize = minOf(totalInvites, refCap)
                    val (listData, listErr) = MoonRepository.referrals(limit = pageSize, offset = 0, token = token)
                    if (!isCurrentScope(generation)) return@launchInAccountScope
                    val d = listData?.optJSONObject("data") ?: listData
                    val arr = d?.optJSONArray("invites")
                    if (arr == null) {
                        listErrMsg = listErr.ifEmpty { "邀请明细为空" }
                    } else {
                        for (i in 0 until arr.length()) {
                            val j = arr.optJSONObject(i) ?: continue
                            invites.add(
                                InviteeRow(
                                    id = j.optLong("id"),
                                    username = j.optString("username"),
                                    createdAt = j.optString("created_at"),
                                    isBanned = j.optBoolean("is_banned", false),
                                    totalEarnedGc = numberText(j.opt("total_earned_gc")),
                                    milestoneAwarded = j.optBoolean("milestone_awarded", false)
                                )
                            )
                        }
                    }
                }
            }
            if (listErrMsg.isBlank() && linkErr.isNotBlank()) listErrMsg = linkErr

            _referral.value = ReferralUiState(
                loading = false,
                message = listErrMsg,
                totalInvites = totalInvites,
                commissionEarnedGc = commission.ifEmpty { "0" },
                invites = invites,
                uidLink = uidLink,
                customCode = customCode,
                customLink = customLink
            )
        }
    }

    // ===== 主要问卷 =====
    fun loadSurveys() {
        val token = beginAccountRequest() ?: return
        if (_ban.value.banned) {
            _surveys.value = SurveysUiState(
                message = _ban.value.reason.ifEmpty { "账号已封禁，无法查看问卷" })
            return
        }
        _surveys.value = _surveys.value.copy(loading = true, message = "")
        launchInAccountScope { generation ->
            // 优先直连官网 get_surveys.php（zlex_token + PHPSESSID，与网页端同源数据）；
            // 官网凭证缺失/失效时回退到 Worker 代理，保证功能可用。
            val siteToken = Session.accountFor(token)?.siteToken.orEmpty()
            var used = "worker"
            var data: JSONObject? = null
            var err = ""
            if (siteToken.isNotBlank()) {
                used = "site"
                // 官网凭证每小时轮换：陈旧就先访问 /home 换一份，再请求。
                SiteRefresh.refreshIfStale()
                var acct = Session.accountFor(token)
                var r = MoonRepository.siteSurveys(
                    acct?.siteToken.orEmpty(), acct?.siteSession.orEmpty())
                if (r.first == null) {
                    Log.w("SiteSurveys", "retry after refresh: ${r.second}")
                    SiteRefresh.refresh()
                    acct = Session.accountFor(token)
                    r = MoonRepository.siteSurveys(
                        acct?.siteToken.orEmpty(), acct?.siteSession.orEmpty())
                }
                data = r.first
                err = r.second
            } else {
                val r = MoonRepository.surveys(token)
                data = r.first
                err = r.second
            }
            if (data == null && siteToken.isNotBlank()) {
                Log.w("SiteSurveys", "fallback to worker: $err")
                used = "worker"
                val fb = MoonRepository.surveys(token)
                data = fb.first
                err = fb.second
            }
            if (!isCurrentScope(generation)) return@launchInAccountScope
            // 两种来源：Worker 代理 = {ok, data:{surveys:[…]}}；
            // 官网直连 = {status:"success", country_code, cpx_enabled, …, data:[…]}。
            val env = data?.optJSONObject("data") ?: data
            val items = env?.optJSONArray("surveys") ?: data?.optJSONArray("data")
            if (env == null) {
                _surveys.value = SurveysUiState(loading = false,
                    message = err.ifEmpty { "无法获取问卷" })
                return@launchInAccountScope
            }
            Log.i("SiteSurveys", "loaded via $used, count=${items?.length() ?: 0}")

            val country = env.optString("country_code")
            val quality = env.optInt("quality_score", 0)

            // provider_status: { "cpx": true, … } —— 为 false 的提供商问卷不显示；
            // 官网直连则用扁平开关 cpx_enabled / prime_enabled / theoremreach_enabled …
            val statusObj = env.optJSONObject("provider_status")
            fun providerEnabled(p: String): Boolean {
                if (p.isBlank()) return true
                if (statusObj != null) {
                    val keys = statusObj.keys()
                    while (keys.hasNext()) {
                        val k = keys.next()
                        if (k.equals(p, ignoreCase = true)) return statusObj.optBoolean(k, true)
                    }
                    return true   // 未列出的提供商不隐藏
                }
                return env.optBoolean("${p}_enabled", true)
            }

            val arr = items
            val list = mutableListOf<SurveyItem>()
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val j = arr.optJSONObject(i) ?: continue
                    // 过滤用原始 key（provider_status 里的键，如 "cpx"）
                    val providerKey = j.optString("provider")
                    if (!providerEnabled(providerKey)) continue
                    // 展示用全名：provider_full（如 "CPX Research"），缺失时回退原始值
                    val providerName = j.optString("provider_full").ifBlank { providerKey }
                    // 真实字段：id / payout / link；同时对示例里的旧键名做兜底
                    val payout = if (j.has("payout")) j.opt("payout") else j.opt("reward")
                    list.add(
                        SurveyItem(
                            provider = providerName,
                            providerKey = providerKey,
                            surveyId = if (j.has("id")) j.opt("id")?.toString().orEmpty()
                            else j.opt("survey_id")?.toString().orEmpty(),
                            reward = numberText(payout).ifEmpty { "0" },
                            loi = numberText(j.opt("loi")),
                            url = j.optString("link").ifBlank { j.optString("url") }
                        )
                    )
                }
            }
            _surveys.value = SurveysUiState(
                loading = false,
                message = if (list.isEmpty()) "当前没有可做的问卷" else "共 ${list.size} 份可做问卷",
                countryCode = country,
                qualityScore = quality,
                surveys = list
            )
        }
    }

    // ===== withdraw products =====
    fun loadProducts() {
        val token = beginAccountRequest() ?: return
        if (_ban.value.banned) {
            _products.value = emptyList()
            _withdrawMsg.value = _ban.value.reason.ifEmpty { "账号已封禁，无法提现" }
            return
        }
        launchInAccountScope { generation ->
            _withdrawMsg.value = "加载中…"
            val (data, err) = MoonRepository.products(token)
            if (!isCurrentScope(generation)) return@launchInAccountScope
            val d = data?.optJSONObject("data") ?: data
            val methods = d?.optJSONArray("methods")
            if (methods == null) {
                _products.value = emptyList()
                _withdrawMsg.value = err.ifEmpty { (d?.toString(2)) ?: "无提现产品" }
                return@launchInAccountScope
            }
            val out = mutableListOf<WithdrawProduct>()
            for (m in 0 until methods.length()) {
                val method = methods.optJSONObject(m) ?: continue
                val methodZh = text(method, "name") // {en,zh}
                val apiOk = method.optBoolean("api_withdraw_supported", true)
                val productsArr = method.optJSONArray("products") ?: continue
                for (p in 0 until productsArr.length()) {
                    val j = productsArr.optJSONObject(p) ?: continue
                    val pricing = j.optString("pricing_type", "fixed")
                    out.add(WithdrawProduct(
                        id = j.optLong("id"),
                        methodZh = methodZh,
                        labelZh = text(j, "label"),
                        categoryZh = text(j, "category"),
                        descZh = text(j, "description"),
                        unitZh = text(j, "unit"),
                        deliveryFieldZh = text(j, "delivery_field"),
                        pricingType = pricing,
                        fixedCostGc = numStr(j, "fixed_cost_gc"),
                        gcPerUnit = numStr(j, "gc_per_unit"),
                        minimumAmount = numStr(j, "minimum_amount"),
                        maximumAmount = numStr(j, "maximum_amount"),
                        apiOk = apiOk,
                        rawText = j.toString(2)
                    ))
                }
            }
            _products.value = out
            _withdrawMsg.value = if (out.isEmpty()) "（暂无可用提现产品）" else "共 ${out.size} 个可提现产品"
        }
    }

    fun submitWithdrawal(product: WithdrawProduct?, amount: String, delivery: String, note: String) {
        val b = _ban.value
        if (product == null || product.id <= 0) {
            _withdrawMsg.value = "请选择一个提现产品。"
            return
        }
        if (b.banned) {
            _withdrawMsg.value = "❌ ${b.reason.ifEmpty { "账号已封禁，无法提现" }}"
            return
        }
        if (delivery.isBlank()) {
            _withdrawMsg.value = "需填写${product.deliveryFieldZh.ifEmpty { "接收信息" }}。"
            return
        }
        if (product.pricingType == "rate" && amount.isBlank()) {
            _withdrawMsg.value = "该产品按数量/金额计，请填写金额。"
            return
        }
        val token = beginAccountRequest() ?: return
        // 发送时固定价可不传 amount，rate 必传
        launchInAccountScope { generation ->
            _withdrawMsg.value = "提交中…"
            val r = MoonRepository.submitWithdrawal(
                product.id,
                if (product.pricingType == "rate") amount else null,
                delivery, note.ifBlank { null }, token)
            if (!isCurrentScope(generation)) return@launchInAccountScope
            val st = findStr(r.json, "status") ?: "已提交"
            _withdrawMsg.value = if (r.okHttp) "✅ 已提交，状态 $st\n${r.body}"
            else "❌ ${r.errorMsg}\n${r.body}"
        }
    }

    fun clearWithdrawMsg() { _withdrawMsg.value = "" }

    // 文本双语对象取中文
    private fun text(o: JSONObject?, key: String): String {
        o ?: return ""
        val v = o.opt(key)
        return when (v) {
            is String -> v
            is JSONObject -> v.optString("zh").ifEmpty { v.optString("en") }
            else -> o.optString(key)
        }
    }

    private fun numStr(o: JSONObject?, key: String): String {
        o ?: return ""
        return numberText(o.opt(key))
    }

    /** 数值转字符串：保留原始小数（整数不补 .0），不用取整。 */
    private fun numberText(v: Any?): String = when (v) {
        null -> ""
        is Double -> if (v.isNaN() || v.isInfinite()) v.toString()
        else java.math.BigDecimal.valueOf(v).stripTrailingZeros().toPlainString()
        else -> v.toString()
    }

    // ===== small JSON helpers (root or data aware) =====
    private fun findVal(o: JSONObject?, vararg keys: String): Any? {
        o ?: return null
        for (k in keys) if (o.has(k)) { val v = o.opt(k); if (v != null && v !== JSONObject.NULL) return v }
        o.optJSONObject("data")?.let { d ->
            for (k in keys) if (d.has(k)) { val v = d.opt(k); if (v != null && v !== JSONObject.NULL) return v }
        }
        return null
    }

    private fun findStr(o: JSONObject?, vararg keys: String): String? =
        findVal(o, *keys)?.toString()

    fun pickString(o: JSONObject, vararg keys: String): String = findStr(o, *keys) ?: ""
}

data class RedemptionUiState(
    val submitting: Boolean = false,
    val done: Boolean = false,
    val okHttp: Boolean = false,
    val confirmationUrl: String = "",
    val error: String = "",
    val note: String = "",
    val body: String = ""
)

// 邀请明细一行
data class InviteeRow(
    val id: Long = 0,
    val username: String = "",
    val createdAt: String = "",
    val isBanned: Boolean = false,
    val totalEarnedGc: String = "0",
    val milestoneAwarded: Boolean = false
)

data class ReferralUiState(
    val loading: Boolean = false,
    val message: String = "",
    val totalInvites: Int = 0,
    val commissionEarnedGc: String = "0",
    val invites: List<InviteeRow> = emptyList(),
    val uidLink: String = "",       // 数值邀请链接（必有）
    val customCode: String = "",    // 自定义邀请码（可能未设置）
    val customLink: String = ""     // 自定义链接（可能未设置）
)
