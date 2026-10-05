package com.canglan.cyancodex.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.canglan.cyancodex.data.ApiClient
import com.canglan.cyancodex.data.Session
import com.canglan.cyancodex.generators.Generators
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
 * 滚动字幕 / 公告生成器
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScrollerScreen(nav: NavHostController) {
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf("欢迎来到 沧蓝工坊 服务器!") }
    var color by remember { mutableStateOf("gold") }
    var mode by remember { mutableStateOf("roll") }
    var interval by remember { mutableStateOf(20) }
    var x by remember { mutableStateOf("0") }
    var y by remember { mutableStateOf("100") }
    var z by remember { mutableStateOf("0") }
    var gap by remember { mutableStateOf("2") }
    var result by remember { mutableStateOf<List<String>?>(null) }

    fun generate() {
        val cmds = Generators.scroller(Generators.ScrollerArgs(
            text = text, color = color, mode = mode, intervalTicks = interval,
            x = x.toIntOrNull() ?: 0, y = y.toIntOrNull() ?: 100, z = z.toIntOrNull() ?: 0,
            gap = gap.toIntOrNull() ?: 2,
        ))
        result = cmds
        Session.addLocalHistory("scroller", Gson().toJson(mapOf("text" to text, "color" to color, "mode" to mode)), cmds.joinToString("\n"))
        scope.launch {
            if (AppState.token != null) {
                val input = Gson().toJson(mapOf("text" to text, "color" to color, "mode" to mode))
                safeApi { ApiClient.get().saveHistory(mapOf(
                    "gen_type" to "scroller", "input_json" to input, "output_command" to cmds.joinToString("\n"))) }
            }
        }
    }

    Scaffold(
        topBar = {
            AppTopBar(title = "滚动字幕生成器", icon = Icons.Default.Campaign, onBack = { nav.popBackStack() })
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(value = text, onValueChange = { text = it },
                label = { Text("字幕内容 (空格分隔将逐行轮播)") }, modifier = Modifier.fillMaxWidth())

            Text("颜色:", style = MaterialTheme.typography.labelMedium)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(Generators.MC_COLORS.size) { i ->
                    val (k, v) = Generators.MC_COLORS.entries.elementAt(i)
                    GlassFilterChip(selected = color == v, onClick = { color = v }, label = { Text(k) })
                }
            }

            Text("模式:", style = MaterialTheme.typography.labelMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GlassFilterChip(selected = mode == "roll", onClick = { mode = "roll" }, label = { Text("轮播公告(title)") })
                GlassFilterChip(selected = mode == "banner", onClick = { mode = "banner" }, label = { Text("静态横幅(盔甲架)") })
            }

            if (mode == "roll") {
                Text("每行间隔: $interval 游戏刻", style = MaterialTheme.typography.labelMedium)
                Slider(value = interval.toFloat(), onValueChange = { interval = it.toInt() }, valueRange = 5f..100f)
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField2(x, { x = it }, "起始 X", modifier = Modifier.weight(1f))
                    OutlinedTextField2(y, { y = it }, "Y", modifier = Modifier.weight(1f))
                    OutlinedTextField2(z, { z = it }, "Z", modifier = Modifier.weight(1f))
                }
                OutlinedTextField2(gap, { gap = it }, "字符间距", modifier = Modifier.weight(1f))
            }

            GlassActionButton(filled = true, onClick = { generate() }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.PlayArrow, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("生成指令")
            }
            result?.let { CommandOutputBox(it) }
        }
    }
}
