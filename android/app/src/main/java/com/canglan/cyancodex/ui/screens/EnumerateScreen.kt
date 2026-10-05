package com.canglan.cyancodex.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.canglan.cyancodex.data.ApiClient
import com.canglan.cyancodex.data.Session
import com.canglan.cyancodex.generators.Generators
import com.canglan.cyancodex.ui.AppState
import com.canglan.cyancodex.ui.components.AppTopBar
import com.canglan.cyancodex.ui.components.CommandOutputBox
import com.canglan.cyancodex.ui.components.GlassActionButton
import com.canglan.cyancodex.ui.components.OutlinedTextField2
import com.canglan.cyancodex.ui.components.safeApi
import com.google.gson.Gson
import kotlinx.coroutines.launch

/**
 * 穷举: 批量生成相同结构、不同数据的指令
 * 模板中用 {变量名} 占位, 每个变量填多个取值, 自动笛卡尔积展开
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EnumerateScreen(nav: NavHostController) {
    val scope = rememberCoroutineScope()
    var template by remember { mutableStateOf("") }
    var vars by remember { mutableStateOf(listOf(Pair("player", "Steve,Alex,Notch"), Pair("amount", "1,2,4,8"))) }
    var maxCount by remember { mutableStateOf(100) }
    var result by remember { mutableStateOf<List<String>?>(null) }
    var tip by remember { mutableStateOf("") }

    fun generate() {
        val map = vars.mapNotNull { (k, v) ->
            if (k.isBlank() || v.isBlank()) null
            else k.trim() to v.split(Regex("[,\\n]")).map { it.trim() }.filter { it.isNotBlank() }
        }.toMap()
        if (template.isBlank() || map.isEmpty()) {
            tip = "请填写模板和至少一个变量"
            return
        }
        tip = ""
        val cmds = Generators.enumerate(Generators.EnumerateArgs(template, map, maxCount))
        result = cmds
        Session.addLocalHistory("enumerate", Gson().toJson(mapOf("template" to template, "vars" to map, "max" to maxCount)), cmds.joinToString("\n"))
        scope.launch {
            if (AppState.token != null) {
                val input = Gson().toJson(mapOf("template" to template, "vars" to map, "max" to maxCount))
                safeApi { ApiClient.get().saveHistory(mapOf(
                    "gen_type" to "enumerate", "input_json" to input, "output_command" to cmds.joinToString("\n"))) }
            }
        }
    }

    Scaffold(
        topBar = {
            AppTopBar(title = "穷举生成器", icon = Icons.Default.SwapHoriz, onBack = { nav.popBackStack() })
        },
    ) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Text("示例(基岩版): /give @s diamond {count}  →  变量 count 取 1,2,4 生成 3 条指令",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            item {
                OutlinedTextField(
                    value = template, onValueChange = { template = it },
                    label = { Text("指令模板 (用 {变量} 占位)") },
                    placeholder = { Text("/give @s diamond {amount}") },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 100.dp),
                    textStyle = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace),
                )
            }
            item {
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text("变量列表", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    FilledTonalButton(onClick = { vars = vars + Pair("", "") }) {
                        Icon(Icons.Default.Add, null, Modifier.size(16.dp)); Text("添加变量")
                    }
                }
            }
            itemsIndexed(vars) { idx, (k, v) ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    OutlinedTextField2(k, { nv -> vars = vars.toMutableList().also { it[idx] = Pair(nv, it[idx].second) } },
                        "变量名", modifier = Modifier.weight(1f))
                    OutlinedTextField2(v, { nv -> vars = vars.toMutableList().also { it[idx] = Pair(it[idx].first, nv) } },
                        "取值(逗号分隔)", modifier = Modifier.weight(2f))
                    IconButton(onClick = { vars = vars.filterIndexed { i, _ -> i != idx } }) {
                        Icon(Icons.Default.Close, "删除")
                    }
                }
            }
            item {
                Text("最大生成条数: $maxCount", style = MaterialTheme.typography.labelMedium)
                Slider(value = maxCount.toFloat(), onValueChange = { maxCount = it.toInt() },
                    valueRange = 1f..500f, steps = 98)
            }
            item {
                if (tip.isNotBlank()) Text(tip, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                GlassActionButton(filled = true, onClick = { generate() }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.PlayArrow, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("开始穷举")
                }
            }
            result?.let { cmds ->
                item { CommandOutputBox(cmds) }
            }
        }
    }
}
