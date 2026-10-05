package com.canglan.cyancodex.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Widgets
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.canglan.cyancodex.ui.LocalGlassBottomInset
import com.canglan.cyancodex.ui.Routes
import com.canglan.cyancodex.ui.components.AppTopBar

private data class ToolEntry(
    val title: String,
    val icon: ImageVector,
    val tint: Color,
    val desc: String,
    val route: String,
)

/** 隐私与安全 */
private val safeTools = listOf(
    ToolEntry("网络测速", Icons.Default.Speed, Color(0xFF38BDF8),
        "延迟 / 下载 / 上传速度", Routes.SPEED_TEST),
)

/** 生成器 */
private val genTools = listOf(
    ToolEntry("T显生成器", Icons.Default.BarChart, Color(0xFFFBBF24), "计分板/T显示指令", Routes.SCOREBOARD),
    ToolEntry("穷举", Icons.Default.SwapHoriz, Color(0xFFA78BFA), "批量生成不同数据指令", Routes.ENUMERATE),
    ToolEntry("滚动字幕", Icons.Default.Campaign, Color(0xFFF472B6), "公告/滚动字幕", Routes.SCROLLER),
    ToolEntry("音乐生成器", Icons.Default.MusicNote, Color(0xFF34D399), "命令方块音乐", Routes.MUSIC),
)

/** 实用工具 */
private val utilTools = listOf(
    ToolEntry("MIDI 工具", Icons.Default.MusicNote, Color(0xFF34D399), "音频转 MIDI / 曲库下载", Routes.MIDI),
    ToolEntry("查询 ID", Icons.Default.Search, Color(0xFF60A5FA), "中文名查基岩版 ID", Routes.MC_ID),
    ToolEntry("JSON 生成", Icons.Default.Code, Color(0xFFF59E0B), "titleraw / 文本组件 JSON", Routes.TITLERAW_JSON),
)

/**
 * 工具箱: 把生成器、实用工具与网络工具收在一个入口里。
 * (原来的「生成器」Tab 升级成「工具箱」)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToolboxScreen(nav: NavHostController) {
    Scaffold(
        topBar = { AppTopBar(title = "工具箱", icon = Icons.Default.Widgets) },
    ) { pad ->
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            contentPadding = PaddingValues(
                start = 16.dp, top = 16.dp, end = 16.dp,
                // 液态玻璃开启时底栏浮在内容之上, 末尾补出这段高度
                bottom = 16.dp + LocalGlassBottomInset.current,
            ),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(pad).fillMaxSize(),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) { GroupTitle("网络工具") }
            items(safeTools) { g -> ToolCard(g, null, true) { nav.navigate(g.route) } }

            item(span = { GridItemSpan(maxLineSpan) }) { GroupTitle("生成器") }
            items(genTools) { g -> ToolCard(g, null, true) { nav.navigate(g.route) } }

            item(span = { GridItemSpan(maxLineSpan) }) { GroupTitle("实用工具") }
            items(utilTools) { g -> ToolCard(g, null, true) { nav.navigate(g.route) } }

            item(span = { GridItemSpan(maxLineSpan) }) {
                Card(
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                    modifier = Modifier.fillMaxWidth().clickable { nav.navigate(Routes.HISTORY) },
                ) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.History, null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text("生成历史", style = MaterialTheme.typography.titleSmall)
                            Text("查看已保存的生成记录", style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GroupTitle(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
        Text(text, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun ToolCard(
    g: ToolEntry,
    badge: String?,
    badgeOk: Boolean,
    onClick: () -> Unit,
) {
    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(g.icon, null, tint = g.tint, modifier = Modifier.size(26.dp))
                Spacer(Modifier.weight(1f))
                if (badge != null) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = if (badgeOk) Color(0xFF34D399).copy(alpha = 0.18f)
                        else MaterialTheme.colorScheme.errorContainer,
                    ) {
                        Text(
                            badge,
                            Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (badgeOk) Color(0xFF34D399) else MaterialTheme.colorScheme.onErrorContainer,
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(g.title, style = MaterialTheme.typography.titleSmall)
            Text(g.desc, style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
