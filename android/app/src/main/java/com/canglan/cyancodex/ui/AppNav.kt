package com.canglan.cyancodex.ui

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Widgets
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.canglan.cyancodex.BuildConfig
import com.canglan.cyancodex.data.ApiClient
import com.canglan.cyancodex.data.Announcement
import com.canglan.cyancodex.data.Session
import com.canglan.cyancodex.data.TamperDetector
import com.canglan.cyancodex.data.UpdateInfo
import com.canglan.cyancodex.data.Updater
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.ui.draw.clip
import com.canglan.cyancodex.ui.components.safeApi
import com.canglan.cyancodex.ui.components.versionBelow
import com.canglan.cyancodex.ui.components.versionNewer
import com.canglan.cyancodex.ui.liquidglass.LiquidBottomTab
import com.canglan.cyancodex.ui.liquidglass.LiquidBottomTabs
import com.canglan.cyancodex.ui.screens.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import kotlinx.coroutines.launch

object Routes {
    const val HOME = "home"
    const val LIBRARY = "library"
    const val FEED = "feed"
    const val GENERATORS = "generators"
    const val AI = "ai"
    const val PROFILE = "profile"
    const val PROFILE_EDIT = "profile_edit"
    const val COMMAND_DETAIL = "command/{id}"
    const val EDITOR = "editor?cmd={cmd}&id={id}&mc={mc}&cat={cat}&title={title}"
    const val SCOREBOARD = "scoreboard"
    const val ENUMERATE = "enumerate"
    const val SCROLLER = "scroller"
    const val MUSIC = "music"
    const val HISTORY = "history"
    const val LOGIN = "login"
    const val CHECKIN = "checkin"
    const val SETTINGS = "settings"
    const val ABOUT = "about"
    const val TUTORIAL = "tutorial/{id}"
    const val USER = "user/{id}"
    const val ANNOUNCEMENTS = "announcements"
    const val POINTS_LOG = "points_log"
    const val RECHARGE = "recharge"   // 积分充值(爱发电)
    const val MESSAGES = "messages"
    const val CHANGE_PASSWORD = "change_password"
    const val CHANGE_EMAIL = "change_email"
    const val TITLERAW_JSON = "titleraw_json"
    const val FEEDBACK = "feedback"
    const val AI_HISTORY = "ai_history"
    const val PLUGIN_DEV = "plugin_dev"
    const val PLUGIN_BUILDER = "plugin_builder"
    const val CHAT = "chat"
    const val FRIENDS = "friends"
    const val PLUGINS = "plugins"
    const val DM = "dm/{peerId}/{peerName}"
    const val PRIVACY = "privacy"
    const val TERMS = "terms"
    const val OPEN_SOURCE = "open_source"
    const val CHANGELOG = "changelog"
    const val GOMOKU = "gomoku"
    const val SCHOOL_SHARE = "school_share"
    const val AD_REWARD = "ad_reward"
    const val BABY_HOME = "baby_home"
    const val MC_ID = "mc_id"
    const val DEBUG = "debug"
    const val MY_ID = "my_id"
    const val BINDINGS = "bindings"
    const val SPEED_TEST = "speed_test"
    const val MIDI = "midi"

    // 注意: 必须用 Uri.encode (空格 -> %20). 用 URLEncoder 会把空格编成 '+' ,
    // 而 Navigation 只解百分号转义、不还原 '+', 结果指令里的空格会变成 '+' (意见 #93/#89)
    fun dm(peerId: Long, peerName: String): String =
        "dm/$peerId/${android.net.Uri.encode(peerName)}"

    fun commandDetail(id: Long) = "command/$id"
    fun tutorial(id: Long) = "tutorial/$id"
    fun user(id: Long) = "user/$id"

    /**
     * 指令编辑器的路由。
     *
     * @param mc    适用版本 (2026-10-01 加)。AI 回答里的指令代码块会带上 `mc 1.20+` 这样的
     *              语言标记, 点「上传」时把它预填进表单 —— 用户不必再手填一遍。
     * @param cat   分类。同样来自 AI(或留空, 编辑器用默认的 other)。
     * @param title 指令名。来自代码块首行的 `# 标题`。
     *
     * ⚠️ 三个新参数都用 `Uri.encode` —— 标题里可能有空格/中文/`&`, 不编码会把路由串切坏
     *    (与 [dm] 里那个坑同一个道理: `&` 之后的内容会被当成新的 query 参数)。
     */
    fun editor(
        cmd: String = "",
        id: Long? = null,
        mc: String = "",
        cat: String = "",
        title: String = "",
    ): String = "editor?cmd=${android.net.Uri.encode(cmd)}&id=${id ?: -1}" +
        "&mc=${android.net.Uri.encode(mc)}" +
        "&cat=${android.net.Uri.encode(cat)}" +
        "&title=${android.net.Uri.encode(title)}"
}

