package top.witzzz.moonbazaar.ui

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DrawerState
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.content.ClipData
import android.content.ClipboardManager
import android.os.SystemClock
import android.widget.Toast
import androidx.activity.compose.BackHandler
import kotlinx.coroutines.launch
import top.witzzz.moonbazaar.data.Account
import top.witzzz.moonbazaar.data.AppConfig
import top.witzzz.moonbazaar.data.SiteCookie

private enum class Tab(val label: String) {
    Home("首页"), Surveys("问卷"), Tx("交易"), Redeem("兑换"), Referral("邀请"), Withdraw("提现")
}

@Composable
fun MoonApp(
    vm: MainViewModel,
    openUrl: (String) -> Unit,
    onStartLogin: () -> Unit
) {
    var tabIndex by rememberSaveable { mutableIntStateOf(0) }
    val tabs = Tab.entries
    val ban by vm.ban.collectAsState()
    val accounts by vm.accounts.collectAsState()
    val currentKey by vm.currentKey.collectAsState()
    val mb by vm.mbToken.collectAsState()
    // 不用 rememberSaveable：每次重建都从「关闭」开始，避免恢复上次展开状态而被自动打开
    val drawerState = remember { DrawerState(DrawerValue.Closed) }
    val scope = rememberCoroutineScope()
    val current = accounts.firstOrNull { it.key == currentKey }
    val ctx = LocalContext.current

    // 返回键：抽屉开着先关抽屉；否则要连按两次才退出（第一次提示）。
    var lastBackAt by remember { mutableLongStateOf(0L) }
    BackHandler {
        if (drawerState.isOpen) {
            scope.launch { drawerState.close() }
        } else {
            val now = SystemClock.elapsedRealtime()
            if (now - lastBackAt in 1..2000) {
                (ctx as? android.app.Activity)?.finish()
            } else {
                lastBackAt = now
                Toast.makeText(ctx, "再返回一次退出MoonBazaar", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // 双保险：首帧若为展开则立即关闭
    LaunchedEffect(Unit) {
        if (drawerState.isOpen) drawerState.close()
        // 启动时补抓一次官网 Cookie（zlex_token + PHPSESSID）：登录时的 WebView 已经把它们写进
        // CookieManager，这里只是从本地读回来存档，供「问卷」直连官网接口使用。
        SiteCookie.sync()
    }

    // 注意：不要在这里监听 auth 变化去 reloadAll()。
    // 所有会把状态推到「已登录」的入口（登录、切换账号、登出、移除账号、初始化）
    // 都已经显式调用过 reloadAll()，再监听一次会导致每次切号都重复发一轮请求，
    // 反而放大旧响应与新账号交错的窗口。账号切换的时序由 ViewModel 的账号作用域统一保证。
    LaunchedEffect(ban.banned) {
        if (ban.banned) vm.loadHome()
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        // 允许从屏幕左缘滑动拉开 / 滑动关闭
        gesturesEnabled = true,
        drawerContent = {
            ModalDrawerSheet {
                // 小屏设备上内容可能超出屏幕：整体可纵向滑动
                Column(
                    Modifier
                        .fillMaxHeight()
                        .verticalScroll(rememberScrollState())
                ) {
                    // 顶部：当前账号
                    Column(Modifier.fillMaxWidth().padding(16.dp)) {
                        Text("MoonBazaar", style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(2.dp))
                        Text(
                            current?.displayName ?: "未登录",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                        if (current != null && current.uid.isNotBlank()) {
                            Text("UID ${current.uid}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.outline)
                        }
                    }
                    HorizontalDivider()
                    Spacer(Modifier.height(8.dp))

                    // 功能导航
                    tabs.forEachIndexed { i, t ->
                        NavigationDrawerItem(
                            label = { Text(t.label) },
                            selected = tabIndex == i,
                            onClick = {
                                tabIndex = i
                                scope.launch { drawerState.close() }
                            },
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp)
                        )
                    }

                    Spacer(Modifier.height(8.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(8.dp))
                    Text("账户", style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 28.dp, vertical = 6.dp))

                    // 账户列表（点击切换）
                    accounts.forEach { a ->
                        NavigationDrawerItem(
                            label = {
                                Column {
                                    Text(a.displayName)
                                    if (a.uid.isNotBlank()) {
                                        Text("UID ${a.uid}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.outline)
                                    }
                                }
                            },
                            selected = a.key == currentKey,
                            onClick = {
                                vm.switchAccount(a.key)
                                scope.launch { drawerState.close() }
                            },
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp)
                        )
                    }

                    if (accounts.isEmpty()) {
                        Text("（暂无已登录账号）",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.padding(horizontal = 28.dp, vertical = 6.dp))
                    }

                    NavigationDrawerItem(
                        label = { Text("＋ 添加账号") },
                        selected = false,
                        onClick = {
                            onStartLogin()
                            scope.launch { drawerState.close() }
                        },
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp)
                    )

                    if (current != null) {
                        NavigationDrawerItem(
                            label = { Text("退出当前账号") },
                            selected = false,
                            onClick = {
                                vm.logout()
                                scope.launch { drawerState.close() }
                            },
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp)
                        )
                    }

                    // 启动时申请的 MoonBazaar 官方 token（只显示状态，不显示 token 本体）
                    Spacer(Modifier.height(8.dp))
                    HorizontalDivider()
                    Column(Modifier.padding(horizontal = 28.dp, vertical = 8.dp)) {
                        Text("MoonBazaar Token",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary)
                        Text(
                            mbTokenLine(mb),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (mb.ok) MaterialTheme.colorScheme.outline
                            else MaterialTheme.colorScheme.error
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                }
            }
        }
    ) {
        Scaffold(
            topBar = {
                AppTopBar(
                    title = tabs[tabIndex].label,
                    onMenu = { scope.launch { drawerState.open() } }
                )
            }
        ) { inner ->
            val m = Modifier.padding(inner)
            when (tabs[tabIndex]) {
                Tab.Home -> HomeScreen(vm, onStartLogin, { scope.launch { drawerState.open() } }, m)
                Tab.Surveys -> if (ban.banned) BannedScreen(ban.reason, m, "问卷") else SurveysScreen(vm, m)
                Tab.Tx -> TransactionsScreen(vm, m)
                Tab.Redeem -> if (ban.banned) BannedScreen(ban.reason, m, "兑换") else RedeemScreen(vm, openUrl, m)
                Tab.Referral -> if (ban.banned) BannedScreen(ban.reason, m, "邀请") else ReferralScreen(vm, openUrl, m)
                Tab.Withdraw -> if (ban.banned) BannedScreen(ban.reason, m, "提现") else WithdrawScreen(vm, m)
            }
        }
    }
}

/** 顶部栏：打开侧边栏 + 当前页标题。 */
@Composable
private fun AppTopBar(title: String, onMenu: () -> Unit) {
    Surface(tonalElevation = 3.dp) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onMenu) { Text("☰  菜单", fontWeight = FontWeight.Bold) }
            Spacer(Modifier.width(6.dp))
            Text(title, style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold)
        }
    }
}

// =============================== 首页
@Composable
private fun HomeScreen(
    vm: MainViewModel,
    onStartLogin: () -> Unit,
    onOpenDrawer: () -> Unit,
    modifier: Modifier
) {
    val auth by vm.auth.collectAsState()
    val authNote by vm.authNote.collectAsState()
    val home by vm.home.collectAsState()
    val loggedIn = auth is AuthUi.LoggedIn

    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        // hero gc
        val grad = Brush.linearGradient(listOf(
            MaterialTheme.colorScheme.primary,
            MaterialTheme.colorScheme.tertiary))
        Column(
            Modifier
                .fillMaxWidth()
                .background(grad)
                .padding(horizontal = 20.dp, vertical = 20.dp)
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("MoonBazaar",
                        color = MaterialTheme.colorScheme.onPrimary,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold)
                    Text("登录 · 兑换 · 邀请 · 提现",
                        color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.9f),
                        style = MaterialTheme.typography.bodySmall)
                }
                Text(home.gc, color = MaterialTheme.colorScheme.onPrimary,
                    fontSize = 42.sp, fontWeight = FontWeight.Bold)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Text("可用 GC", color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.9f),
                    style = MaterialTheme.typography.labelMedium)
            }
        }

        // session bar
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(
                authNote.ifEmpty { if (loggedIn) "已登录" else "未登录" },
                style = MaterialTheme.typography.bodyMedium,
                color = if (loggedIn) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!loggedIn) {
                    Button(onClick = onStartLogin) { Text("登录") }
                } else {
                    OutlinedButton(onClick = { vm.loadHome() }) { Text("刷新") }
                    OutlinedButton(onClick = onOpenDrawer) { Text("切换账号") }
                    OutlinedButton(onClick = { vm.logout() }) { Text("登出") }
                }
            }
        }

        if (home.account.isBanned) {
            BanBanner2(MaterialTheme.colorScheme.onSurface,
                MaterialTheme.colorScheme.errorContainer,
                MaterialTheme.colorScheme.onErrorContainer,
                desc = home.account.banReason)
            Spacer(Modifier.height(8.dp))
        }

        // 账户信息（仅登录后展示，避免登出后残留上一个账号的内容）
        Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Column(Modifier.padding(14.dp)) {
                Text("账户信息", style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(6.dp))
                if (!loggedIn) {
                    Text("登录后可查看账号信息。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.outline)
                } else {
                    FieldRow("用户名", home.account.username)
                    FieldRow("UID", home.account.uid)
                    FieldRow("绑定邮箱", home.account.emailMasked)
                    FieldRow("状态", home.account.status)
                    FieldRow("注册时间", home.account.registeredAt)
                }
            }
        }
        Spacer(Modifier.height(40.dp))
    }
}

