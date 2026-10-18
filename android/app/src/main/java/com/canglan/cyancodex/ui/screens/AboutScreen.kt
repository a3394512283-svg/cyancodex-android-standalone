package com.canglan.cyancodex.ui.screens

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Gavel
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PrivacyTip
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.canglan.cyancodex.BuildConfig
import com.canglan.cyancodex.data.Announcement
import com.canglan.cyancodex.data.ApiClient
import com.canglan.cyancodex.data.DebugStore
import com.canglan.cyancodex.data.Updater
import com.canglan.cyancodex.ui.Routes
import com.canglan.cyancodex.ui.components.AppTopBar
import com.canglan.cyancodex.ui.components.GlassActionButton
import com.canglan.cyancodex.ui.components.safeApi
import com.canglan.cyancodex.ui.components.versionNewer
import kotlinx.coroutines.launch

const val QQ_GROUP_NUMBER = "1065025874"
const val QQ_GROUP_URL = "https://qun.qq.com/universal-share/share?ac=1&authKey=hwdWfjhcrT2gyREXBglanWfdHTMjrjUvEvVSSg8%2FVGx8E10%2FEVdfp3H0OJPPTPdi&busi_data=eyJncm91cENvZGUiOiIxMDY1MDI1ODc0IiwidG9rZW4iOiJiZ0JFVG9Uakc3ZDcrZkZyK0cxVStZOEErYU1Ja1o5S1ZjUFlYM0padVdsVUNGKytrZ1lidvkrakZ5YjhmbWR4IiwidWluIjoiMzYxNTc4NTc2OSJ9&data=ZezSPJyBUEcAwSLrXEOkeiTVTvEZK8FRMEkY2BQdfpP_GIPfHokXrTLjcvEzf7ZehLL-nD00FXXW5z7Z2chqZA&svctype=4&tempid=h5_group_info"
// 赞助链接 (爱发电)
const val SPONSOR_URL = "https://ifdian.net/a/canglanworkshop"
// GitHub 开源仓库
const val GITHUB_REPO_URL = "https://github.com/Diamond-Minec/CyanCodex-Android"

/** 打开 QQ 群: 直接唤起 QQ 并在其内置浏览器加载群邀请链接 (不经过外部浏览器) */
fun joinQqGroup(context: android.content.Context) {
    // mqqapi://forward/url 让 QQ 内置浏览器直接打开群邀请链接
    val encoded = Uri.encode(QQ_GROUP_URL)   // ≈ encodeURIComponent
    val qq = Intent(Intent.ACTION_VIEW, Uri.parse("mqqapi://forward/url?url_prefix=$encoded&src_type=web&version=1"))
    qq.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    if (qq.resolveActivity(context.packageManager) != null) {
        runCatching { context.startActivity(qq) }
            .onFailure { openWebFallback(context) }
    } else {
        openWebFallback(context)
    }
}

private fun openWebFallback(context: android.content.Context) {
    val web = Intent(Intent.ACTION_VIEW, Uri.parse(QQ_GROUP_URL))
    runCatching { context.startActivity(web) }
        .onFailure { Toast.makeText(context, "无法打开链接", Toast.LENGTH_SHORT).show() }
}