private data class TabItem(val route: String, val label: String, val icon: ImageVector)

private val tabs = listOf(
    TabItem(Routes.HOME, "首页", Icons.Default.Home),
    TabItem(Routes.LIBRARY, "指令库", Icons.Default.Book),
    TabItem(Routes.FEED, "广场", Icons.Default.Forum),
    TabItem(Routes.GENERATORS, "工具箱", Icons.Default.Widgets),
    TabItem(Routes.AI, "AI", Icons.Default.AutoAwesome),
    TabItem(Routes.PROFILE, "我的", Icons.Default.Person),
)

/**
 * 液态玻璃底栏"压住"的内容高度。
 *
 * 底栏(页脚 + 玻璃条)仍按原样占位, 所以玻璃开启时这个值 = Scaffold 的 innerPadding.bottom。
 * 玻璃关闭 / 非 Tab 页时为 0.dp —— 页面行为与改造前完全一致。
 *
 * 6 个 Tab 页要把它加到滚动容器的底部 contentPadding 上(注意是 contentPadding 而不是
 * Modifier.padding: 后者缩的是视口, 内容就永远到不了玻璃下面, 玻璃又变回一块死矩形),
 * 列表末尾才不会被玻璃盖住。
 */
val LocalGlassBottomInset = compositionLocalOf { 0.dp }

/**
 * 液态玻璃当前是否生效: **本次启动**的玻璃状态 且 系统 >= Android 12 (API 31, 模糊的最低要求)。
 *
 * 页面/组件据此决定走玻璃分支还是原路径。玻璃关闭时一律走原路径, 行为与改造前完全一致。
 *
 * ⚠️ 读的是 `AppState.glassActiveNow`(由 MainActivity.onCreate 冻结的值),
 * **不是** `AppState.liquidGlass`(用户设置)。用户拨开关只改设置, 界面外观要重启后才变
 * —— 理由见 AppState.glassActiveNow 的注释。
 */
val liquidGlassActive: Boolean
    get() = AppState.glassActiveNow &&
        android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S