@Composable
private fun FieldRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = MaterialTheme.colorScheme.outline)
        Text(value.ifEmpty { "—" }, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun BanBanner2(
    textColor: Color,
    container: Color,
    onText: Color,
    desc: String
) {
    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        colors = CardDefaults.cardColors(containerColor = container)) {
        Column(Modifier.padding(12.dp)) {
            Text("账号已被封禁", fontWeight = FontWeight.Bold, color = onText)
            Spacer(Modifier.height(4.dp))
            Text(desc.ifEmpty { "封禁期间无法进行兑换 / 邀请 / 提现等操作。" },
                color = onText, style = MaterialTheme.typography.bodySmall)
        }
    }
}

// =============================== 主要问卷
@Composable
private fun SurveysScreen(vm: MainViewModel, modifier: Modifier) {
    val s by vm.surveys.collectAsState()
    // 进行中的问卷：非空时在该页内以内嵌 WebView 打开
    var activeSurvey by rememberSaveable { mutableStateOf<String?>(null) }
    val running = activeSurvey

    if (running != null) {
        // 问卷 WebView 打开时，返回键先关问卷，不触发「再按一次退出」。
        BackHandler {
            activeSurvey = null
            vm.loadSurveys()
        }
        SurveyWebScreen(
            startUrl = running,
            onClose = {
                activeSurvey = null
                vm.loadSurveys()
            },
            modifier = modifier
        )
    } else {
        Column(modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("主要问卷", style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold)
                    Text(s.message, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline)
                }
                OutlinedButton(onClick = { vm.loadSurveys() },
                    modifier = Modifier.height(34.dp)) { Text("刷新") }
            }

            // 质量分
            Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                Row(
                    Modifier.fillMaxWidth().padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("质量分", style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.outline)
                        Text("${s.qualityScore}",
                            fontSize = 30.sp, fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary)
                    }
                    if (s.countryCode.isNotBlank()) {
                        Text("地区 ${s.countryCode}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline)
                    }
                }
            }
            Spacer(Modifier.height(10.dp))

            if (s.surveys.isEmpty()) {
                Text(
                    text = if (s.loading) "加载中…" else s.message.ifEmpty { "暂无可做问卷" },
                    modifier = Modifier.padding(horizontal = 16.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.outline
                )
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    itemsIndexed(s.surveys) { _, item ->
                        SurveyCard(item) { url -> activeSurvey = url }
                    }
                    item { Spacer(Modifier.height(24.dp)) }
                }
            }
        }
    }
}

