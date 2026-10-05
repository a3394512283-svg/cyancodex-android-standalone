package com.canglan.cyancodex.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.canglan.cyancodex.data.ApiClient
import com.canglan.cyancodex.data.Session
import com.canglan.cyancodex.generators.Generators
import com.canglan.cyancodex.generators.Generators.ScoreboardEntry
import com.canglan.cyancodex.generators.Generators.TDisplayLine
import com.canglan.cyancodex.ui.AppState
import com.canglan.cyancodex.ui.components.AppTopBar
import com.canglan.cyancodex.ui.components.CommandOutputBox
import com.canglan.cyancodex.ui.components.GlassActionButton
import com.canglan.cyancodex.ui.components.GlassFilterChip
import com.canglan.cyancodex.ui.components.OutlinedTextField2
import com.canglan.cyancodex.ui.components.safeApi
import com.google.gson.Gson
import kotlinx.coroutines.launch

/**
 * T显生成器 (基岩版): 侧边栏计分板 / T显字幕(titleraw), 计分项仅 dummy
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScoreboardScreen(nav: NavHostController) {
    val scope = rememberCoroutineScope()
    var mode by remember { mutableStateOf("sidebar") }
    var objective by remember { mutableStateOf("sb") }
    var displayName by remember { mutableStateOf("血量") }
    var slot by remember { mutableStateOf("sidebar") }
    var tpos by remember { mutableStateOf("title") }
    var entries by remember { mutableStateOf(listOf(ScoreboardEntry("Steve", 20), ScoreboardEntry("Alex", 20))) }
    // #53 修复: 分数输入用文本态 (允许清空/中间态, 不再每键即时转 Int 导致删不掉/光标乱跳)
    var entryTexts by remember {
        mutableStateOf(listOf("20", "20"))
    }
    var tlines by remember {
        mutableStateOf(listOf(
            TDisplayLine("生命值: ", "hp", ""),
            TDisplayLine("金币: ", "money", " 枚"),
        ))
    }
    var result by remember { mutableStateOf<List<String>?>(null) }

    val slots = listOf("sidebar" to "侧边栏", "belowname" to "名字下方", "list" to "列表")
    val tposes = listOf("title" to "标题", "subtitle" to "副标题", "actionbar" to "快捷栏上方")

    fun generate() {
        // 分数输入文本态 -> 数值 (空/非法按 0)
        val resolved = entries.mapIndexed { i, e ->
            e.copy(value = entryTexts.getOrElse(i) { e.value.toString() }.trim().toIntOrNull() ?: 0)
        }
        val cmds = Generators.scoreboard(Generators.ScoreboardArgs(
            objective = objective, displayName = displayName, slot = slot,
            mode = mode, tpos = tpos, entries = resolved, tlines = tlines,
        ))
        result = cmds
        Session.addLocalHistory("tdisplay", Gson().toJson(mapOf(
            "objective" to objective, "displayName" to displayName,
            "mode" to mode, "slot" to slot, "tpos" to tpos,
            "entries" to resolved, "tlines" to tlines)), cmds.joinToString("\n"))
        scope.launch {
            if (AppState.token != null) {
                val input = Gson().toJson(mapOf(
                    "objective" to objective, "displayName" to displayName,
                    "mode" to mode, "slot" to slot, "tpos" to tpos,
                    "entries" to resolved, "tlines" to tlines))
                safeApi { ApiClient.get().saveHistory(mapOf(
                    "gen_type" to "tdisplay", "input_json" to input, "output_command" to cmds.joinToString("\n"))) }
            }
        }
    }

    Scaffold(
        topBar = {
            AppTopBar(title = "T显生成器 (基岩版)", icon = Icons.Default.BarChart, onBack = { nav.popBackStack() })
        },
    ) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Text("生成模式:", style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GlassFilterChip(selected = mode == "sidebar", onClick = { mode = "sidebar" }, label = { Text("侧边栏计分板") })
                    GlassFilterChip(selected = mode == "tdisplay", onClick = { mode = "tdisplay" }, label = { Text("T显字幕(titleraw)") })
                }
            }
            item {
                OutlinedTextField2(objective, { objective = it }, "计分板名称(英文)")
                Spacer(Modifier.height(10.dp))
                OutlinedTextField2(displayName, { displayName = it }, "显示名称")
                Spacer(Modifier.height(10.dp))
                Text("计分项类型: dummy (基岩版唯一)", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.secondary)
            }
            if (mode == "sidebar") {
                item {
                    Text("显示位置:", style = MaterialTheme.typography.labelMedium)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(slots.size) { i ->
                            GlassFilterChip(selected = slot == slots[i].first, onClick = { slot = slots[i].first },
                                label = { Text(slots[i].second) })
                        }
                    }
                }
                item {
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Text("玩家分数", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                        FilledTonalButton(onClick = {
                            entries = entries + ScoreboardEntry("", 0)
                            entryTexts = entryTexts + "0"
                        }) {
                            Icon(Icons.Default.Add, null, Modifier.size(16.dp)); Text("添加")
                        }
                    }
                }
                itemsIndexed(entries) { idx, e ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        OutlinedTextField2(e.player, { v ->
                            entries = entries.toMutableList().also { it[idx] = e.copy(player = v) }
                        }, "玩家名", modifier = Modifier.weight(1.4f))
                        OutlinedTextField2(entryTexts.getOrElse(idx) { "0" }, { v ->
                            // #53: 允许输入中间态/清空, 不即时转 Int
                            if (v.all { it.isDigit() }) {
                                entryTexts = entryTexts.toMutableList().also { if (idx < it.size) it[idx] = v }
                            }
                        }, "分数", modifier = Modifier.weight(1f))
                        IconButton(onClick = {
                            entries = entries.filterIndexed { i, _ -> i != idx }
                            entryTexts = entryTexts.filterIndexed { i, _ -> i != idx }
                        }) {
                            Icon(Icons.Default.Close, "删除")
                        }
                    }
                }
            } else {
                item {
                    Text("显示位置:", style = MaterialTheme.typography.labelMedium)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(tposes.size) { i ->
                            GlassFilterChip(selected = tpos == tposes[i].first, onClick = { tpos = tposes[i].first },
                                label = { Text(tposes[i].second) })
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text("T显内容行 (每行 = 文字 + 计分项):", style = MaterialTheme.typography.titleSmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(onClick = { tlines = tlines + TDisplayLine("", "", "") }) {
                            Icon(Icons.Default.Add, null, Modifier.size(16.dp)); Text("添加行")
                        }
                    }
                }
                itemsIndexed(tlines) { idx, l ->
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        OutlinedTextField2(l.prefix, { v -> tlines = tlines.toMutableList().also { it[idx] = l.copy(prefix = v) } },
                            "前缀文字", modifier = Modifier.weight(1.2f))
                        OutlinedTextField2(l.objective, { v -> tlines = tlines.toMutableList().also { it[idx] = l.copy(objective = v) } },
                            "计分项", modifier = Modifier.weight(1f))
                        OutlinedTextField2(l.suffix, { v -> tlines = tlines.toMutableList().also { it[idx] = l.copy(suffix = v) } },
                            "后缀", modifier = Modifier.weight(1f))
                        IconButton(onClick = { tlines = tlines.filterIndexed { i, _ -> i != idx } }) {
                            Icon(Icons.Default.Close, "删除")
                        }
                    }
                }
                item {
                    Text("示例输出: /titleraw @a title {\"rawtext\":[{\"text\":\"生命值: \"},{\"score\":{\"name\":\"@s\",\"objective\":\"hp\"}}]}",
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            item {
                GlassActionButton(filled = true, onClick = { generate() }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.PlayArrow, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("生成指令")
                }
            }
            result?.let { cmds ->
                item { CommandOutputBox(cmds) }
            }
        }
    }
}
