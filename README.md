# MoonBazaar For Android

MoonBazaar Developer API v1 的非官方 Android 客户端。App 自身**不持有** API Key 与用户令牌，所有请求都发往自建的 **Cloudflare Worker 中转服务**，由 Worker 注入 `X-API-Key`、注入用户 `Authorization: Bearer`，并通过 **Session Token** 把每个请求绑定到真实登录用户。


---

## 目录

- [功能特性](#功能特性)
- [界面结构](#界面结构)
- [鉴权与会话机制](#鉴权与会话机制)
- [技术栈](#技术栈)
- [项目结构](#项目结构)
- [调用的接口](#调用的接口)
- [构建与运行](#构建与运行)
- [配置项](#配置项)
- [设计约定与实现细节](#设计约定与实现细节)
- [安全说明](#安全说明)
- [已知限制](#已知限制)

---

## 功能特性

| 模块 | 说明 |
|---|---|
| **首页** | 顶部渐变大卡片展示**可用 GC**（整数显示）；账户信息卡（用户名 / UID / 绑定邮箱 / 状态 / 注册时间）；被 Ban 时顶部红色横幅并展示原因 |
| **问卷** | `主要问卷`：显示**质量分**与地区，列出当前可做问卷（提供商 / 问卷 ID / 奖励 GC / 时长），`provider_status` 为 `false` 的提供商其问卷自动隐藏，可一键开始问卷 |
| **交易** | 交易流水列表（时间 / 标题 / 详情 / 金额），金额**保留原始小数**，正数绿色、负数红色；动态分页（先探测总数再拉取），滑到底自动加载更多 |
| **兑换** | 兑换码提交，返回官方确认页时可直接唤起浏览器完成确认 |
| **邀请** | 邀请人数与累计佣金统计、专属邀请链接与自定义链接（未设置时优雅降级）、邀请明细列表、一键复制 |
| **提现** | 提现商品以卡片展示（中文名称 / 计价方式 / 接收字段），选择后填写表单提交，展示处理结果 |
| **多账号** | 侧边栏可保存多个已登录账号并**随时切换**，账号各自持有独立 session，互不串数据 |
| **封禁管控** | 账号被 Ban 时：首页提示原因，问卷/兑换/邀请/提现整页禁用，网络层亦拦截请求 |

## 界面结构

采用 **Material 3 侧边栏（Navigation Drawer）** 而非底部导航，侧边栏内容支持滑动，小屏设备可滚动查看全部条目。

```
☰ 菜单
├── 功能导航：首页 · 问卷 · 交易 · 兑换 · 邀请 · 提现
└── 账户    ：账号列表（点击切换） · ＋ 添加账号 · 退出当前账号
```

- 从左缘向右滑动可拉开侧边栏，在侧边栏上向左滑动可收起。
- 每次启动侧边栏默认**关闭**，不自动展开。

## 鉴权与会话机制

MoonBazaar 不提供密码登录 API，登录必须发生在官方页面。本项目的完整链路：

```
① 点击「登录 / ＋ 添加账号」
        ↓
② App 内嵌 WebView 打开  {BASE_URL}/auth/login
   （打开前先清空 WebView 的 Cookie 与浏览器数据，避免复用上一个账号的登录态）
        ↓
③ 用户在 MoonBazaar 官方页完成授权
        ↓
④ Worker 回调 → 兑换 token → 加密存入 Cloudflare KV → 下发 session token
        ↓
⑤ 浏览器落在  {BASE_URL}/auth/done?auth=ok&uid=<UID>&session_token=<token>
        ↓
⑥ WebView 拦截该 URL，提取 session_token，保存账号并关闭
        ↓
⑦ 之后所有请求携带  X-Client-Session: <token>
```

**为什么用 App 内 WebView 而不是系统浏览器？**
系统浏览器无法把 `<token>` 送回 App（Worker 的落地页是一次性 HTML 页面，不会跳回自定义 scheme）。因此登录流程完全在 App 内完成，由 WebView 监视地址并在 `/auth/done` 落地瞬间截获令牌。

**会话存储**：支持多账号，账号列表与当前选中项持久化在 `SharedPreferences`（`moonbazaar_session` → `accounts_json` / `current_key`），并自动迁移旧版本的单账号 token。`accounts_json` 以 **Android Keystore 加密后的密文**落盘（格式 `v1:<iv>:<ciphertext>`），首次读取历史明文数据时会自动重写为密文。

## 技术栈

| 项 | 版本 |
|---|---|
| 语言 | Kotlin 2.2.10 |
| UI | Jetpack Compose + Material 3（Compose BOM `2026.02.01`） |
| 构建 | Gradle 9.5.0（Wrapper） · AGP 9.3.2 |
| JDK | 25（Android Studio 内置 JBR 即可） |
| 网络 | OkHttp 4.12.0 |
| 其他 | androidx.browser 1.8.0 · lifecycle-viewmodel-compose 2.6.1 |
| SDK | `minSdk 29` · `targetSdk 37` · `compileSdk 37` |
| 包名 | `top.witzzz.moonbazaar` |

JSON 解析使用 Android 内置的 `org.json`，未引入额外序列化框架。

## 项目结构

```
project/
├── build.gradle.kts                 顶层构建脚本
├── settings.gradle.kts              模块与仓库配置
├── gradle/libs.versions.toml        版本目录（依赖统一管理）
└── app/
    ├── build.gradle.kts             模块配置（namespace / applicationId / SDK / 依赖）
    └── src/main/
        ├── AndroidManifest.xml      INTERNET 权限、mnb:// 深链、Application 与 Activity 声明
        ├── java/top/witzzz/moonbazaar/
        │   ├── MoonBazaarApp.kt             Application 入口，初始化会话存储
        │   ├── MainActivity.kt              宿主 Activity：Compose 内容 + 登录 WebView + 深链收尾
        │   ├── data/
        │   │   ├── AppConfig.kt             全局常量 + Account + Session（多账号会话存储）
        │   │   ├── MoonWorkerApi.kt         OkHttp 客户端：注入 X-Client-Session / 幂等键
        │   │   └── MoonRepository.kt        Worker 各接口的封装与幂等键生成
        │   └── ui/
        │       ├── MainViewModel.kt         全部界面状态与数据加载逻辑
        │       ├── MoonApp.kt               侧边栏 + 六个功能页面（Compose）
        │       ├── LoginWebView.kt          内嵌 WebView 登录页（拦截 /auth/done）
        │       └── theme/                   Material 3 主题（Color / Theme / Type）
        └── res/                             图标、字符串、主题等资源
```

## 调用的接口

所有业务请求都发往自建 Worker，由 Worker 转发到 `https://moonbazaar.xyz/dev/api/v1`。

| 界面 | 方法 | 路径 | 说明 |
|---|---|---|---|
| 会话 | `GET` | `/status` | Worker 会话与令牌状态（`session_valid` / `has_tokens`） |
| 首页 | `GET` | `/proxy/account/status` | 用户名、UID、打码邮箱、状态、注册时间、封禁信息 |
| 首页 | `GET` | `/proxy/account/balance` | 可用 Gascoin（主页 GC） |
| 问卷 | `GET` | `/proxy/surveys` | 质量分、地区、提供商开关、可做问卷列表 |
| 交易 | `GET` | `/proxy/transactions?limit=&offset=` | 交易流水（分页） |
| 兑换 | `POST` | `/proxy/codes/redeem` | 兑换码（幂等，常返回 `confirmation_url`） |
| 邀请 | `GET` | `/proxy/referrals` | 邀请统计与明细 |
| 邀请 | `GET` | `/proxy/referrals/link` | 邀请链接与自定义链接 |
| 提现 | `GET` | `/proxy/products` | 提现方式与商品 |
| 提现 | `POST` | `/proxy/withdrawals` | 提交提现（幂等） |
| 登出 | `DELETE` | `/user/tokens` | 吊销 Worker session |

写操作统一携带 `Idempotency-Key`（格式 `{op}-{timestamp}-{seq}-{sessionPrefix}`）。

## 构建与运行

**前置条件**：Android Studio（自带 JBR 与 Android SDK）。项目使用 Gradle Wrapper，无需单独安装 Gradle。

Android Studio 中直接 **Sync Project with Gradle Files** 后 Run 即可。命令行构建：

```powershell
# 通过 Android Studio 内置 JBR 提供 JDK
$env:JAVA_HOME = "D:\Program Files\Android\Android Studio\jbr"
$env:Path = "$env:JAVA_HOME\bin;" + $env:Path

cd project
.\gradlew.bat assembleDebug            # 产物：app/build/outputs/apk/debug/app-debug.apk
```

安装到已连接的设备 / 模拟器：

```powershell
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

> 提示：若只改了 Kotlin 源码，可用 `.\gradlew.bat :app:compileDebugKotlin` 快速校验编译。

## 配置项

所有可调参数集中在 `app/src/main/java/top/witzzz/moonbazaar/data/AppConfig.kt`：

| 常量 | 默认值 | 说明 |
|---|---|---|
| `BASE_URL` | `https://mnb.witzzz.top` | **必须改成你自己部署的 Worker 域名** |
| `BASE_HOST` | `mnb.witzzz.top` | 用于识别登录落地页 `/auth/done`，需与 `BASE_URL` 的 host 一致 |
| `CALLBACK_URI` | `mnb://auth/callback` | 深链回调，需与 `AndroidManifest.xml` 中 intent-filter 一致 |
| `CALLBACK_SCHEME` | `mnb` | 深链 scheme |
| `CALLBACK_HOST` | `auth` | 深链 authority，回调校验时要求完全匹配 |
| `CALLBACK_PATH` | `/callback` | 深链 path，回调校验时要求完全匹配 |
| `SOURCE_APP` | `android` | 请求头 `X-Source-App`，便于 Worker 端日志追踪 |

服务端需保证：Worker 已完成部署、已绑定 KV、已配置 `API_KEY` 与 `ENCRYPTION_KEY`，且 `REDIRECT_URI` 与开发者面板登记一致。

## 设计约定与实现细节

**GC 数值显示**
- 首页大号 GC 取整显示；交易金额、问卷奖励、邀请佣金**保留原始小数**（如 `52.8 GC`），不做四舍五入。

**交易分页（动态 limit）**
1. 先发一次探测请求 `limit=1` 取回 `total`；
2. 再按 `limit = min(total, 500)` 拉取；
3. 若总数超过 500，则回退为多页加载，滑到列表底部自动请求下一页。

**问卷过滤**
- `provider_status` 中为 `false` 的提供商，其问卷不展示（键名大小写不敏感匹配）；未列出的提供商不隐藏。

**封禁管控（三层）**
1. 首页红色横幅展示 `ban_reason`；
2. 问卷 / 兑换 / 邀请 / 提现被替换为只读禁用页；
3. ViewModel 层在发起请求前拦截，不会向上游发送写操作。

**账号切换（账号作用域）**
- 切换账号时会先清空界面状态（首页 / 交易 / 问卷 / 邀请 / 提现）再重新拉取，避免两个账号的数据串台；
- 退出当前账号会尽力吊销 Worker session，并从本地账号列表移除；若仍有其它账号则自动切换过去。

**账号作用域与请求绑定（防旧响应串台）**
- ViewModel 维护一个「账号世代号」：切换账号 / 登出 / 移除账号时递增，并取消上一个账号的全部在途请求；
- 每个请求在**发起时**快照「世代号 + 该账号的 token」，网络返回后先校验世代号，只有仍属于当前账号的响应才允许写入界面；
- 仅靠取消是不够的：OkHttp 的 `execute()` 是阻塞调用、不响应协程取消，取消后协程体仍会继续执行到下一个挂起点，因此必须再校验一次世代号；
- 请求头 `X-Client-Session` 与幂等键都使用快照下来的 token，避免请求真正执行时账号已被切换而带到另一个账号名下；
- `Session.identify()` 按 **token** 定位要回填的账号（而不是「当前账号」），否则 A 的响应会把 UID 写到已切换到的 B 上。

## 安全说明

- **API Key 永不进入 App**：客户端只与自建 Worker 通信，`X-API-Key` 由 Worker 在服务端注入。
- **用户令牌不下发到 App**：Worker 只回传 session token，用户 `access_token` / `refresh_token` 始终加密保存在 Cloudflare KV。
- **登录前清理浏览器数据**：每次打开登录页都会清空 WebView 的 Cookie、WebStorage、表单数据与缓存，防止账号串号。
- **session token 加密落盘**：token 使用 Android Keystore 中的 AES/GCM 密钥加密后写入 `SharedPreferences`，密钥不出进程、不参与备份；加密失败时宁可不落盘，也不写明文。
- **会话数据不参与备份**：`backup_rules.xml` / `data_extraction_rules.xml` 均排除了 `moonbazaar_session.xml`，云备份与换机迁移都不会带走账号凭据。
- **登录回调白名单校验**：深链必须同时匹配 `mnb` + `auth` + `/callback` 三个部分，且仅在本进程内主动发起过登录时才接受其中的 `session_token`；令牌被消费后即失效，无法重放。
- **不要提交真实密钥**：`local.properties`、Worker 的 `.dev.vars`、开发者面板的 API Key 均不应进入版本库。

## 已知限制

- 会话密文由 Android Keystore 密钥保护：若用户在系统设置中清除应用数据、或 Keystore 密钥失效，本地账号列表会不可解并清空，需要重新登录。
- 提现需要账号满足商品的可见性条件（如注册天数、累计收益），不满足时接口会返回业务错误，界面直接展示原始返回。
- 部分接口（交易、邀请、问卷）的字段名以**线上真实返回**为准做了兼容处理（如问卷为 `id` / `payout` / `link`，而非文档示例中的 `survey_id` / `reward` / `url`）；若上游调整字段，需同步更新 `MoonRepository` / `MainViewModel` 中的解析逻辑。