@Composable
private fun SurveyCard(item: SurveyItem, onStart: (String) -> Unit) {
    Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
        Column(Modifier.padding(12.dp)) {
            Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(item.provider.ifEmpty { "未知提供商" },
                        fontWeight = FontWeight.SemiBold)
                    Text("ID ${item.surveyId.ifEmpty { "—" }}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text("${item.reward} GC",
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary)
                    Text("时长 ${item.loi.ifEmpty { "—" }}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline)
                }
            }
            if (item.url.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = { onStart(item.url) },
                    modifier = Modifier.fillMaxWidth()) { Text("开始问卷") }
            }
        }
    }
}

// =============================== 交易流水（分页，单条卡片）
@Composable
private fun TransactionsScreen(vm: MainViewModel, modifier: Modifier) {
    val tx by vm.tx.collectAsState()
    val listState = rememberLazyListState()

    // bottom detection to load more
    val shouldLoad by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: 0
            last >= info.totalItemsCount - 1
        }
    }
    LaunchedEffect(shouldLoad) {
        if (shouldLoad && tx.hasMore && !tx.loading) {
            vm.loadTransactions(tx.records.size)
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("交易流水", style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold)
                Text(tx.message, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline)
            }
            if (tx.records.isNotEmpty()) {
                OutlinedButton(onClick = { vm.loadTransactions(0) },
                    modifier = Modifier.height(34.dp)) { Text("刷新") }
            }
        }
        if (tx.records.isEmpty()) {
            Spacer(Modifier.height(24.dp))
            Text(
                text = if (tx.loading) "加载中…" else (tx.message.ifEmpty { "暂无流水" }),
                modifier = Modifier.padding(horizontal = 16.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.outline
            )
        } else {
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                itemsIndexed(tx.records) { _, rec ->
                    TxCardRow(rec)
                }
                item {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        when {
                            tx.loading -> "加载中…"
                            tx.hasMore -> "继续下滑加载更多"
                            else -> "已全部加载 (${tx.total})"
                        },
                        modifier = Modifier.fillMaxWidth().padding(8.dp),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            }
        }
    }
}