/** QQ 群卡片 (首页/关于页共用) */
@Composable
fun QqGroupCard(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    Card(onClick = { joinQqGroup(ctx) }, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = modifier.fillMaxWidth()) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Groups, null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("加入我们的QQ群", style = MaterialTheme.typography.titleSmall)
                Text("群号: $QQ_GROUP_NUMBER · 点击加入，一起交流指令", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(nav: NavHostController) {
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    var showLicenseDialog by remember { mutableStateOf(false) }

    // ===== 隐藏调试模式：连点版本号 7 次解锁 =====
    // 已解锁后再点 → 直接进调试面板（不用再点 7 次）
    var tapCount by remember { mutableStateOf(0) }
    var tapHint by remember { mutableStateOf("") }

    fun onVersionTap() {
        // ★★ 2026-10-05: 总开关关着时的**再开启兜底**。
        //
        // 为什么必须有: 现在设置页的「调试」入口跟随总开关显示(见 SettingsScreen 的 debugVisible),
        // 开关一关入口就没了。若这里不做点什么, 用户就**再也找不到回去的路**。
        // 这与安卓「开发者选项」的行为一致 —— 关掉之后, 再连点版本号还能重新打开。
        // 这里简化成"点一下就重开", 因为此时用户显然已经知道这个入口存在。
        if (DebugStore.unlocked && !DebugStore.masterOn) {
            DebugStore.masterOn = true
            DebugStore.logEnabled = true
            DebugStore.logI("Debug", "调试模式已重新开启 (关于页点版本号)")
            Toast.makeText(ctx, "调试模式已重新开启", Toast.LENGTH_SHORT).show()
            nav.navigate(Routes.DEBUG)
            return
        }
        if (DebugStore.unlocked) {
            nav.navigate(Routes.DEBUG)
            return
        }
        tapCount++
        if (tapCount >= 7) {
            DebugStore.unlocked = true
            // ★ 解锁的同时把总开关也打开 —— 否则"解锁了但入口还是不显示",
            //   因为入口判的是 activeState(解锁 且 总开关开着)。
            DebugStore.masterOn = true
            DebugStore.logEnabled = true
            DebugStore.logI("Debug", "调试模式已解锁 (连点版本号 7 次)")
            tapCount = 0
            tapHint = "调试模式已解锁"
            Toast.makeText(ctx, "调试模式已解锁，已跳转调试面板", Toast.LENGTH_LONG).show()
            nav.navigate(Routes.DEBUG)
        } else if (tapCount >= 4) {
            // 第 4 次起给提示，别让用户点了 6 次还不知道自己在干嘛
            tapHint = "还差 ${7 - tapCount} 次解锁调试模式"
        }
    }

    fun checkUpdate() {
        // ★ 2026-10-18: 更新从 AlertDialog 改成**独立的整屏页面**（Routes.UPDATE）。
        //   原对话框只能显示一行「下载中 42%」，弱网下体验很差；页面里有真实的
        //   字节进度、版本卡与分块更新日志，观感对齐 OriginOS 的「系统升级」。
        nav.navigate(Routes.UPDATE)
    }

    Scaffold(
        topBar = {
            AppTopBar(title = "关于 CyanCodex", icon = Icons.Default.Info, onBack = { nav.popBackStack() })
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            Image(
                painter = androidx.compose.ui.res.painterResource(com.canglan.cyancodex.R.drawable.usericon),
                contentDescription = "CyanCodex",
                modifier = Modifier.size(80.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(18.dp)),
            )
            Text("CyanCodex", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary)
            Text("v${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.clickable { onVersionTap() })
            if (tapHint.isNotBlank()) {
                Text(tapHint, style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 2.dp))
            }
            Spacer(Modifier.height(12.dp))
            // 世纪集团旗下 (收录合作方)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Image(
                    painter = androidx.compose.ui.res.painterResource(com.canglan.cyancodex.R.drawable.shiji),
                    contentDescription = "世纪集团",
                    modifier = Modifier.size(28.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(6.dp)),
                )
                Spacer(Modifier.width(8.dp))
                Text("世纪集团旗下", style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(6.dp))
            // 矩阵方块协助开发
            Row(verticalAlignment = Alignment.CenterVertically) {
                Image(
                    painter = androidx.compose.ui.res.painterResource(com.canglan.cyancodex.R.drawable.jzfk),
                    contentDescription = "矩阵方块",
                    modifier = Modifier.size(28.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(6.dp)),
                )
                Spacer(Modifier.width(8.dp))
                Text("矩阵方块协助开发", style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(6.dp))
            // 沧蓝工坊 (logo)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Image(
                    painter = androidx.compose.ui.res.painterResource(com.canglan.cyancodex.R.drawable.logo_canglan),
                    contentDescription = "沧蓝工坊",
                    modifier = Modifier.size(28.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(6.dp)),
                )
                Spacer(Modifier.width(8.dp))
                Text("沧蓝工坊", style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(4.dp))
            Text("由 沧蓝工坊工作室 开发", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(10.dp))
            GlassActionButton(onClick = { checkUpdate() }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Refresh, null, Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("检查更新")
            }
            Spacer(Modifier.height(16.dp))
            QqGroupCard()
            Spacer(Modifier.height(8.dp))
            // 赞助支持 (爱发电)
            Card(onClick = {
                val web = Intent(Intent.ACTION_VIEW, Uri.parse(SPONSOR_URL))
                runCatching { ctx.startActivity(web) }
                    .onFailure { Toast.makeText(ctx, "无法打开赞助链接", Toast.LENGTH_SHORT).show() }
            }, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Favorite, null, tint = Color(0xFFF87171))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("赞助支持", style = MaterialTheme.typography.titleSmall)
                        Text("请开发团队喝杯奶茶，支持项目持续更新", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            Spacer(Modifier.height(20.dp))

            // 开源与法律: GitHub 仓库 / 开源协议 GPLv3.0 / 隐私协议 / 更新日志
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("开源与法律", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                    // GitHub 仓库
                    ListItem(
                        headlineContent = { Text("GitHub 开源仓库", style = MaterialTheme.typography.bodyMedium) },
                        supportingContent = { Text("github.com/Diamond-Minec/CyanCodex-Android", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) },
                        leadingContent = {
                            Icon(Icons.Default.Code, null, tint = MaterialTheme.colorScheme.primary)
                        },
                        trailingContent = { Icon(Icons.Default.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        modifier = Modifier.clickable {
                            val web = Intent(Intent.ACTION_VIEW, Uri.parse(GITHUB_REPO_URL))
                            runCatching { ctx.startActivity(web) }
                                .onFailure { Toast.makeText(ctx, "无法打开链接", Toast.LENGTH_SHORT).show() }
                        },
                    )
                    // 开源协议 GPLv3.0
                    ListItem(
                        headlineContent = { Text("开源协议：GPLv3.0", style = MaterialTheme.typography.bodyMedium) },
                        supportingContent = { Text("本应用以 GNU GPLv3.0 协议开源", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) },
                        leadingContent = {
                            Icon(Icons.Default.Description, null, tint = MaterialTheme.colorScheme.primary)
                        },
                        trailingContent = { Icon(Icons.Default.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        modifier = Modifier.clickable { showLicenseDialog = true },
                    )
                    // 用户协议
                    ListItem(
                        headlineContent = { Text("用户协议", style = MaterialTheme.typography.bodyMedium) },
                        supportingContent = { Text("使用本应用需遵守的行为规范与约定", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) },
                        leadingContent = {
                            Icon(Icons.Default.Gavel, null, tint = MaterialTheme.colorScheme.primary)
                        },
                        trailingContent = { Icon(Icons.Default.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        modifier = Modifier.clickable { nav.navigate(com.canglan.cyancodex.ui.Routes.TERMS) },
                    )
                    // 隐私协议
                    ListItem(
                        headlineContent = { Text("隐私协议", style = MaterialTheme.typography.bodyMedium) },
                        supportingContent = { Text("查看我们如何收集与保护您的信息", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) },
                        leadingContent = {
                            Icon(Icons.Default.PrivacyTip, null, tint = MaterialTheme.colorScheme.primary)
                        },
                        trailingContent = { Icon(Icons.Default.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        modifier = Modifier.clickable { nav.navigate(com.canglan.cyancodex.ui.Routes.PRIVACY) },
                    )
                    // 更新日志
                    ListItem(
                        headlineContent = { Text("更新日志", style = MaterialTheme.typography.bodyMedium) },
                        supportingContent = { Text("查看各版本更新内容", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) },
                        leadingContent = {
                            Icon(Icons.Default.History, null, tint = MaterialTheme.colorScheme.primary)
                        },
                        trailingContent = { Icon(Icons.Default.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        modifier = Modifier.clickable { nav.navigate(com.canglan.cyancodex.ui.Routes.CHANGELOG) },
                    )
                }
            }
            Spacer(Modifier.height(16.dp))

            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(Modifier.padding(16.dp)) {
                    Text("产品定位", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                    Text("一站式我的世界指令创作、资源社区、快捷调用工具。", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(12.dp))
                    Text("功能模块", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                    Text("""· 指令库: 用户上传 + 平台资源(高质/低质分级, 不定时更新)
· 指令编辑器: 快捷编辑修改游戏指令
· 输入法模式: 指令接入输入法, 游戏内快捷调用
· 优化: App 性能优化
· T显生成器: 计分板/T显示指令
· 悬浮窗: 全局悬浮窗, 游戏内快速复制
· 穷举: 批量生成同构不同数据指令
· AI Agent: 智能编写复杂指令(付费/积分解锁, 支持自备 API)
· 音乐生成器: 命令方块音乐指令
· 滚动字幕: 公告/滚动字幕指令""", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(12.dp))
                    Text("高级能力支持付费或积分解锁。", style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.secondary)
                }
            }
            Spacer(Modifier.height(16.dp))
            Text("© 2026 世纪集团 · 沧蓝工坊工作室 · CyanCodex", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

    // GNU GPLv3.0 开源协议弹窗
    if (showLicenseDialog) {
        AlertDialog(
            onDismissRequest = { showLicenseDialog = false },
            title = { Text("GNU GPLv3.0 开源协议") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text("本应用（CyanCodex）以 GNU General Public License v3.0（GPLv3.0）协议开源发布。")
                    Spacer(Modifier.height(8.dp))
                    Text("你可以：自由使用、复制、修改、分发本软件及其源代码；将修改后的版本用于任何用途（包括商业用途）。")
                    Spacer(Modifier.height(8.dp))
                    Text("但你必须：保留原始版权声明与许可声明；以相同协议（GPLv3.0）发布修改后的源代码；修改后的版本同样必须开源，且允许他人以相同条款使用。")
                    Spacer(Modifier.height(8.dp))
                    Text("本软件按“原样”提供，不附带任何明示或默示的担保。更多条款详见 GitHub 仓库中的 LICENSE 文件。")
                    Spacer(Modifier.height(12.dp))
                    GlassActionButton(onClick = {
                        showLicenseDialog = false
                        val web = Intent(Intent.ACTION_VIEW, Uri.parse(GITHUB_REPO_URL))
                        runCatching { ctx.startActivity(web) }
                            .onFailure { Toast.makeText(ctx, "无法打开链接", Toast.LENGTH_SHORT).show() }
                    }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.Code, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("查看 GitHub 仓库")
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showLicenseDialog = false }) { Text("知道了") } },
        )
    }

    // 发现新版本的弹窗已移除：更新改走独立整屏页面 Routes.UPDATE（见 UpdateScreen.kt）
}
