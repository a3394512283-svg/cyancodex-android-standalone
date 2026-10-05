package com.canglan.cyancodex.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.Casino
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Feedback
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PictureInPicture
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Widgets
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.canglan.cyancodex.data.ApiClient
import com.canglan.cyancodex.data.Announcement
import com.canglan.cyancodex.ui.AppState
import com.canglan.cyancodex.ui.LocalGlassBottomInset
import com.canglan.cyancodex.ui.Routes
import com.canglan.cyancodex.ui.components.safeApi
import kotlinx.coroutines.launch

internal data class ModuleEntry(
    val title: String,
    val icon: ImageVector,
    val tint: Color,
    val desc: String,
    val route: String,
)

// 首页模块清单 (internal: 设置页「首页模块」开关也要用到)
internal val homeModules = listOf(
    ModuleEntry("指令库", Icons.Default.Book, Color(0xFF4ADE80), "平台+用户上传", Routes.LIBRARY),
    ModuleEntry("工具箱", Icons.Default.Widgets, Color(0xFF2DD4BF), "生成器 / MIDI / 网络测速", Routes.GENERATORS),
    ModuleEntry("快捷查询ID", Icons.Default.Search, Color(0xFF2DD4BF), "输入中文查基岩版 ID", Routes.MC_ID),
    ModuleEntry("网络测速", Icons.Default.Speed, Color(0xFF38BDF8), "延迟/下载/上传", Routes.SPEED_TEST),
    ModuleEntry("MIDI 工具", Icons.Default.MusicNote, Color(0xFF34D399), "音频转 MIDI / 曲库", Routes.MIDI),
    ModuleEntry("指令编辑器", Icons.Default.Edit, Color(0xFF38BDF8), "快捷编辑指令", Routes.EDITOR),
    ModuleEntry("T显生成器", Icons.Default.BarChart, Color(0xFFFBBF24), "计分板/T显示", Routes.SCOREBOARD),
    ModuleEntry("穷举", Icons.Default.SwapHoriz, Color(0xFFA78BFA), "批量生成指令", Routes.ENUMERATE),
    ModuleEntry("滚动字幕", Icons.Default.Campaign, Color(0xFFF472B6), "公告/字幕指令", Routes.SCROLLER),
    ModuleEntry("音乐生成器", Icons.Default.MusicNote, Color(0xFF34D399), "命令方块音乐", Routes.MUSIC),
    ModuleEntry("T显JSON编辑器", Icons.Default.Code, Color(0xFF22D3EE), "rawtext 可视化编辑", Routes.TITLERAW_JSON),
    ModuleEntry("AI Agent", Icons.Default.SmartToy, Color(0xFF60A5FA), "AI 智能编写", Routes.AI),
    ModuleEntry("五子棋", Icons.Default.Casino, Color(0xFFF59E0B), "人机/匹配对战", Routes.GOMOKU),
    ModuleEntry("好友", Icons.Default.Person, Color(0xFFF472B6), "好友私聊", Routes.FRIENDS),
    ModuleEntry("插件库", Icons.Default.Extension, Color(0xFF22D3EE), "分享/下载插件", Routes.PLUGINS),
    ModuleEntry("输入法模式", Icons.Default.Keyboard, Color(0xFFFB923C), "游戏聊天框内快捷插入指令", Routes.SETTINGS),
    ModuleEntry("悬浮窗", Icons.Default.PictureInPicture, Color(0xFF2DD4BF), "全局快速复制", Routes.SETTINGS),
    ModuleEntry("优化", Icons.Default.Settings, Color(0xFFF87171), "性能优化设置", Routes.SETTINGS),
    ModuleEntry("意见申诉", Icons.Default.Feedback, Color(0xFFC084FC), "反馈问题/申诉", Routes.FEEDBACK),
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(nav: NavHostController) {
    val scope = rememberCoroutineScope()
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var anns by remember { mutableStateOf<List<Announcement>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    val user = AppState.user

    LaunchedEffect(Unit) {
        val r = safeApi { ApiClient.get().announcements("all") }
        if (r.ok) anns = r.data ?: emptyList()
        loaded = true
        // 刷新用户信息
        if (AppState.token != null) {
            val me = safeApi { ApiClient.get().me() }
            if (me.ok) me.data?.let { AppState.patchUser(points = it.points, aiUnlocked = it.aiUnlocked, nickname = it.nickname) }
        }
    }

    // 插件背景 (自定义背景插件)
    val pluginBg = com.canglan.cyancodex.plugin.PluginManager.background()
    val bgModifier = if (pluginBg != null) {
        val c1 = runCatching { Color(android.graphics.Color.parseColor(pluginBg.colorTop)) }.getOrDefault(Color(0xFF0B1A14))
        val c2 = runCatching { Color(android.graphics.Color.parseColor(pluginBg.colorBottom)) }.getOrDefault(c1)
        if (pluginBg.kind == "color") Modifier.background(c1)
        else Modifier.background(Brush.verticalGradient(listOf(c1, c2)))
    } else Modifier

    // 自定义背景图片已改为**全局**(2026-09-26): 由 MainActivity 最底层的 GlobalBackgroundImage
    // 统一绘制, 这里不再自己画一遍 —— 否则会和全局那张叠在一起, 而且只有主页能看到。
    Box(Modifier.fillMaxSize()) {
    Box(Modifier.fillMaxSize().then(bgModifier)) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        contentPadding = PaddingValues(
            start = 16.dp, top = 16.dp, end = 16.dp,
            // 液态玻璃开启时底栏浮在内容之上, 末尾补出这段高度, 否则最后一行会被玻璃压住
            bottom = 16.dp + LocalGlassBottomInset.current,
        ),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        // 头部横幅占满两列
        item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(2) }) {
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Image(
                            painter = androidx.compose.ui.res.painterResource(com.canglan.cyancodex.R.drawable.usericon),
                            contentDescription = "CyanCodex",
                            modifier = Modifier.size(30.dp)
                                .clip(androidx.compose.foundation.shape.RoundedCornerShape(7.dp)),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text("CyanCodex", style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.weight(1f))
                        if (user != null) {
                            Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.secondary) {
                                Text(" 积分 ${user.points} ", modifier = Modifier.clickable { nav.navigate(Routes.PROFILE) },
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSecondary)
                            }
                            Spacer(Modifier.width(6.dp))
                            Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.onPrimaryContainer) {
                                Text(" 签到 ", modifier = Modifier.clickable { nav.navigate(Routes.CHECKIN) },
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primaryContainer)
                            }
                        }
                    }
                    Text("一站式我的世界指令创作 · 沧蓝工坊开发", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer)
                    if (anns.isNotEmpty()) {
                        Spacer(Modifier.height(10.dp))
                        // 优先展示活动公告; 点击直达活动页 (开学季福利), 其余进公告列表
                        val bannerAnn = anns.firstOrNull { it.type == "activity" } ?: anns.first()
                        Card(shape = RoundedCornerShape(10.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.6f)),
                            modifier = Modifier.fillMaxWidth().clickable {
                                if (bannerAnn.type == "activity") nav.navigate(Routes.SCHOOL_SHARE)
                                else nav.navigate(Routes.ANNOUNCEMENTS)
                            }) {
                            Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                AnnBadge(bannerAnn.type)
                                Spacer(Modifier.width(8.dp))
                                Text(bannerAnn.title, style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface, maxLines = 2, modifier = Modifier.weight(1f))
                                Icon(Icons.Default.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                }
            }
        }

        // 聊天室入口 (意见 #44): 通知栏(公告)下方、指令库/编辑器上方, 居中圆角横条
        item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(2) }) {
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .clickable { nav.navigate(Routes.CHAT) },
            ) {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    // 图标最左侧
                    Image(
                        painter = androidx.compose.ui.res.painterResource(com.canglan.cyancodex.R.drawable.usericon),
                        contentDescription = "聊天室",
                        modifier = Modifier.size(38.dp)
                            .clip(androidx.compose.foundation.shape.RoundedCornerShape(9.dp)),
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        // 图标左上方「聊天室」
                        Text("聊天室", style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
                        Text("和玩家实时交流指令", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f))
                    }
                    Icon(Icons.Default.ChatBubbleOutline, null, tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(20.dp))
                }
            }
        }

        // 开发者功能入口: 图形化插件工坊 (积木式做插件 + 一键打包 JAR)
        item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(2) }) {
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp)
                    .clickable { nav.navigate(Routes.PLUGIN_BUILDER) },
            ) {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(38.dp)
                            .clip(androidx.compose.foundation.shape.RoundedCornerShape(9.dp))
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Default.Extension, null, tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp))
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("开发者功能", style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.width(6.dp))
                            Surface(shape = RoundedCornerShape(6.dp),
                                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)) {
                                Text("插件工坊", style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp))
                            }
                        }
                        Text("像搭积木一样做插件，一键打包成 JAR",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f))
                    }
                    Icon(Icons.Default.ChevronRight, null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                }
            }
        }

        val hasActivity = anns.any { it.type == "activity" }
        // 开学季福利活动模块: 仅当存在 activity 类型公告(活动进行中)时展示; 点击直达活动页
        val activityModule = if (hasActivity) {
            listOf(ModuleEntry("开学季福利", Icons.Default.Star, Color(0xFFFBBF24),
                "分享到QQ群得200积分", Routes.SCHOOL_SHARE))
        } else emptyList()
        val visibleModules = (activityModule + homeModules)
            .filter { it.title !in AppState.hiddenModules }
        // ★ 删掉了原先那行「已隐藏 N 个入口，点击显示全部」(2026-09-26 用户要求):
        //   用户自己会去设置里调, 首页不必顶一条提示条 —— 而且它插在网格首行会把卡片挤下去。
        //   下面只保留"一个都不剩"时的兜底空状态(那种情况用户已经看不到任何入口, 必须给条路)。
        if (visibleModules.isEmpty()) {
            item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(2) }) {
                Card(
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    modifier = Modifier.fillMaxWidth()
                        .clickable { AppState.resetHiddenModules() },
                ) {
                    Column(
                        Modifier.fillMaxWidth().padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Icon(Icons.Default.VisibilityOff, null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(32.dp))
                        Spacer(Modifier.height(8.dp))
                        Text("入口都被隐藏了", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(4.dp))
                        Text("点击这里恢复全部入口",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
        items(visibleModules) { m ->
            Card(
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                // 统一卡片高度, 左右比例一致; 长按隐藏 (意见#68)
                modifier = Modifier
                    .fillMaxWidth()
                    .height(116.dp)
                    .combinedClickable(
                        onClick = {
                            when (m.route) {
                                Routes.EDITOR -> nav.navigate(Routes.editor())
                                else -> nav.navigate(m.route)
                            }
                        },
                        onLongClick = {
                            // ★ 不设任何限制 (2026-09-26 用户要求: 「首页模块没必要限制吧，人家想关就关」)。
                            //   全部关掉也没事 —— 下面有"入口都被隐藏了 / 点击这里恢复全部入口"兜底卡片,
                            //   用户点一下就全回来了, 不会卡死。
                            AppState.toggleHiddenModule(m.title)
                            android.widget.Toast.makeText(ctx, "已隐藏「${m.title}」", android.widget.Toast.LENGTH_SHORT).show()
                        },
                    ),
            ) {
                Column(
                    Modifier.fillMaxSize().padding(12.dp),
                    verticalArrangement = Arrangement.SpaceBetween,
                ) {
                    Icon(m.icon, m.title, tint = m.tint, modifier = Modifier.size(28.dp))
                    Column {
                        Text(m.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                        Text(m.desc, style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    }
                }
            }
        }

        // 公告列表 (点击公告可展开查看, 头部进全部公告
        if (anns.size > 1) {
            item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(2) }) {
                var expandedId by remember { mutableStateOf<Long?>(null) }
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().clickable { nav.navigate(Routes.ANNOUNCEMENTS) }) {
                            Icon(Icons.Default.Campaign, null, tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("公告 · 点击查看全部", style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
                            Icon(Icons.Default.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(16.dp))
                        }
                        anns.take(3).forEach { a ->
                            Column(Modifier.fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { expandedId = if (expandedId == a.id) null else a.id }
                                .padding(vertical = 4.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    AnnBadge(a.type)
                                    Spacer(Modifier.width(8.dp))
                                    Text(a.title, style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurface, maxLines = 1,
                                        modifier = Modifier.weight(1f))
                                    Text(a.createdAt?.take(10) ?: "", style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Icon(if (expandedId == a.id) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                        null, tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(16.dp))
                                }
                                if (expandedId == a.id) {
                                    Spacer(Modifier.height(6.dp))
                                    Text(a.content, style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(start = 8.dp))
                                }
                            }
                        }
                    }
                }
            }
        }

        item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(2) }) {
            QqGroupCard()
        }

        item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(2) }) {
            Text("世纪集团旗下 · 沧蓝工坊工作室 · CyanCodex v${com.canglan.cyancodex.BuildConfig.VERSION_NAME}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            )
        }
    }
    }
    }
}

/** 公告分类徽章 */
@Composable
private fun AnnBadge(type: String) {
    val (label, color) = when (type) {
        "update" -> "更新" to Color(0xFF4ADE80)
        "punish" -> "处罚" to Color(0xFFF87171)
        else -> "公告" to Color(0xFF38BDF8)
    }
    Surface(shape = RoundedCornerShape(6.dp), color = color.copy(alpha = 0.18f)) {
        Text(label, color = color, fontSize = 11.sp,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp))
    }
}