@Composable
private fun TxCardRow(rec: TransactionRecord) {
    val color = if (rec.isGain) Color(0xFF1B8A3A) else Color(0xFFC62828)
    Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
        Column(Modifier.padding(12.dp)) {
            Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(rec.typeName.ifEmpty { "交易" }, fontWeight = FontWeight.SemiBold)
                    Text(rec.createdAt, style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline)
                }
                Text(
                    text = rec.displayAmount,
                    fontWeight = FontWeight.Bold,
                    color = color
                )
            }
            if (rec.description.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(rec.description, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline)
            }
        }
    }
}

// =============================== 兑换
@Composable
private fun RedeemScreen(vm: MainViewModel, openUrl: (String) -> Unit, modifier: Modifier) {
    val r by vm.redeem.collectAsState()
    var code by rememberSaveable { mutableStateOf("") }
    Column(modifier.fillMaxSize().padding(16.dp)) {
        Text("兑换码", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        Text("兑换结果若返回官方确认页，请在浏览器打开并确认后才能入账。",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.outline)
        Spacer(Modifier.height(14.dp))
        OutlinedTextField(value = code, onValueChange = { code = it },
            label = { Text("兑换码") }, placeholder = { Text("如 MOON-1234") },
            singleLine = true, enabled = !r.submitting, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(12.dp))
        Button(onClick = { vm.redeem(code) },
            enabled = code.isNotBlank() && !r.submitting,
            modifier = Modifier.fillMaxWidth()) {
            Text(if (r.submitting) "提交中…" else "兑换")
        }
        if (r.submitting || r.done) {
            Spacer(Modifier.height(16.dp))
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    when {
                        r.submitting -> Text("处理中…", fontWeight = FontWeight.Bold)
                        r.done && r.okHttp -> {
                            Text("✅ 已受理" +
                                (if (r.confirmationUrl.isNotBlank()) "，需在官方确认页确认" else ""),
                                fontWeight = FontWeight.Bold)
                            if (r.confirmationUrl.isNotBlank()) {
                                Spacer(Modifier.height(6.dp))
                                OutlinedButton(onClick = { openUrl(r.confirmationUrl) }) {
                                    Text("打开官方确认页")
                                }
                            }
                        }
                        r.done -> {
                            Text("❌ ${r.error.ifEmpty { "兑换失败" }}", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }
    }
}

// =============================== 邀请
@Composable
private fun ReferralScreen(vm: MainViewModel, openUrl: (String) -> Unit, modifier: Modifier) {
    val ref by vm.referral.collectAsState()
    val ctx = LocalContext.current

    fun copyToClipboard(text: String) {
        if (text.isBlank()) return
        val cm = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("invite", text))
        Toast.makeText(ctx, "已复制", Toast.LENGTH_SHORT).show()
    }

    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("邀请好友", style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            OutlinedButton(onClick = { vm.loadReferral() },
                modifier = Modifier.height(34.dp)) { Text("刷新") }
        }
        if (ref.message.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(ref.message, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error)
        }

        // 统计
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Card(Modifier.weight(1f)) {
                Column(Modifier.padding(14.dp)) {
                    Text("总邀请人数", style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.outline)
                    Text("${ref.totalInvites}",
                        fontSize = 26.sp, fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary)
                }
            }
            Card(Modifier.weight(1f)) {
                Column(Modifier.padding(14.dp)) {
                    Text("累计佣金 (GC)", style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.outline)
                    Text(ref.commissionEarnedGc.ifEmpty { "0" },
                        fontSize = 26.sp, fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary)
                }
            }
        }

        // 邀请链接
        Spacer(Modifier.height(18.dp))
        Text("我的邀请链接", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(6.dp))
        if (ref.uidLink.isBlank()) {
            Text("（暂无数据，请先登录或稍后重试）",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline)
        } else {
            LinkBox(ref.uidLink)
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { copyToClipboard(ref.uidLink) },
                    modifier = Modifier.weight(1f)) { Text("复制链接") }
                OutlinedButton(onClick = { openUrl(ref.uidLink) },
                    modifier = Modifier.weight(1f)) { Text("在浏览器打开") }
            }
        }

        // 自定义链接（可能未设置）
        Spacer(Modifier.height(14.dp))
        Text("自定义邀请链接", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(6.dp))
        if (ref.customLink.isBlank()) {
            Text("未设置自定义邀请链接（可用上方默认链接）",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline)
        } else {
            if (ref.customCode.isNotBlank()) {
                Text("邀请码：${ref.customCode}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(4.dp))
            }
            LinkBox(ref.customLink)
            Spacer(Modifier.height(6.dp))
            OutlinedButton(onClick = { copyToClipboard(ref.customLink) },
                modifier = Modifier.fillMaxWidth()) { Text("复制自定义链接") }
        }

        // 明细
        Spacer(Modifier.height(20.dp))
        Text("邀请明细 (${ref.invites.size}/${ref.totalInvites})",
            style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(6.dp))
        if (ref.invites.isEmpty()) {
            Text(if (ref.loading) "加载中…" else "暂无邀请记录",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline)
        } else {
            ref.invites.forEach { inv ->
                InviteeCard(inv)
                Spacer(Modifier.height(8.dp))
            }
        }
        Spacer(Modifier.height(40.dp))
    }
}

