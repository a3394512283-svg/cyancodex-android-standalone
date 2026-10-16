package com.canglan.cyancodex.ui.screens

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Feedback
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import com.canglan.cyancodex.ui.theme.dynamicColorSupported
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.navigation.NavHostController
import com.canglan.cyancodex.data.ApiClient
import com.canglan.cyancodex.data.Session
import com.canglan.cyancodex.data.SettingsBackup
import com.canglan.cyancodex.ime.CyanCodexImeSettings
import com.canglan.cyancodex.overlay.FloatWindowService
import com.canglan.cyancodex.ui.AppState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.canglan.cyancodex.ui.Routes
import com.canglan.cyancodex.ui.components.AppTopBar
import com.canglan.cyancodex.ui.components.GlassActionButton
import com.canglan.cyancodex.ui.components.GlassFilterChip
import com.canglan.cyancodex.ui.components.GlassSwitch
import com.canglan.cyancodex.ui.components.safeApi

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(nav: NavHostController) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var savedMsg by remember { mutableStateOf("") }
    // 设置云端备份 / 恢复
    var showBackup by remember { mutableStateOf(false) }
    var showRestore by remember { mutableStateOf(false) }
    var backupStamp by remember { mutableStateOf(0L) }
    var reduceMotion by remember { mutableStateOf(Session.reduceMotion) }
    var animScale by remember { mutableStateOf(Session.animationsScale) }
    var canOverlay by remember { mutableStateOf(Settings.canDrawOverlays(ctx)) }
    var floatRunning by remember { mutableStateOf(FloatWindowService.running) }
    var imeEnabled by remember { mutableStateOf(isImeEnabled(ctx)) }
    var quickCount by remember { mutableStateOf(Session.quickCommands().size) }
    var notifEnabled by remember { mutableStateOf(NotificationManagerCompat.from(ctx).areNotificationsEnabled()) }
    var bgWatch by remember { mutableStateOf(Session.backgroundWatch) }
    var ignoringBattery by remember { mutableStateOf(isIgnoringBattery(ctx)) }
    var plugins by remember { mutableStateOf(com.canglan.cyancodex.plugin.PluginManager.list()) }
    // 调试面板入口可见性。
    //
    // ★★ 2026-10-05 修 bug: 用户原话「我不是说把调试模式关上设置底下的调试就自动隐藏吗」。
    //    原来这里写的是 `remember { mutableStateOf(DebugStore.unlocked) }`, 有两个毛病:
    //      ① 判的是 **unlocked(入口闸)**, 没判**总开关** —— 于是总开关关掉了, 入口照样在;
    //      ② `remember` 是一次性快照 —— 在调试面板里改了开关, 返回设置页**不会刷新**。
    //    现在直接读 [DebugStore.activeState](解锁 且 总开关开着), 它是 Compose 可观察的,
    //    开关一改就重组。语义与安卓「开发者选项」一致: 顶部开关一关, 设置里的入口就没了。
    //
    // ⚠️ 关掉之后怎么再进去? 见 AboutScreen: 版本号连点 7 次会**重新打开总开关**并跳转,
    //    这就是安卓那边"再点 7 次版本号"的同款兜底, 不会把自己锁在外面。
    val debugVisible = com.canglan.cyancodex.data.DebugStore.activeState

    fun checkOverlay() {
        canOverlay = Settings.canDrawOverlays(ctx)
        floatRunning = FloatWindowService.running
        notifEnabled = NotificationManagerCompat.from(ctx).areNotificationsEnabled()
        plugins = com.canglan.cyancodex.plugin.PluginManager.list()
        bgWatch = Session.backgroundWatch
        ignoringBattery = isIgnoringBattery(ctx)
    }

    // 申请消息推送权限
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        notifEnabled = it
    }

    // 从系统设置页返回时自动刷新权限/运行状态
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                checkOverlay()
                imeEnabled = isImeEnabled(ctx)
                quickCount = Session.quickCommands().size
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Scaffold(
        topBar = {
            AppTopBar(title = "设置 / 优化", icon = Icons.Default.Settings, onBack = { nav.popBackStack() })
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)) {

            SectionHeader("性能与存储")

            // ===== 性能优化 =====
            SectionCard("性能优化") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("减少动画效果", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    GlassSwitch(checked = reduceMotion, onCheckedChange = {
                        reduceMotion = it
                        Session.reduceMotion = it
                        // 全局动画缩放 (降低系统动画)
                        try {
                            Settings.Global.putFloat(ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, if (it) 0f else 1f)
                            Settings.Global.putFloat(ctx.contentResolver, Settings.Global.WINDOW_ANIMATION_SCALE, if (it) 0f else 1f)
                            Settings.Global.putFloat(ctx.contentResolver, Settings.Global.TRANSITION_ANIMATION_SCALE, if (it) 0f else 1f)
                        } catch (e: Exception) { /* 无 WRITE_SETTINGS 权限时忽略 */ }
                    })
                }
                Text("动画缩放: ${"%.2f".format(animScale)}", style = MaterialTheme.typography.labelMedium)
                Slider(value = animScale, onValueChange = {
                    animScale = it
                    Session.animationsScale = it
                }, valueRange = 0.25f..1f)
                Text("说明: 降低动画可减少 GPU 开销, 提升低端机流畅度。", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                // 注: 「关于」「意见申诉」原先挤在这个性能卡片里, 已挪到页面底部的
                //     「关于与支持」分组 —— 性能卡片只留性能相关操作。
                GlassActionButton(onClick = {
                    Session.clearCacheData()
                    quickCount = Session.quickCommands().size
                    savedMsg = "已清理本地缓存"
                }, modifier = Modifier.fillMaxWidth()) { Text("清除本地缓存") }
            }

            SectionHeader("外观与交互")

            // ===== 外观 =====
            SectionCard("外观") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("system" to "跟随系统", "light" to "白天", "dark" to "夜晚").forEach { (k, v) ->
                        GlassFilterChip(selected = AppState.themeMode == k, onClick = { AppState.applyThemeMode(k) },
                            label = { Text(v) })
                    }
                }
                Text("默认跟随系统。", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                // 主题配色来源: 品牌默认 / 莫奈取色(需 Android 12+) / 自定义颜色
                Text("主题配色", style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GlassFilterChip(selected = AppState.themeSource == "brand",
                        onClick = { AppState.applyThemeSource("brand") }, label = { Text("品牌默认") })
                    if (dynamicColorSupported()) {
                        GlassFilterChip(selected = AppState.themeSource == "dynamic",
                            onClick = { AppState.applyThemeSource("dynamic") }, label = { Text("莫奈取色") })
                    }
                    GlassFilterChip(selected = AppState.themeSource == "custom",
                        onClick = { AppState.applyThemeSource("custom") }, label = { Text("自定义颜色") })
                }
                if (!dynamicColorSupported()) {
                    Text("「莫奈取色」需 Android 12 及以上系统, 当前系统不支持 (已自动隐藏)。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (AppState.themeSource == "custom") {
                    ThemeColorPicker(AppState.themeCustomColor) { AppState.applyThemeCustomColor(it) }
                }
                // 自定义背景图片 (意见 id=22): 优先于插件渐变背景
                var bgImg by remember { mutableStateOf(Session.backgroundImage) }
                val bgPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
                    if (uri != null) {
                        scope.launch {
                            val target = java.io.File(ctx.filesDir, "custom_bg.jpg")
                            val ok = runCatching {
                                ctx.contentResolver.openInputStream(uri)?.use { input ->
                                    target.outputStream().use { input.copyTo(it) }
                                }
                            }.isSuccess
                            if (ok) { AppState.applyBackgroundImage(target.absolutePath); bgImg = target.absolutePath; savedMsg = "背景图片已设置" }
                            else savedMsg = "图片保存失败"
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (bgImg.isNotBlank()) "背景图片已设置" else "自定义背景图片（全局）",
                        style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    GlassActionButton(onClick = { bgPicker.launch("image/*") }, modifier = Modifier.padding(end = 6.dp)) {
                        Text(if (bgImg.isNotBlank()) "更换" else "选择图片")
                    }
                    if (bgImg.isNotBlank()) {
                        TextButton(onClick = { AppState.applyBackgroundImage(""); bgImg = ""; savedMsg = "已恢复默认背景" }) {
                            Text("清除", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
                // ★ 说明: 设了壁纸后顶栏会自动去掉品牌绿渐变与不透明底色(见 Common.kt AppTopBar),
                //   用户不知道的话会以为顶栏"坏了" —— 明确写出来。
                Text(
                    if (bgImg.isNotBlank()) "已设置背景图：顶栏的品牌渐变会自动隐藏，让壁纸完整显示。"
                    else "设置背景图后，顶栏的品牌渐变会自动隐藏，让壁纸完整显示。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // ===== 底部导航 =====
            SectionCard("底部导航") {
                Text("选择要显示的底部 Tab。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                // ★ 「我的」**不出现在列表里** (2026-09-26 用户要求: 「点击我的那是能进入到设置的」)。
                //   它是设置页的稳定入口 —— 之前把它列出来给用户关, 关掉后设置页就进不去了,
                //   属于自锁。现在干脆不给这个选项 (Session.pinnedTabs 在出口也会过滤掉它,
                //   防止旧版本存下的历史数据把它藏起来)。
                listOf(
                    Routes.LIBRARY to "指令库",
                    Routes.FEED to "广场",
                    Routes.GENERATORS to "生成器",
                    Routes.AI to "AI",
                ).forEach { (route, label) ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        GlassSwitch(
                            checked = route !in AppState.hiddenTabs,
                            onCheckedChange = { AppState.toggleHiddenTab(route) },
                        )
                    }
                }
                if (AppState.hiddenTabs.isNotEmpty()) {
                    TextButton(onClick = { AppState.resetHiddenTabs() }) { Text("全部显示") }
                }
            }

            // ===== 首页模块 =====
            SectionCard("首页模块") {
                Text("关闭不需要的入口, 首页更清爽 (首页也可长按卡片快速隐藏)。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                // ★ 不设任何限制 (2026-09-26 用户要求: 「首页模块没必要限制吧，人家想关就关」)。
                //   全部关掉首页会显示"入口都被隐藏了 / 点击这里恢复全部入口"的兜底卡片, 不会卡死。
                homeModules.forEach { m ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(m.title, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        GlassSwitch(
                            checked = m.title !in AppState.hiddenModules,
                            onCheckedChange = { AppState.toggleHiddenModule(m.title) },
                        )
                    }
                }
                if (AppState.hiddenModules.isNotEmpty()) {
                    TextButton(onClick = { AppState.resetHiddenModules() }) { Text("全部显示") }
                }
            }

            // ===== 液态玻璃 =====
            SectionCard("液态玻璃") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("液态玻璃效果", style = MaterialTheme.typography.bodyMedium)
                        Text("底部导航栏玻璃化 (开源库 backdrop)。需 Android 12+; 折射效果需 Android 13+; 若开启后启动闪退 2 次会自动关闭。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    GlassSwitch(checked = AppState.liquidGlass, onCheckedChange = { AppState.applyLiquidGlass(it) })
                }
                // 开关只改设置、外观要下次启动才变 —— 必须明确告诉用户, 否则会以为开关失灵。
                // (玻璃会一次性改变底栏/返回键/输入条/开关等一大批组件的外观, 立即切换太突兀)
                val glassPending = AppState.liquidGlass != AppState.glassActiveNow
                Text(
                    when {
                        glassPending && AppState.liquidGlass -> "已保存：重启 App 后开启（避免界面突然整体切换）。"
                        glassPending -> "已保存：重启 App 后关闭。"
                        AppState.liquidGlass -> "已开启。低端机如卡顿可关闭。"
                        else -> "默认关闭。"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (glassPending) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (glassPending) {
                    GlassActionButton(onClick = { restartApp(ctx) }) { Text("立即重启") }
                }
            }

            SectionHeader("功能与权限")

            // ===== 输入法模式 =====
            SectionCard("输入法模式") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (imeEnabled) Icons.Default.CheckCircle else Icons.Default.Warning, null,
                        tint = if (imeEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(if (imeEnabled) "CyanCodex 指令输入法已启用" else "输入法未启用, 需要在系统设置中开启",
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (imeEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                }
                Text("启用后, 游戏聊天框内切换到此输入法即可一键插入指令。", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("说明: 输入法模式就是把 CyanCodex 注册成手机系统输入法(类似搜狗/百度), 在游戏聊天框长按输入框→切换输入法→选「CyanCodex 指令输入法」即可使用; 因需系统级注册, 所以放在设置里启用。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    GlassActionButton(filled = true, onClick = {
                        ctx.startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
                    }, modifier = Modifier.weight(1f)) { Text("去系统设置启用") }
                    GlassActionButton(onClick = {
                        ctx.startActivity(Intent(ctx, CyanCodexImeSettings::class.java))
                    }, modifier = Modifier.weight(1f)) { Text("管理快捷指令($quickCount)") }
                }
            }

            // ===== 悬浮窗 =====
            SectionCard("悬浮窗 (游戏内快捷复制)") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (canOverlay) Icons.Default.CheckCircle else Icons.Default.Warning, null,
                        tint = if (canOverlay) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(if (canOverlay) "悬浮窗权限已授予" else "需要「显示在其他应用上层」权限",
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (canOverlay) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                }
                Text(if (floatRunning) "悬浮窗运行中" else "悬浮窗未运行", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (!canOverlay) {
                        GlassActionButton(filled = true, onClick = {
                            ctx.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:${ctx.packageName}")))
                        }, modifier = Modifier.weight(1f)) { Text("去授权") }
                    } else if (!floatRunning) {
                        GlassActionButton(filled = true, onClick = {
                            FloatWindowService.start(ctx)
                            floatRunning = true
                        }, modifier = Modifier.weight(1f)) { Text("开启悬浮窗") }
                    } else {
                        GlassActionButton(onClick = {
                            FloatWindowService.stop(ctx)
                            floatRunning = false
                        }, modifier = Modifier.weight(1f)) { Text("停止悬浮窗") }
                    }
                }
                Text("使用: 授权后开启悬浮窗, 游戏内点悬浮球即可复制指令。", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("无法显示? 卓易通/双开等虚拟环境请到系统设置→应用→本应用→「显示在其他应用上层」再次确认授权; 若游戏全屏看不到, 可先在普通桌面开启悬浮窗再进游戏。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)

                // ===== 悬浮窗调节 (对标 BNM) =====
                if (canOverlay) {
                    Spacer(Modifier.height(8.dp))
                    Text("悬浮窗调节", style = MaterialTheme.typography.titleSmall)
                    // 悬浮球大小
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("悬浮球大小", style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.weight(1f))
                        listOf("小" to 44, "中" to 52, "大" to 64).forEach { (label, dp) ->
                            GlassFilterChip(
                                selected = Session.floatBallSizeDp == dp,
                                onClick = { Session.floatBallSizeDp = dp },
                                label = { Text(label, style = MaterialTheme.typography.labelSmall) },
                                modifier = Modifier.padding(end = 4.dp),
                            )
                        }
                    }
                    // 悬浮窗大小
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("悬浮窗大小", style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.weight(1f))
                        listOf("小" to (220 to 380), "中" to (300 to 520), "大" to (400 to 680)).forEach { (label, wh) ->
                            GlassFilterChip(
                                selected = Session.floatWidthDp == wh.first,
                                onClick = {
                                    Session.floatWidthDp = wh.first
                                    Session.floatHeightDp = wh.second
                                },
                                label = { Text(label, style = MaterialTheme.typography.labelSmall) },
                                modifier = Modifier.padding(end = 4.dp),
                            )
                        }
                    }
                    // 透明度
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("透明度 ${(Session.floatOpacity * 100).toInt()}%", style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.weight(1f))
                        Slider(
                            value = Session.floatOpacity,
                            onValueChange = { Session.floatOpacity = it },
                            valueRange = 0.2f..1f,
                            modifier = Modifier.weight(1.6f),
                        )
                    }
                    Text("下次开启悬浮窗时生效（运行中可在悬浮窗内 ⚙ 实时调节）",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            // ===== 消息通知 =====
            SectionCard("消息通知") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (notifEnabled) Icons.Default.CheckCircle else Icons.Default.Warning, null,
                        tint = if (notifEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(if (notifEnabled) "消息推送已开启" else "消息推送未开启，新公告/站内信将无法提醒",
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (notifEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                }
                Text("新消息提醒为静默推送：只在通知栏显示，不会弹横幅、不会响铃或震动。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (!notifEnabled) {
                        GlassActionButton(filled = true, onClick = { notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) },
                            modifier = Modifier.weight(1f)) { Text("申请消息推送权限") }
                    } else {
                        GlassActionButton(onClick = { openNotificationSettings(ctx) },
                            modifier = Modifier.weight(1f)) { Text("通知设置") }
                    }
                }

                HorizontalDivider(Modifier.padding(vertical = 4.dp))

                // ---- 后台常驻收消息 (v1.0.91) ----
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("后台常驻接收消息", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            if (bgWatch) "已开启 · 每 ${Session.watchIntervalSec} 秒检查一次，退出 App 也能及时收到"
                            else "已关闭 · 只有打开 App 时才会收到新消息",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    GlassSwitch(checked = bgWatch, onCheckedChange = { on ->
                        bgWatch = on
                        Session.backgroundWatch = on
                        if (on) {
                            com.canglan.cyancodex.data.MessageWatchService.start(ctx)
                            com.canglan.cyancodex.data.MessageWatchService.ensureChannel(ctx)
                        } else {
                            com.canglan.cyancodex.data.MessageWatchService.stop(ctx)
                        }
                    })
                }
                Text("开启后状态栏会有一条常驻通知（这是安卓要求的前台服务提示，本身也是静默的）。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)

                HorizontalDivider(Modifier.padding(vertical = 4.dp))

                // ---- AI 回答保活 (2026-10-05) ----
                // 用户反馈「AI 任务一切后台就断」。机制上是"进程级协程 + 前台服务"两层，
                // 但**通知权限被关时前台服务通知不显示**，用户会以为没有保活。
                // 这里如实说清楚它长什么样、能干什么，并给一个直接的补救入口。
                Text("AI 回答时后台保活", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "AI 回答期间状态栏会显示一条「AI 正在回答」通知（带计时器）："
                        + "它就是保活的凭证，切到别的应用也能让回答跑完，完成后再通知你。"
                        + "不想等了可以在通知里直接点「停止生成」，已写出的内容会保留。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (!notifEnabled) {
                    Text(
                        "⚠ 通知权限未开启：保活仍在工作，但通知栏看不到这条提示，也收不到「回答好了」提醒。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (ignoringBattery) Icons.Default.CheckCircle else Icons.Default.Warning, null,
                        tint = if (ignoringBattery) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        if (ignoringBattery) "已允许后台运行（电池优化白名单）"
                        else "建议允许后台运行，否则部分手机锁屏后会收不到消息",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (ignoringBattery) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    GlassActionButton(onClick = { openBatterySettings(ctx) },
                        modifier = Modifier.weight(1f)) { Text("关闭电池优化") }
                    GlassActionButton(onClick = { openAutoStartSettings(ctx) },
                        modifier = Modifier.weight(1f)) { Text("自启动设置") }
                }
            }

            // ===== 插件 (开发平台) =====
            SectionHeader("扩展与安全")

            SectionCard("插件 (开发平台)") {
                Text("插件是独立打包的 JAR, 可扩展 App 功能; 插件默认关闭, 打开开关才生效。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                plugins.forEach { lp ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text("${lp.plugin.name} v${lp.plugin.version}", style = MaterialTheme.typography.bodyMedium)
                            Text(lp.plugin.description, style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        GlassSwitch(checked = lp.enabled, onCheckedChange = {
                            com.canglan.cyancodex.plugin.PluginManager.setEnabled(ctx, lp.plugin.id, it)
                            plugins = com.canglan.cyancodex.plugin.PluginManager.list()
                        })
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    GlassActionButton(onClick = { nav.navigate(Routes.PLUGIN_DEV) }, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.Extension, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("开发平台")
                    }
                    GlassActionButton(onClick = {
                        com.canglan.cyancodex.plugin.PluginManager.reload(ctx)
                        plugins = com.canglan.cyancodex.plugin.PluginManager.list()
                        savedMsg = "插件已重新加载"
                    }, modifier = Modifier.weight(1f)) { Text("重新加载") }
                }
                Text("插件目录: ${com.canglan.cyancodex.plugin.PluginManager.pluginDir(ctx).absolutePath}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            // ===== 两步验证 (身份验证器 TOTP) =====
            SectionCard("两步验证 (身份验证器)") {
                var totpEnabled by remember { mutableStateOf(false) }
                var setup by remember { mutableStateOf<com.canglan.cyancodex.data.TotpSetup?>(null) }
                var totpInput by remember { mutableStateOf("") }
                var totpMsg by remember { mutableStateOf("") }
                var totpBusy by remember { mutableStateOf(false) }
                // 服务端要求先验证当前登录密码才下发密钥 (防止令牌泄露者抢先绑定 2FA)
                var askPwd by remember { mutableStateOf(false) }
                var totpPwd by remember { mutableStateOf("") }
                LaunchedEffect(Unit) {
                    val r = safeApi { ApiClient.get().totpStatus() }
                    if (r.ok) totpEnabled = r.data?.enabled ?: false
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (totpEnabled) Icons.Default.CheckCircle else Icons.Default.Warning, null,
                        tint = if (totpEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(if (totpEnabled) "两步验证已开启" else "两步验证未开启",
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (totpEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                }
                Text("开启后登录需输入身份验证器（Google Authenticator 等）的 6 位验证码，秘钥由服务器密钥派生。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (!totpEnabled) {
                    GlassActionButton(filled = true, onClick = { askPwd = true; totpPwd = ""; totpMsg = "" },
                        enabled = !totpBusy, modifier = Modifier.fillMaxWidth()) { Text("开启两步验证") }
                } else {
                    GlassActionButton(onClick = {
                        setup = com.canglan.cyancodex.data.TotpSetup(); totpInput = ""; totpMsg = "输入验证码后关闭"
                    }, modifier = Modifier.fillMaxWidth()) { Text("关闭两步验证") }
                }
                if (totpMsg.isNotBlank()) Text(totpMsg, color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall)
                // 开启两步验证前: 先输入当前密码做二次验证
                if (askPwd) {
                    AlertDialog(
                        onDismissRequest = { askPwd = false },
                        title = { Text("验证当前密码") },
                        text = {
                            Column {
                                Text("为确认是本人操作，请先输入当前登录密码。",
                                    style = MaterialTheme.typography.bodySmall)
                                Spacer(Modifier.height(8.dp))
                                OutlinedTextField(
                                    value = totpPwd,
                                    onValueChange = { totpPwd = it },
                                    label = { Text("当前密码") },
                                    singleLine = true,
                                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                                )
                            }
                        },
                        confirmButton = {
                            TextButton(onClick = {
                                if (totpPwd.isBlank()) { totpMsg = "请输入当前密码"; return@TextButton }
                                scope.launch {
                                    totpBusy = true; totpMsg = ""
                                    val r = safeApi { ApiClient.get().totpSetup(mapOf("password" to totpPwd)) }
                                    totpBusy = false
                                    askPwd = false; totpPwd = ""
                                    if (r.ok) { setup = r.data; totpInput = "" } else totpMsg = r.error ?: "获取失败"
                                }
                            }, enabled = !totpBusy) { Text("确认") }
                        },
                        dismissButton = { TextButton(onClick = { askPwd = false; totpPwd = "" }) { Text("取消") } },
                    )
                }
                setup?.let { s ->
                    AlertDialog(
                        onDismissRequest = { setup = null },
                        title = { Text(if (totpEnabled) "关闭两步验证" else "开启两步验证") },
                        text = {
                            Column {
                                if (!totpEnabled) {
                                    Text("在身份验证器中添加以下密钥（或复制 otpauth 链接手动添加），然后输入验证码确认：",
                                        style = MaterialTheme.typography.bodySmall)
                                    Spacer(Modifier.height(6.dp))
                                    Text(s.secret, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                        color = MaterialTheme.colorScheme.primary)
                                    Spacer(Modifier.height(4.dp))
                                    Text(s.otpauthUri, style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Spacer(Modifier.height(8.dp))
                                OutlinedTextField(value = totpInput, onValueChange = { totpInput = it },
                                    label = { Text("6 位验证码") }, singleLine = true)
                            }
                        },
                        confirmButton = {
                            TextButton(onClick = {
                                scope.launch {
                                    totpBusy = true
                                    val r = if (totpEnabled)
                                        safeApi { ApiClient.get().totpDisable(mapOf("code" to totpInput.trim())) }
                                    else safeApi { ApiClient.get().totpVerify(mapOf("code" to totpInput.trim())) }
                                    totpBusy = false
                                    if (r.ok) {
                                        totpEnabled = !totpEnabled
                                        setup = null; totpInput = ""; totpMsg = ""
                                        savedMsg = if (totpEnabled) "两步验证已开启" else "两步验证已关闭"
                                    } else totpMsg = r.error ?: "验证码错误"
                                }
                            }, enabled = !totpBusy) { Text(if (totpEnabled) "关闭" else "确认开启") }
                        },
                        dismissButton = { TextButton(onClick = { setup = null }) { Text("取消") } },
                    )
                }
            }

            if (savedMsg.isNotBlank()) {
                Text(savedMsg, color = MaterialTheme.colorScheme.secondary, style = MaterialTheme.typography.bodySmall)
            }

            // ===== 数据与备份 =====
            //
            // 二改独有: 把本机全部设置加密后备份到自建服务 (cyancodex.bluestudio.cn)。
            // 加密在设备上用「备份码」派生的密钥完成, 服务端只存密文 —— 见 SettingsBackup 类注释。
            SectionHeader("数据与备份")

            SectionCard("设置云端备份") {
                Text("把本机全部设置打包加密后上传，换机或重装时用备份码恢复。加密在设备上完成，服务器只保存密文，看不到你的任何内容。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                SettingEntry(
                    icon = Icons.Default.CloudUpload,
                    title = "备份设置到云端",
                    subtitle = run {
                        backupStamp   // 备份成功后自增, 用于触发重组刷新副标题
                        val t = SettingsBackup.lastTime(ctx)
                        if (t <= 0L) "尚未备份过"
                        else "上次备份 " + java.text.SimpleDateFormat(
                            "yyyy-MM-dd HH:mm", java.util.Locale.getDefault()
                        ).format(java.util.Date(t * 1000))
                    },
                ) { showBackup = true }
                SettingEntry(
                    icon = Icons.Default.CloudDownload,
                    title = "用备份码恢复",
                    subtitle = "输入备份码，恢复设置后自动重启",
                ) { showRestore = true }
            }

            SectionHeader("关于与支持")

            // ===== 开源组件声明 =====
            SectionCard("开源组件声明") {
                Text("本 App 使用了多个第三方开源组件（含液态玻璃效果库）。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                SettingEntry(
                    icon = Icons.Default.Code,
                    title = "查看开源组件声明",
                    subtitle = "组件清单、版本、许可证与源码地址",
                ) { nav.navigate(Routes.OPEN_SOURCE) }
            }

            // ===== 关于与帮助 =====
            SectionCard("关于与帮助") {
                SettingEntry(
                    icon = Icons.Default.Info,
                    title = "关于 CyanCodex",
                    subtitle = "版本信息 · 用户协议 · 隐私协议 · 更新日志",
                ) { nav.navigate(Routes.ABOUT) }
                SettingEntry(
                    icon = Icons.Default.Feedback,
                    title = "意见申诉",
                    subtitle = "反馈问题、提出建议或申诉",
                ) { nav.navigate(Routes.FEEDBACK) }
            }

            Text("世纪集团旗下 · 沧蓝工坊工作室 · CyanCodex v${com.canglan.cyancodex.BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))

            // ===== 调试面板入口 =====
            // 可见条件 = 已解锁 **且 总开关开着** (见上面 [debugVisible] 的注释)。
            // 关掉总开关 → 这里整块消失, 与安卓「开发者选项」一致。
            if (debugVisible) {
                SectionHeader("调试")
                SectionCard("调试") {
                    Text("服务器地址 / 日志 / 网络请求记录",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    SettingEntry(
                        icon = Icons.Default.BugReport,
                        title = "调试面板",
                        subtitle = "改服务器地址(含端口) · 日志开关与查看 · 请求记录",
                    ) { nav.navigate(Routes.DEBUG) }
                }
            }
        }

        if (showBackup) {
            BackupDialog(
                onDismiss = { showBackup = false },
                onDone = { backupStamp = System.currentTimeMillis() },
            )
        }
        if (showRestore) {
            RestoreDialog(onDismiss = { showRestore = false })
        }
    }
}

/**
 * 备份对话框。
 *
 * 备份码是**唯一**的解密凭证 —— 服务端零知识, 丢了谁也救不回来, 所以这里
 * 把"请立即保存"写得非常显眼, 并给了复制按钮。
 */
@Composable
private fun BackupDialog(onDismiss: () -> Unit, onDone: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var code by remember { mutableStateOf(SettingsBackup.newCode()) }
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf("") }
    var err by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("备份设置到云端") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "备份码是恢复设置的唯一凭证，请立即保存到安全的地方。忘记备份码将无法恢复。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                OutlinedTextField(
                    value = code,
                    onValueChange = {},
                    readOnly = true,
                    singleLine = true,
                    label = { Text("备份码") },
                    trailingIcon = {
                        IconButton(onClick = {
                            val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE)
                                as android.content.ClipboardManager
                            cm.setPrimaryClip(android.content.ClipData.newPlainText("备份码", code))
                            msg = "已复制到剪贴板"
                        }) { Icon(Icons.Default.ContentCopy, contentDescription = "复制") }
                    },
                )
                if (msg.isNotBlank()) {
                    Text(msg, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary)
                }
                if (err.isNotBlank()) {
                    Text(err, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(enabled = !busy, onClick = {
                scope.launch {
                    busy = true; err = ""; msg = ""
                    val r = withContext(Dispatchers.IO) {
                        runCatching { SettingsBackup.backup(ctx, code) }
                    }
                    busy = false
                    r.onSuccess {
                        msg = "备份成功（%d 项设置，%.1f KB）".format(
                            it.keyCount, it.sizeBytes / 1024.0)
                        onDone()
                    }.onFailure { err = it.message ?: "备份失败" }
                }
            }) { Text(if (busy) "备份中…" else "开始备份") }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("关闭") } },
    )
}

/**
 * 恢复对话框。恢复会**覆盖本机现有设置**, 所以文案里明确写清楚, 且必须二次点击确认。
 */
@Composable
private fun RestoreDialog(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var code by remember { mutableStateOf(SettingsBackup.lastCode(ctx)) }
    var busy by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("用备份码恢复设置") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "恢复会用云端备份覆盖本机现有设置（账号、AI 配置、快捷指令、主题等），完成后 App 会自动重启。",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(
                    value = code,
                    onValueChange = { code = it; err = "" },
                    singleLine = true,
                    label = { Text("备份码") },
                    placeholder = { Text("XXXX-XXXX-XXXX-XXXX") },
                    isError = err.isNotBlank(),
                )
                if (err.isNotBlank()) {
                    Text(err, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(enabled = !busy && code.isNotBlank(), onClick = {
                scope.launch {
                    busy = true; err = ""
                    val r = withContext(Dispatchers.IO) {
                        runCatching { SettingsBackup.restore(ctx, code) }
                    }
                    busy = false
                    r.onSuccess { restartApp(ctx) }
                        .onFailure { err = it.message ?: "恢复失败" }
                }
            }) { Text(if (busy) "恢复中…" else "恢复并重启") }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("取消") } },
    )
}

/**
 * 重启 App —— 给"改了要重启才生效"的设置(目前是液态玻璃开关)一个一键入口,
 * 免得用户得自己去最近任务里划掉再点开。
 *
 * 姿势: 用官方 `Intent.makeRestartActivityTask`(等价 NEW_TASK|CLEAR_TASK)清空任务栈、
 * 重建入口 Activity。MainActivity.onCreate 会调 AppState.refreshGlassAtLaunch(),
 * 于是新设置被应用上 —— 界面原地重新加载, 效果等同于重启。
 *
 * ⚠️ 为什么**不用** killProcess(这条弯路走过, 实测翻车):
 *   startActivity 只是把请求异步交给 system_server, 立刻自杀有概率抢在 ActivityTaskManager
 *   调度之前; 更糟的是当时还带了 FLAG_ACTIVITY_CLEAR_TOP —— 它让 AMS **复用**旧任务栈里的
 *   MainActivity 实例而不新建, 进程一死任务就空了, 结果是**掉回桌面**而不是重启。
 *   而且真要"杀进程再拉活"就得靠 AlarmManager 兜底, 那又依赖精确闹钟权限(Android 14 起默认不授予),
 *   失败代价是用户点了重启却再也打不开 App —— 风险远大于收益。
 */
private fun restartApp(ctx: android.content.Context) {
    val app = ctx.applicationContext
    val launch = app.packageManager.getLaunchIntentForPackage(app.packageName) ?: return
    val comp = launch.component ?: return
    app.startActivity(android.content.Intent.makeRestartActivityTask(comp))
}

@Composable
private fun SectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            content()
        }
    }
}

/** 设置分组标题 —— 把整页拆成「性能与存储 / 外观与交互 / 功能与权限 / 扩展与安全 / 关于与支持」几块。 */
@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 4.dp, top = 6.dp),
    )
}

/** 可点击的设置入口行：图标 + 标题 + 副标题 + 右箭头。 */
@Composable
private fun SettingEntry(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(subtitle, style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
    }
}

private fun isImeEnabled(ctx: android.content.Context): Boolean {
    val imm = ctx.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as InputMethodManager
    // 用 ComponentName 精确比对，而不是手工拼 "包名/.类名" 字符串。
    // 原因: 系统给出的 IME id 形如 "<applicationId>/<完整类名>", 且只有在
    // 完整类名以 applicationId 开头时才会被缩写成 "<包名>/.类名"。
    // 共存版 applicationId=com.canglan.cyancodex.com, 而类仍在命名空间
    // com.canglan.cyancodex.ime 下, 不满足缩写条件 → 手工拼的短名永远匹配不上。
    val cn = android.content.ComponentName(ctx, com.canglan.cyancodex.ime.CyanCodexIme::class.java)
    return imm.enabledInputMethodList.any { it.component == cn }
}

private fun openNotificationSettings(ctx: Context) {
    try {
        ctx.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName))
    } catch (e: Exception) {
        runCatching { ctx.startActivity(Intent(Settings.ACTION_SETTINGS)) }
    }
}

// ===== 自定义主题色选择器 (HSV: 色相/饱和度/明度 + 预设色) =====

private val THEME_PRESET_COLORS = listOf(
    "#4ADE80", "#16A34A", "#38BDF8", "#0EA5E9", "#FBBF24", "#F59E0B",
    "#F472B6", "#C084FC", "#A78BFA", "#F87171", "#2DD4BF", "#94A3B8",
)

@Composable
private fun ThemeColorPicker(hex: String, onChange: (String) -> Unit) {
    val init = remember {
        FloatArray(3).also { runCatching { android.graphics.Color.colorToHSV(android.graphics.Color.parseColor(hex), it) } }
    }
    var hue by remember { mutableStateOf(init[0]) }
    var sat by remember { mutableStateOf(init[1]) }
    var bri by remember { mutableStateOf(init[2]) }

    fun emit() {
        val c = android.graphics.Color.HSVToColor(floatArrayOf(hue, sat, bri))
        onChange(String.format("#%06X", 0xFFFFFF and c))
    }

    val preview = Color(android.graphics.Color.HSVToColor(floatArrayOf(hue, sat, bri)))

    Column(Modifier.padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(Modifier.fillMaxWidth().height(40.dp).clip(RoundedCornerShape(10.dp)).background(preview))
        Text("色相", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Slider(value = hue, onValueChange = { hue = it; emit() }, valueRange = 0f..360f)
        Text("饱和度", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Slider(value = sat, onValueChange = { sat = it; emit() }, valueRange = 0f..1f)
        Text("明度", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Slider(value = bri, onValueChange = { bri = it; emit() }, valueRange = 0.15f..1f)
        Text("预设颜色", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        THEME_PRESET_COLORS.chunked(6).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { hx ->
                    val swatch = remember(hx) {
                        runCatching { Color(android.graphics.Color.parseColor(hx)) }.getOrDefault(Color.Gray)
                    }
                    Box(
                        Modifier.size(30.dp).clip(CircleShape).background(swatch).clickable {
                            val hsv = FloatArray(3)
                            runCatching { android.graphics.Color.colorToHSV(android.graphics.Color.parseColor(hx), hsv) }
                            hue = hsv[0]; sat = hsv[1]; bri = hsv[2]; emit()
                        }
                    )
                }
            }
        }
    }
}

/** 是否已在电池优化白名单里 (不在 = 系统可能随时杀掉后台) */
private fun isIgnoringBattery(ctx: Context): Boolean = try {
    val pm = ctx.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
    pm.isIgnoringBatteryOptimizations(ctx.packageName)
} catch (e: Exception) { false }

/** 申请加入电池优化白名单 (系统弹窗直接授权) */
@android.annotation.SuppressLint("BatteryLife")
private fun openBatterySettings(ctx: Context) {
    try {
        ctx.startActivity(
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                .setData(android.net.Uri.parse("package:${ctx.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    } catch (e: Exception) {
        runCatching {
            ctx.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}

/** 跳自启动管理: 国产系统各家页面不同, 能跳就跳, 跳不了就落到应用详情页 */
private fun openAutoStartSettings(ctx: Context) {
    val candidates = listOf(
        "com.huawei.systemmanager/.startupmgr.ui.StartupNormalAppListActivity",
        "com.xiaomi.miui.securitycenter/com.miui.powercenter.bootstart.BootStartActivity",
        "com.coloros.safecenter/.startupapp.StartupAppListActivity",
        "com.oppo.safe/.permission.startup.StartupAppListActivity",
        "com.vivo.permissionmanager/.activity.BgStartUpManagerActivity",
        "com.iqoo.secure/.safeguard.PurviewTabActivity",
        "com.meizu.safe/.permission.SmartBGActivity",
        "com.samsung.android.lool/com.samsung.android.sm.ui.battery.BatteryActivity",
    )
    for (c in candidates) {
        val parts = c.split("/")
        try {
            ctx.startActivity(Intent().setClassName(parts[0], parts[1]).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        } catch (e: Exception) { /* 下一个 */ }
    }
    runCatching {
        ctx.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(android.net.Uri.parse("package:${ctx.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}