@Composable
fun AppNav() {
    val navController = rememberNavController()
    // ★ 这里刻意**不**用 `by` 解包成值, 而是保留 State 对象本身。
    // 原因: 玻璃底栏的选中药丸需要一个能被 snapshotFlow 观察到的状态读取点,
    // 而"读取"必须发生在 snapshotFlow 的 lambda **内部**才有效(见下面 LiquidBottomTabs 调用点)。
    val backStackState = navController.currentBackStackEntryAsState()
    val currentRoute = backStackState.value?.destination?.route

    // ===== 客户端检测: 第三方客户端/篡改 -> 上报服务器封禁账号, 全功能阻断 =====
    val activity = (LocalContext.current as? android.app.Activity)
    val tampered = !TamperDetector.isOfficial()
    var tamperReported by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (tampered && !tamperReported && Session.token != null) {
            tamperReported = true
            runCatching {
                ApiClient.get().reportTamper(mapOf("reason" to "tampered-client"))
            }
        }
    }
    if (tampered) {
        AlertDialog(
            onDismissRequest = {},   // 篡改客户端: 不可关闭
            title = { Text("检测到第三方客户端") },
            text = {
                Column {
                    Text("当前客户端签名与官方版本不符，可能为第三方客户端或已被篡改。")
                    Spacer(Modifier.height(4.dp))
                    Text("为保护您的账号安全，本账号已被封禁，无法继续使用。")
                    if (tamperReported || Session.token == null) {
                        Spacer(Modifier.height(8.dp))
                        Text("请卸载当前客户端，从官方渠道重新下载安装。",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { activity?.finish() }) { Text("退出应用") }
            },
        )
        return
    }

    // ===== 强制更新拦截: 版本低于服务器最低支持版本时不可使用 =====
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    var forceUpdate by remember { mutableStateOf<UpdateInfo?>(null) }
    var fuProgress by remember { mutableStateOf(-1f) }   // -1=未下载, 0..1=进度, -2=未知大小
    LaunchedEffect(Unit) {
        // 共存版不走强制更新门禁: 下面的弹窗 onDismissRequest 为空且只能"下载安装/退出应用",
        // 而共存版装官方包只会多出一个独立 App、本版版本号不变 —— 一旦服务器抬高 minVersion,
        // 共存版会被永久锁死。共存版由用户手动替换安装包更新。
        if (Updater.isCoexistBuild(ctx)) return@LaunchedEffect
        val r = runCatching { ApiClient.get().latestUpdate() }.getOrNull()
        val d = r?.data
        if (d != null && d.minVersion.isNotBlank() && versionBelow(BuildConfig.VERSION_NAME, d.minVersion)) {
            forceUpdate = d
        }
    }
    forceUpdate?.let { u ->
        AlertDialog(
            onDismissRequest = {},   // 强制更新: 不可关闭
            title = { Text("版本过低，无法使用") },
            text = {
                Column {
                    Text("当前版本 ${BuildConfig.VERSION_NAME} 已停止支持（最低要求 ${u.minVersion}）。")
                    Spacer(Modifier.height(4.dp))
                    Text("请下载安装最新版后继续使用。")
                    if (fuProgress >= 0f) {
                        Spacer(Modifier.height(8.dp))
                        Text(if (fuProgress == -2f) "下载中…" else "下载中 ${(fuProgress * 100).toInt()}%",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary)
                    }
                }
            },
            confirmButton = {
                if (fuProgress < 0f) {
                    TextButton(onClick = {
                        scope.launch {
                            val url = Updater.resolveUrl(null)
                            if (url == null) { Updater.openInBrowser(ctx, Updater.apkUrl(u.version)); return@launch }
                            val file = Updater.download(ctx, url) { done, total ->
                                fuProgress = if (total > 0) done.toFloat() / total else -2f
                            }
                            if (file == null || !Updater.install(ctx, file)) {
                                Updater.openInBrowser(ctx, url)
                            }
                        }
                    }) { Text("下载安装", color = MaterialTheme.colorScheme.primary) }
                }
            },
            dismissButton = {
                TextButton(onClick = { activity?.finish() }) { Text("退出应用") }
            },
        )
    }

    // ===== 登录门禁: 未登录/未注册不可使用 App, 首次进入引导注册 =====
    if (AppState.token == null) {
        AuthScreen(navController, asGate = true)
        return
    }

    // 系统通知点击跳转: 公告 / 站内信 (intent extra "goto")
    LaunchedEffect(Unit) {
        val goto = activity?.intent?.getStringExtra("goto") ?: return@LaunchedEffect
        activity.intent.removeExtra("goto")
        when (goto) {
            "messages" -> navController.navigate(Routes.MESSAGES)
            "announcements" -> navController.navigate(Routes.ANNOUNCEMENTS)
        }
    }

    // ===== 个性化: 底部导航按设置过滤 (首页不可隐藏) =====
    val shownTabs = tabs.filter { it.route == Routes.HOME || it.route !in AppState.hiddenTabs }
    // 当前所在 Tab 被隐藏时, 回到首页
    LaunchedEffect(AppState.hiddenTabs) {
        val r = currentRoute
        if (r != null && tabs.any { it.route == r } && shownTabs.none { it.route == r }) {
            navController.navigate(Routes.HOME) {
                popUpTo(Routes.HOME) { inclusive = true }
                launchSingleTop = true
            }
        }
    }

    // ===== 液态玻璃 (可选, 默认关) =====
    // 机制: layerBackdrop 把某个节点的绘制内容录成纹理(采样源), drawBackdrop 在玻璃范围内
    // 取这段纹理, 做 提饱和(vibrancy) / 模糊(blur) / 折射(lens) 后画出来。
    //
    // 参考作者官方示例 (github.com/Kyant0/AndroidLiquidGlass, components/LiquidBottomTabs.kt):
    //   采样源 = 一张铺满全屏的壁纸照片
    //   玻璃   = vibrancy() + blur(8dp) + lens(24dp, 24dp)
    //   表面色 = 深色主题 #121212@40% / 浅色主题 #FAFAFA@40%
    //   外层不传 highlight / innerShadow / depthEffect (官方的内层拖拽药丸才传)
    //
    // 上一版"只有一个矩形、根本没有液态"的根因有三个, 这一版全部修掉:
    //   1) 采样源根本不是页面内容, 而是一块被不透明 Scaffold 盖住的合成色板。
    //      而且底栏那一段全是纯色 —— 纯色/平滑渐变模糊后还是平滑的, 折射也无从产生位移,
    //      玻璃必然退化成"一个半透明矩形"。
    //      修法: 采样源改成 NavHost 的**真实绘制内容**(见下面 padding 那段), 玻璃下面
    //      就是真的卡片和文字, 折射才有东西可折。
    //   2) 表面底色用了 colorScheme.surface(带品牌绿), 把玻璃染绿糊平。
    //      修法: 换成官方示例的中性色。
    //   3) 玻璃条被放在 Scaffold 的 bottomBar 里, 而 Scaffold 的 body 会被 bottomBar 顶上去,
    //      于是玻璃正下方**永远**只有 Scaffold 的纯色背景, 无论参数怎么调都救不回来。
    //      修法: 玻璃开启时去掉 NavHost 的底部内缩, 内容一路铺到屏幕底(见 content lambda)。
    //
    // 能力门槛: 模糊需 Android 12+ (API 31); 折射 lens / 边缘高光着色器需 Android 13+
    // (API 33) —— 库内部会自行降级为无效果, 不会崩。设备低于 API 33 时折射本来就不会出现,
    // 这不是参数问题。
    val glassEnabled = liquidGlassActive
    // 无条件 remember: Compose 虽能正确处理条件式 remember, 但保持稳定调用点更稳妥
    val glassBackdrop = rememberLayerBackdrop()
    val glassDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    // 底栏图标/文字用纯黑/纯白 (官方 demo 的做法: contentColor = if (light) Black else White)。
    // 选中态**不靠改文字颜色** —— 官方的选中反馈来自玻璃药丸的折射, 以及内层那份被
    // accentColor 着色的副本。这样图标才不会因为"变绿"而显得像普通 Material 底栏。
    val tabContentColor = if (glassDark) Color.White else Color.Black
    // Tab 切换: 简单可靠 —— 清掉首页之上的页面, 再进入目标 Tab
    // (saveState/restoreState 状态恢复在本机有错乱问题, 弃用)
    val onTabClick: (TabItem) -> Unit = { t ->
        // ★ 这里**不能**用闭包捕获的 `currentRoute` —— 2026-09-25 修掉的 bug。
        //
        // 玻璃底栏(LiquidBottomTabs)内部是这样持有回调的:
        //     LaunchedEffect(dampedDragAnimation) { snapshotFlow { currentIndex }... { onTabSelected(index) } }
        // key 是 `dampedDragAnimation`(remember 出来的, 恒定不变), 所以这个 effect **只启动一次**,
        // 它拿到的 `onTabSelected` 永远是**首次组合那一份**闭包。闭包里捕获的 currentRoute
        // 就停在了 App 启动时的值("home")。
        //
        // 于是"拖药丸切页签"会出现假阴性:
        //   在「我的」把药丸拖到「首页」→ onTabClick(首页) → 捕获的 currentRoute 还是 "home"
        //   → `"home" != "home"` 为 false → **不导航** → 药丸跑到首页了, 内容还留在「我的」。
        // 这正是"拖动后上一个页面残留在原处、界面出现错误页面"的原因。
        //
        // 修法: 在**调用时**从 navController 实时读当前路由(它不是闭包捕获, 每次调用都取最新值)。
        if (navController.currentDestination?.route != t.route) {
            navController.navigate(t.route) {
                popUpTo(Routes.HOME)
                launchSingleTop = true
            }
        }
    }

    // ★ 2.0.0 官方改造：站内信 / 公告 这些二级页面也是从 Tab 里进去的，
    //   底部导航栏一并显示（高亮「我的」），避免底部空一块、也方便直接切走。
    //
    // ⛔⛔ **「好友」必须排除在外**（2026-10-05 用户报「好友界面有两个底部菜单」）——
    //   这是**官方 2.0.0 自身的 bug**：官方把 `Routes.FRIENDS` 也算进了 secondaryTabbed，
    //   而官方 `FriendsScreen` **自带一条底栏**（好友 / 申请 / 私聊 三个子页签，
    //   见 `FriendsScreen.kt` 里的 `FriendsTab` + `NavigationBar` / `LiquidBottomTabs`）
    //   → 全局底栏再叠一条，页面上就出现**两条底栏**。
    //   站内信 / 公告 那两个页面**没有**自带底栏，显示全局底栏才是对的，所以它们保留。
    //   判据：**页面自带底栏的，一律不再叠全局底栏**（这也正是 1.0.90 的行为）。
    //
    //   注意：这里必须定义在**函数作用域**（而不是 bottomBar 的 lambda 里），
    //   因为下面 content lambda 算 barOnScreen / LocalGlassBottomInset 时也要用到它。
    val secondaryTabbed = currentRoute == Routes.MESSAGES ||
        currentRoute == Routes.ANNOUNCEMENTS

    Box(Modifier.fillMaxSize()) {
        Scaffold(
        // 容器保持不透明: 采样源被它盖住, 仅透过玻璃可见 —— 避免整屏被换掉背景
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            Column {
                // 全局页脚: 宝贝回家公益信息条 (每个页面底部都有; 看广告页除外, 避免误触)
                if (currentRoute != Routes.AD_REWARD) {
                    BabyHomeFooter(onClick = { navController.navigate(Routes.BABY_HOME) })
                }
                // ★ 2.0.0：二级页面(站内信/公告)也显示底栏并高亮「我的」
                //   （「好友」不在其中 —— 它自带底栏，叠上来会变成两条，见 secondaryTabbed 注释）
                if (shownTabs.any { it.route == currentRoute } || secondaryTabbed) {
                    if (glassEnabled) {
                        // ★ 官方 LiquidBottomTabs —— 逐字搬运自 github.com/Kyant0/AndroidLiquidGlass
                        //   (见 ui/liquidglass/, Apache-2.0, 出处与改动记录都在那个包里)
                        //
                        //   和上一版手写实现的关键差别:
                        //     · 形状 Capsule() 来自 com.kyant.shapes (连续曲率), 不是 RoundedCornerShape(50%)
                        //     · 选中药丸用 rememberCombinedBackdrop 做**二次折射**, 边缘才有层次
                        //     · DampedDragAnimation: 按压弹簧放大 + 拖拽跟手 + 松手回弹
                        //     · InteractiveHighlight: 按下时手指位置一团 AGSL 柔光
                        //     · 整条玻璃按压时横向拉伸 (layerBlock 里的 scaleX lerp)
                        // ★★ 选中药丸位置 —— 2026-09-25 修掉的一个真 bug
                        //
                        // 官方 LiquidBottomTabs 内部是这样观察选中项的:
                        //     LaunchedEffect(selectedTabIndex) {
                        //         snapshotFlow { selectedTabIndex() }.collectLatest { currentIndex = it }
                        //     }
                        // snapshotFlow 只能观察到**在它自己的 lambda 里读到的 Compose 状态**。
                        //
                        // 上一版我们传的是 `{ selectedIndex.coerceAtLeast(0) }`, 其中 selectedIndex
                        // 是组合期用 `shownTabs.indexOfFirst { it.route == currentRoute }` 算好的
                        // **普通 Int**。它被捕获进闭包时值就已经定死了, lambda 里没有"读状态"这个
                        // 动作 → flow 永不重发射 → currentIndex 冻在首次组合的值。
                        // 后果有两个, 都是致命的:
                        //   ① 药丸永远停在第一个页签, 点哪个都不动(暗色切换触发重建后才会跳一次);
                        //   ② 药丸是画在页签**之上**的 Box, 还挂着 pointerInput 拖拽手势 ——
                        //      它压住哪个页签, 那个页签就完全点不动(实测"我的"怎么点都没反应)。
                        //
                        // 修法: 用 derivedStateOf 包一层, 把对 backStackState 的读取放进 lambda 内部。
                        // 这样 selectedTabIndex() 就是一个真正的状态读取, snapshotFlow 能观察到。
                        // 注意 `remember(shownTabs)` 这个 key 不能省: derivedStateOf 的 lambda 会捕获
                        // 创建时的 shownTabs, 用户改了"底栏显示哪些页签"后必须重建, 否则用的是旧列表。
                        val selectedIndexState = remember(shownTabs) {
                            derivedStateOf {
                                val r = backStackState.value?.destination?.route
                                val idx = shownTabs.indexOfFirst { it.route == r }
                                if (idx >= 0) idx
                                // ★ 2.0.0：二级页面(站内信/公告)不属于任何 Tab，
                                //   此时药丸停在「我的」上（与官方 NavigationBar 的高亮一致）。
                                //   （「好友」不在此列 —— 它自带底栏，不叠全局底栏，见上面 secondaryTabbed 的注释）
                                //   注意这**不会**触发误导航 —— 下面 onTabSelected 里有
                                //   "当前确实停在某个 Tab 页" 的守卫，二级页过不了那道门。
                                else shownTabs.indexOfFirst { it.route == Routes.PROFILE }.coerceAtLeast(0)
                            }
                        }
                        LiquidBottomTabs(
                            selectedTabIndex = { selectedIndexState.value },
                            // ★★ 第二个 bug (2026-09-25): "在「我的」页点『设置』会错误跳到首页"
                            //
                            // 进到非 Tab 页(设置/详情/聊天…)时, `selectedIndexState` 算出来是 -1
                            // (没有哪个页签匹配当前路由), 被 coerceAtLeast(0) 变成 **0 = 首页**。
                            // 而 LiquidBottomTabs 内部对 `currentIndex` 的变化是会**回调**
                            // onTabSelected 的(snapshotFlow{currentIndex}.drop(1)) ——
                            // 于是"离开 Tab 页"这个动作本身被当成"用户选中了首页", 触发一次导航。
                            //
                            // 又因为 backStackState 的更新比 NavHost 换页晚一拍(它是 Flow 驱动的),
                            // 底栏会晚一帧才被移除, 所以这一次误回调**来得及**发生 —— 表现为
                            // "点设置 → 先跳到首页", 而且时有时无(竞态)。
                            //
                            // 修法: 只允许在"当前确实停在某个 Tab 页"时才响应页签回调。
                            // 路由用**实时读**的 navController.currentDestination, 不用闭包捕获值
                            // (原因同上面 onTabClick 的注释: 这个 lambda 会被长期持有, 捕获值会过期)。
                            // 同理, 这里也不能用闭包捕获的 `shownTabs`(同样会过期),
                            // 实时从 tabs + 当前隐藏设置重新算一份。
                            onTabSelected = { i ->
                                val now = navController.currentDestination?.route
                                val live = tabs.filter { it.route == Routes.HOME || it.route !in AppState.hiddenTabs }
                                if (now != null && live.any { it.route == now }) {
                                    live.getOrNull(i)?.let(onTabClick)
                                }
                            },
                            backdrop = glassBackdrop,
                            tabsCount = shownTabs.size,
                            // 本 App 的深浅色是**用户设置项**(light/dark/system),
                            // 官方默认的 isSystemInDarkTheme() 在"强制浅色"时会判断错, 所以由我们传入
                            isLightTheme = !glassDark,
                            // 官方写死亮蓝(#0088FF / #0091FF)。本 App 是绿色主题, 亮蓝药丸看着像
                            // "另一个 App 的配色", 所以换成主题主色。这是官方预留的着色入口
                            // (给那份 alpha=0 的副本整体 tint, 再由药丸折射出来)。
                            accentColor = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .fillMaxWidth()
                                // ★ 手势区留在玻璃外面 —— 这一句就是"底部那块死玻璃"的解药
                                .navigationBarsPadding()
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                        ) {
                            shownTabs.forEach { t ->
                                LiquidBottomTab({ onTabClick(t) }) {
                                    Icon(
                                        imageVector = t.icon,
                                        contentDescription = t.label,
                                        modifier = Modifier.size(22.dp),
                                        tint = tabContentColor,
                                    )
                                    Text(
                                        text = t.label,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = tabContentColor,
                                        maxLines = 1,
                                    )
                                }
                            }
                        }
                    } else {
                        // 玻璃关闭: 原样保留 Material 底栏 (containerColor 显式给 surface,
                        // 与改造前的写法一致)
                        NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                            shownTabs.forEach { t ->
                                NavigationBarItem(
                                    selected = if (secondaryTabbed) t.route == Routes.PROFILE else currentRoute == t.route,
                                    onClick = { onTabClick(t) },
                                    icon = { Icon(t.icon, t.label) },
                                    label = { Text(t.label, style = MaterialTheme.typography.labelSmall) },
                                )
                            }
                        }
                    }
                }
            }
        },
        ) { padding ->
        // ===== 玻璃开启时的布局改造 (关闭时走原路径, 零差异) =====
        // 玻璃条画在底栏里(页脚之下)。要让玻璃真的"有东西可折射", 底栏正下方必须是真实页面内容,
        // 而不是 Scaffold 的纯色底 —— 纯色被模糊后还是纯色, 折射也无从产生位移, 玻璃就退化成
        // "一个半透明矩形"(这正是上一版的观感)。
        // 做法: 把 NavHost 的底部内缩去掉, 让它一路铺到屏幕底, 内容就流到了玻璃下面;
        // 底栏(不透明)压住的那一段, 由各 Tab 页用 LocalGlassBottomInset 在列表末尾补回来。
        val barOnScreen = glassEnabled && (shownTabs.any { it.route == currentRoute } || secondaryTabbed)
        val navModifier = if (barOnScreen)
            Modifier.padding(top = padding.calculateTopPadding()).fillMaxSize()
        else Modifier.padding(padding)
        // 采样源 = NavHost 自己的绘制内容。录制的节点取整屏大小, 采样坐标系才不会偏移。
        val recordModifier = if (barOnScreen)
            Modifier.fillMaxSize().layerBackdrop(glassBackdrop)
        else Modifier.fillMaxSize()
        CompositionLocalProvider(
            LocalGlassBottomInset provides if (barOnScreen) padding.calculateBottomPadding() else 0.dp,
        ) {
        Box(recordModifier) {
        NavHost(
            navController = navController,
            startDestination = Routes.HOME,
            // 玻璃开启时用 navModifier(去掉底部内缩, 内容铺到屏幕底); 否则与原版一致
            modifier = navModifier,
            // 过渡动画统一 0.3s, 观感更流畅
            enterTransition = { fadeIn(animationSpec = tween(300)) },
            exitTransition = { fadeOut(animationSpec = tween(300)) },
            popEnterTransition = { fadeIn(animationSpec = tween(300)) },
            popExitTransition = { fadeOut(animationSpec = tween(300)) },
        ) {
            composable(Routes.HOME) { HomeScreen(navController) }
            composable(Routes.LIBRARY) { LibraryScreen(navController) }
            composable(Routes.FEED) { FeedScreen(navController) }
            composable(Routes.GENERATORS) { ToolboxScreen(navController) }
            composable(Routes.AI) { AiScreen(navController) }
            composable(Routes.PROFILE) { ProfileScreen(navController) }
            composable(Routes.PROFILE_EDIT) { ProfileEditScreen(navController) }

            composable(
                Routes.USER,
                arguments = listOf(navArgument("id") { type = androidx.navigation.NavType.LongType }),
            ) { UserProfileScreen(navController, it.arguments?.getLong("id") ?: 0L) }

            composable(
                Routes.COMMAND_DETAIL,
                arguments = listOf(navArgument("id") { type = androidx.navigation.NavType.LongType }),
            ) { CommandDetailScreen(navController, it.arguments?.getLong("id") ?: 0L) }

            composable(
                Routes.EDITOR,
                arguments = listOf(
                    navArgument("cmd") { defaultValue = "" },
                    navArgument("id") { defaultValue = -1L },
                    // 2026-10-01: AI 回答里的指令一键上传 —— 版本/分类/标题预填
                    navArgument("mc") { defaultValue = "" },
                    navArgument("cat") { defaultValue = "" },
                    navArgument("title") { defaultValue = "" },
                ),
            ) { entry ->
                val cmd = entry.arguments?.getString("cmd") ?: ""
                val id = entry.arguments?.getLong("id") ?: -1L
                CommandEditorScreen(
                    nav = navController,
                    initialCmd = cmd,
                    editId = id,
                    initialMc = entry.arguments?.getString("mc").orEmpty(),
                    initialCat = entry.arguments?.getString("cat").orEmpty(),
                    initialTitle = entry.arguments?.getString("title").orEmpty(),
                )
            }

            composable(Routes.SCOREBOARD) { ScoreboardScreen(navController) }
            composable(Routes.ENUMERATE) { EnumerateScreen(navController) }
            composable(Routes.SCROLLER) { ScrollerScreen(navController) }
            composable(Routes.MUSIC) { MusicScreen(navController) }
            composable(Routes.HISTORY) { HistoryScreen(navController) }
            composable(Routes.LOGIN) { AuthScreen(navController) }
            composable(Routes.CHECKIN) { CheckInScreen(navController) }
            composable(Routes.SETTINGS) { SettingsScreen(navController) }
            composable(Routes.ABOUT) { AboutScreen(navController) }
            composable(Routes.ANNOUNCEMENTS) { AnnouncementsScreen(navController) }
            composable(Routes.POINTS_LOG) { PointsLogScreen(navController) }
        composable(Routes.RECHARGE) { RechargeScreen(navController) }
            composable(Routes.MESSAGES) { MessagesScreen(navController) }
            composable(Routes.CHANGE_PASSWORD) { ChangePasswordScreen(navController) }
            composable(Routes.CHANGE_EMAIL) { ChangeEmailScreen(navController) }
            composable(Routes.TITLERAW_JSON) { TitlerawJsonScreen(navController) }
            composable(Routes.FEEDBACK) { FeedbackScreen(navController) }
            composable(Routes.AI_HISTORY) { AiHistoryScreen(navController) }
            composable(Routes.PLUGIN_DEV) { PluginDevScreen(navController) }
            composable(Routes.PLUGIN_BUILDER) { PluginBuilderScreen(navController) }
            composable(Routes.CHAT) { ChatScreen(navController) }
            composable(Routes.FRIENDS) { FriendsScreen(navController) }
            composable(Routes.PLUGINS) { PluginScreen(navController) }
            composable(Routes.PRIVACY) { PrivacyScreen(navController) }
            composable(Routes.TERMS) { TermsScreen(navController) }
            composable(Routes.OPEN_SOURCE) { OpenSourceScreen(navController) }
            composable(Routes.CHANGELOG) { ChangelogScreen(navController) }
            composable(Routes.GOMOKU) { GomokuScreen(navController) }
            composable(Routes.SCHOOL_SHARE) { SchoolShareScreen(navController) }
            composable(Routes.AD_REWARD) { AdRewardScreen(navController) }
            composable(Routes.BABY_HOME) { BabyHomeScreen(navController) }
            composable(Routes.MC_ID) { McIdScreen(navController) }
            composable(Routes.DEBUG) { DebugScreen(navController) }
            composable(Routes.MY_ID) { MyIdScreen(navController) }
            composable(Routes.BINDINGS) { BindingsScreen(navController) }
            composable(Routes.SPEED_TEST) { SpeedTestScreen(navController) }
            composable(Routes.MIDI) { MidiScreen(navController) }
            composable(
                Routes.DM,
                arguments = listOf(
                    navArgument("peerId") { type = androidx.navigation.NavType.LongType },
                    navArgument("peerName") { defaultValue = "" },
                ),
            ) { entry ->
                val peerId = entry.arguments?.getLong("peerId") ?: 0L
                val peerName = entry.arguments?.getString("peerName") ?: ""
                DmScreen(navController, peerId, peerName)
            }
            composable(
                Routes.TUTORIAL,
                arguments = listOf(navArgument("id") { type = androidx.navigation.NavType.LongType }),
            ) { TutorialScreen(navController, it.arguments?.getLong("id") ?: 0L) }
        }
        }
        }
        }
    }

    // ===== 启动检查: 新公告弹窗 (含更新公告的下载安装) =====
    var annDialog by remember { mutableStateOf<Announcement?>(null) }
    var dlProgress by remember { mutableStateOf(-1f) }   // -1=未下载, 0..1=进度, -2=未知大小
    LaunchedEffect(Unit) {
        val r = safeApi { ApiClient.get().announcements("all") }
        if (r.ok) {
            val anns = r.data ?: emptyList()
            val unseen = anns.filter { it.id > Session.lastSeenAnnouncementId }
            if (unseen.isNotEmpty()) annDialog = unseen.first()
            anns.maxOfOrNull { it.id }?.let { Session.lastSeenAnnouncementId = it }
        }
    }
    annDialog?.let { a ->
        AlertDialog(
            onDismissRequest = { annDialog = null },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AnnTypeBadge(a.type)
                    Spacer(Modifier.width(8.dp))
                    Text(a.title)
                }
            },
            // 长公告要能滚到底（用户反馈「公告滑不到底」）：限高 + 垂直滚动
            text = {
                Column(
                    Modifier
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState()),
                ) { Text(a.content) }
            },
            confirmButton = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val isUpdate = a.type == "update" && versionNewer(a.version)
                    if (isUpdate && dlProgress >= 0f) {
                        Text(if (dlProgress == -2f) "下载中…" else "下载中 ${(dlProgress * 100).toInt()}%",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary)
                    }
                    TextButton(onClick = { annDialog = null }) { Text("知道了") }
                    // 活动公告: 跳转活动页 (开学季福利等)
                    if (a.type == "activity") {
                        TextButton(onClick = {
                            annDialog = null
                            navController.navigate(Routes.SCHOOL_SHARE)
                        }) { Text("去参与", color = MaterialTheme.colorScheme.primary) }
                    }
                    if (isUpdate && dlProgress < 0f) {
                        TextButton(onClick = {
                            scope.launch {
                                val url = Updater.resolveUrl(a.version)
                                if (url == null) { annDialog = null; return@launch }
                                val file = Updater.download(ctx, url) { done, total ->
                                    dlProgress = if (total > 0) done.toFloat() / total else -2f
                                }
                                annDialog = null
                                if (file == null || !Updater.install(ctx, file)) {
                                    Updater.openInBrowser(ctx, url)
                                }
                            }
                        }) { Text("下载安装", color = MaterialTheme.colorScheme.primary) }
                    }
                }
            },
        )
    }
}


/** 公告类型徽章 */
@Composable
private fun AnnTypeBadge(type: String) {
    val (label, color) = when (type) {
        "update" -> "更新" to Color(0xFF4ADE80)
        "punish" -> "处罚" to Color(0xFFF87171)
        else -> "公告" to Color(0xFF38BDF8)
    }
    Surface(shape = MaterialTheme.shapes.small, color = color.copy(alpha = 0.18f)) {
        Text(label, color = color, style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp))
    }
}