@Composable
private fun LinkBox(link: String) {
    Card(Modifier.fillMaxWidth()) {
        Text(
            link,
            modifier = Modifier.padding(12.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary
        )
    }
}

@Composable
private fun InviteeCard(inv: InviteeRow) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(inv.username.ifEmpty { "（未知用户）" },
                        fontWeight = FontWeight.SemiBold)
                    Text(inv.createdAt,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline)
                }
                Text("${inv.totalEarnedGc} GC",
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary)
            }
            if (inv.isBanned || inv.milestoneAwarded) {
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (inv.milestoneAwarded) {
                        Text("里程碑奖励已发放",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFF1B8A3A))
                    }
                    if (inv.isBanned) {
                        Text("该用户已被封禁",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }
}

// =============================== 提现（卡片）
@Composable
private fun WithdrawScreen(vm: MainViewModel, modifier: Modifier) {
    val products by vm.products.collectAsState()
    val msg by vm.withdrawMsg.collectAsState()
    val ctx = LocalContext.current

    var selected by rememberSaveable { mutableStateOf<Long?>(-1) }
    var amount by remember { mutableStateOf("") }
    var delivery by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }

    val chosen = products.firstOrNull { it.id == selected }

    Column(
        modifier
            .fillMaxSize()
    ) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            Text("提现", style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(16.dp))
            if (products.isEmpty()) {
                Text(msg.ifEmpty { "加载中…" },
                    modifier = Modifier.padding(horizontal = 16.dp),
                    color = MaterialTheme.colorScheme.outline)
            } else {
                products.forEach { p ->
                    WithdrawCard(p, isSelected = p.id == selected,
                        onSelect = { selected = p.id })
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
        HorizontalDivider()
        // 表单
        if (chosen != null) {
            Column(Modifier.padding(16.dp)) {
                OutlinedTextField(value = amount, onValueChange = { amount = it },
                    enabled = chosen.pricingType == "rate",
                    label = { Text(if (chosen.pricingType == "rate")
                        "金额 / 数量" else "固定价格 ${chosen.fixedCostGc} GC，无需输入金额") },
                    singleLine = true,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = androidx.compose.ui.text.input.KeyboardType.Number))
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = delivery,
                    onValueChange = { delivery = it },
                    label = { Text(chosen.deliveryFieldZh.ifEmpty { "接收信息" }) },
                    modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(value = note, onValueChange = { note = it },
                    label = { Text("备注 (可选)") }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                Button(onClick = {
                    val pid = chosen.id
                    vm.submitWithdrawal(chosen, amount, delivery, note)
                }, modifier = Modifier.fillMaxWidth()) {
                    Text("提交提现 ▸ ${chosen.labelZh}")
                }
                if (msg.isNotBlank() || !products.isEmpty() && msg.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    Text(msg, style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace)
                }
            }
        }
    }
}

@Composable
private fun WithdrawCard(p: WithdrawProduct, isSelected: Boolean, onSelect: () -> Unit) {
    val border = if (isSelected) 2.dp else 0.dp
    Card(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .then(if (isSelected) Modifier.padding(1.dp) else Modifier)
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(p.labelZh.ifEmpty { "无名称" }, fontWeight = FontWeight.SemiBold)
                    Text(p.methodZh.ifEmpty { "—" },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline)
                }
                Text(costLine(p), fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary)
            }
            if (p.descriptionText().isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(p.descriptionText(), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline)
            }
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (p.deliveryFieldZh.isNotBlank())
                    Tag("接收: ${p.deliveryFieldZh}")
                if (!p.apiOk) Tag("需官网")
            }
            Spacer(Modifier.height(6.dp))
            OutlinedButton(onClick = onSelect, modifier = Modifier.fillMaxWidth()) {
                Text(if (isSelected) "已选择" else "选择并填写")
            }
        }
    }
}

@Composable
private fun Tag(text: String) {
    Text(text, style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.secondary)
}

// cost line for product card
private fun costLine(p: WithdrawProduct): String {
    return when (p.pricingType) {
        "rate" -> p.gcPerUnit.ifEmpty { "按量计价" } + "/单位"
        else -> "${p.fixedCostGc.ifEmpty { "?" }} GC"
    }
}

// helper on WithdrawProduct to render description (zh)
private fun WithdrawProduct.descriptionText(): String {
    // category + desc combined in Chinese (no extra newlines)
    return buildString {
        if (descZh.isBlank() && categoryZh.isBlank()) return ""
        if (categoryZh.isNotBlank()) append("[").append(categoryZh).append("] ")
        append(descZh)
    }
}

// 封禁时投递只读页
@Composable
private fun BannedScreen(reason: String, modifier: Modifier, feature: String) {
    Column(modifier.fillMaxSize().padding(24.dp)) {
        Card(Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
            Column(Modifier.padding(16.dp)) {
                Text("$feature 已停用", fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onErrorContainer)
                Spacer(Modifier.height(8.dp))
                Text(reason.ifEmpty { "账号已被封禁，无法进行该操作。" },
                    color = MaterialTheme.colorScheme.onErrorContainer)
                Spacer(Modifier.height(8.dp))
                Text("如需详情请联系 MoonBazaar 处理。", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline)
            }
        }
    }
}

@Composable
internal fun JsonBox(title: String, text: String) {
    var open by remember { mutableStateOf(false) }
    val shown = if (text.isEmpty()) "（空）"
    else if (open || text.length <= 1200) text else text.take(900) + "…"
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text(title, style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(4.dp))
            Text(shown, fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall)
            if (text.length > 1200) {
                OutlinedButton(onClick = { open = !open }) { Text(if (open) "收起" else "展开") }
            }
        }
    }
}

/** 侧边栏里 MoonBazaar token 的一行状态（不展示 token 本体）。 */
private fun mbTokenLine(m: MbTokenUi): String = when {
    m.loading -> "申请中…"
    m.ok -> buildString {
        append(if (m.expired) "已过期" else "已获取")
        val left = remainText(m.expiresAt)
        if (left.isNotBlank()) append(" · ").append(left)
        if (m.scopeCount > 0) append(" · ").append(m.scopeCount).append(" 项权限")
        if (m.hasRefresh) append(" · 可本地续期")
    }
    m.message.isNotBlank() -> m.message
    else -> "未申请"
}

/** 把 expires_at（毫秒时间戳）转成粗略的剩余时长。 */
private fun remainText(expiresAt: Long): String {
    if (expiresAt <= 0L) return ""
    val ms = expiresAt - System.currentTimeMillis()
    if (ms <= 0L) return "已过期"
    val minutes = ms / 60_000L
    return when {
        minutes >= 1440L -> "剩余 ${minutes / 1440L} 天"
        minutes >= 60L -> "剩余 ${minutes / 60L} 小时"
        else -> "剩余 ${minutes.coerceAtLeast(1L)} 分钟"
    }
}

/** 组装带登录回跳的授权 URL（供自定义动作/调试用）。 */
internal fun moonLoginUrl(): String {
    val cb = Uri.encode("mnb://auth/callback")
    return AppConfig.BASE_URL.trimEnd('/') + "/auth/login?client_back_uri=$cb"
}
