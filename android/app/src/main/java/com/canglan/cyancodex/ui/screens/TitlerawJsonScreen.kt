package com.canglan.cyancodex.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.canglan.cyancodex.ui.components.AppTopBar
import com.canglan.cyancodex.ui.components.GlassActionButton
import com.canglan.cyancodex.ui.components.GlassFilterChip
import com.canglan.cyancodex.ui.components.TopBarTextAction
import com.canglan.cyancodex.ui.components.copyToClipboard
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * T显 JSON 编辑器 (参考 TitleRaw 编辑器):
 * 可视化编辑 /titleraw 的 rawtext 元素 (文字/翻译/选择器/计分板/顺序拼接/原始JSON),
 * § 颜色调色板, 实时预览, 生成 rawtext JSON 与完整 /titleraw 指令, 支持预设
 */

private data class TlElem(
    var type: String,          // text / translate / selector / score / concat / raw
    var text: String = "",
    var key: String = "",
    var with: MutableList<String> = mutableListOf(),
    var selector: String = "@p",
    var scoreName: String = "@s",
    var scoreObjective: String = "sb",
    var rawJson: String = "",
)

// MC § 颜色
private val MC_COLORS = listOf(
    "0" to Color(0xFF000000), "1" to Color(0xFF0000AA), "2" to Color(0xFF00AA00), "3" to Color(0xFF00AAAA),
    "4" to Color(0xFFAA0000), "5" to Color(0xFFAA00AA), "6" to Color(0xFFFFAA00), "7" to Color(0xFFAAAAAA),
    "8" to Color(0xFF555555), "9" to Color(0xFF5555FF), "a" to Color(0xFF55FF55), "b" to Color(0xFF55FFFF),
    "c" to Color(0xFFFF5555), "d" to Color(0xFFFF55FF), "e" to Color(0xFFFFFF55), "f" to Color(0xFFFFFFFF),
)

private fun buildRawtext(elems: List<TlElem>): String {
    val parser = JsonParser()
    val list = elems.map { e ->
        when (e.type) {
            "text" -> JsonObject().apply { addProperty("text", e.text) }
            "translate" -> JsonObject().apply { addProperty("translate", e.key) }
            "selector" -> JsonObject().apply { addProperty("selector", e.selector) }
            "score" -> JsonObject().apply {
                val s = JsonObject()
                s.addProperty("name", e.scoreName)
                s.addProperty("objective", e.scoreObjective)
                add("score", s)
            }
            "concat" -> JsonObject().apply {
                addProperty("translate", e.key)
                add("with", com.google.gson.JsonArray().apply { e.with.forEach { add(it) } })
            }
            else -> runCatching { parser.parse(e.rawJson.ifBlank { "{}" }) }.getOrNull() ?: JsonObject()
        }
    }
    return Gson().toJson(list)
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun TitlerawJsonScreen(nav: NavHostController) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    // mutableStateListOf: 增删可被 Compose 观察, 避免预设替换后点删除索引失效闪退 (意见 id=13)
    // 初始为空文字元素 (意见 #54: 不再硬编码示例文字, 避免"初始文字删不掉")
    val elems = remember { mutableStateListOf(TlElem(type = "text", text = "")) }
    var expandedId by remember { mutableStateOf(0) }
    var showAdd by remember { mutableStateOf(false) }
    var outputJson by remember { mutableStateOf(buildRawtext(listOf(TlElem(type = "text", text = "")))) }
    var pos by remember { mutableStateOf("title") }
    var target by remember { mutableStateOf("@a") }
    var msg by remember { mutableStateOf("") }

    fun refresh() {
        outputJson = buildRawtext(elems)
    }

    fun addType(t: String) {
        elems.add(TlElem(type = t))
        expandedId = elems.size - 1
        refresh()
    }

    fun removeAt(i: Int) {
        if (i !in elems.indices) return   // 防御: 陈旧索引
        if (elems.size > 1) {
            elems.removeAt(i)
            expandedId = -1
            refresh()
        } else {
            msg = "至少保留一个元素"
        }
    }

    fun move(i: Int, d: Int) {
        val j = i + d
        if (i in elems.indices && j in elems.indices) {
            val tmp = elems[i]
            elems[i] = elems[j]
            elems[j] = tmp
            expandedId = j
            refresh()
        }
    }

    fun applyPreset(name: String) {
        val newList = when (name) {
            "欢迎语" -> mutableListOf(
                TlElem(type = "text", text = "欢迎来到 沧蓝工坊！\n"),
                TlElem(type = "selector", selector = "@p"),
                TlElem(type = "text", text = " 祝你游戏愉快！"),
            )
            "计分板" -> mutableListOf(
                TlElem(type = "text", text = "分数: "),
                TlElem(type = "score", scoreName = "@s", scoreObjective = "sb"),
            )
            "玩家名" -> mutableListOf(TlElem(type = "selector", selector = "@p"))
            else -> return
        }
        elems.clear()
        elems.addAll(newList)
        expandedId = -1
        refresh()
    }

    fun importJson(raw: String) {
        msg = ""
        val parsed = runCatching {
            var root = JsonParser().parse(raw)
            if (root.isJsonObject && root.asJsonObject.has("rawtext")) {
                root = root.asJsonObject.get("rawtext")
            }
            if (root.isJsonArray) root.asJsonArray else null
        }.getOrNull()
        if (parsed == null || parsed.size() == 0) {
            msg = "JSON 解析失败，需要 rawtext 数组"
            return
        }
        val list = mutableListOf<TlElem>()
        parsed.forEach { el ->
            if (el.isJsonObject) {
                val o = el.asJsonObject
                when {
                    o.has("text") -> list.add(TlElem(type = "text", text = o.get("text").asString))
                    o.has("translate") -> {
                        val w = if (o.has("with") && o.get("with").isJsonArray) {
                            o.get("with").asJsonArray.map { it.asString }.toMutableList()
                        } else mutableListOf()
                        list.add(if (w.isNotEmpty())
                            TlElem(type = "concat", key = o.get("translate").asString, with = w)
                        else TlElem(type = "translate", key = o.get("translate").asString))
                    }
                    o.has("selector") -> list.add(TlElem(type = "selector", selector = o.get("selector").asString))
                    o.has("score") -> {
                        val s = o.get("score").asJsonObject
                        list.add(TlElem(type = "score",
                            scoreName = if (s.has("name")) s.get("name").asString else "@s",
                            scoreObjective = if (s.has("objective")) s.get("objective").asString else "sb"))
                    }
                    else -> list.add(TlElem(type = "raw", rawJson = el.toString()))
                }
            } else {
                list.add(TlElem(type = "raw", rawJson = el.toString()))
            }
        }
        if (list.isEmpty()) {
            msg = "JSON 中没有可识别的元素"
            return
        }
        elems.clear()
        elems.addAll(list)
        expandedId = -1
        refresh()
        msg = "导入成功: ${list.size} 个元素"
    }

    val fullCmd = buildString {
        append("/titleraw $target $pos ")
        append("{\"rawtext\":")
        append(outputJson)
        append("}")
    }

    Scaffold(topBar = {
        AppTopBar(title = "T显 JSON 编辑器", icon = Icons.Default.Code, onBack = { nav.popBackStack() },
            actions = {
                TopBarTextAction(onClick = { copyToClipboard(ctx, outputJson, "已复制 rawtext JSON") }) { Text("复制JSON") }
                TopBarTextAction(onClick = { copyToClipboard(ctx, fullCmd, "已复制完整指令") }) { Text("复制指令") }
            })
    }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            // 顶部工具行
            Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                GlassActionButton(onClick = { showAdd = true }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.Add, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text("添加元素")
                }
                Spacer(Modifier.width(8.dp))
                GlassActionButton(onClick = { applyPreset("欢迎语") }) { Text("欢迎语", style = MaterialTheme.typography.labelMedium) }
                Spacer(Modifier.width(8.dp))
                GlassActionButton(onClick = { applyPreset("计分板") }) { Text("计分板", style = MaterialTheme.typography.labelMedium) }
            }
            // 元素列表
            LazyColumn(Modifier.weight(1f)) {
                itemsIndexed(elems) { i, e ->
                    ElementCard(
                        index = i, e = e, expanded = expandedId == i,
                        onToggle = { expandedId = if (expandedId == i) -1 else i },
                        onMoveUp = { move(i, -1) }, onMoveDown = { move(i, 1) },
                        onDelete = { removeAt(i) },
                        onChanged = { refresh() },
                    )
                }
                item { Spacer(Modifier.height(8.dp)) }
            }
            // 底部: 参数 + 预览 + 输出
            Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("位置:", style = MaterialTheme.typography.labelMedium)
                    Spacer(Modifier.width(6.dp))
                    listOf("title" to "title", "subtitle" to "subtitle", "actionbar" to "actionbar").forEach { (k, v) ->
                        GlassFilterChip(selected = pos == k, onClick = { pos = k }, label = { Text(v, style = MaterialTheme.typography.labelSmall) },
                            modifier = Modifier.padding(end = 4.dp))
                    }
                    Spacer(Modifier.width(4.dp))
                    Text("目标:", style = MaterialTheme.typography.labelMedium)
                    Spacer(Modifier.width(4.dp))
                    OutlinedTextField(value = target, onValueChange = { target = it }, singleLine = true,
                        modifier = Modifier.width(110.dp), textStyle = MaterialTheme.typography.bodySmall)
                }
                // 预览
                Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF0A0F0C)),
                    modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(8.dp)) {
                        Text("预览 (玩家实际看到的效果)", style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFF9DB4A5))
                        Spacer(Modifier.height(4.dp))
                        BasicText(buildAnnotatedString {
                            elems.forEach { e ->
                                when (e.type) {
                                    "text" -> appendSection(e.text, this)
                                    "selector" -> appendSection("§6" + e.selector.replace("@", ""), this)
                                    "score" -> appendSection("§a[计分板 ${e.scoreObjective}]", this)
                                    else -> appendSection("§7[${typeLabel(e.type)}]", this)
                                }
                            }
                        })
                    }
                }
                // 输出
                Text("输出 JSON (可直接粘贴进 /titleraw):", style = MaterialTheme.typography.labelSmall)
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                    Text(outputJson, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        modifier = Modifier.padding(8.dp).fillMaxWidth())
                }
                if (msg.isNotBlank()) {
                    Text(msg, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                Spacer(Modifier.height(6.dp))
            }
        }
    }

    // 添加元素对话框
    if (showAdd) {
        AlertDialog(
            onDismissRequest = { showAdd = false },
            title = { Text("添加元素") },
            text = {
                Column {
                    listOf(
                        Triple("text", "文字", "直接显示一段文字（支持 § 颜色）"),
                        Triple("translate", "翻译", "把翻译识别符显示成对应语言文本"),
                        Triple("selector", "选择器", "显示被选中玩家的名字"),
                        Triple("score", "计分板", "显示玩家在某计分板上的分数"),
                        Triple("concat", "顺序拼接", "把槽位按 %1%2… 排列显示"),
                        Triple("raw", "原始JSON", "直接粘贴一段 rawtext JSON"),
                    ).forEach { (k, label, desc) ->
                        Card(onClick = { addType(k); showAdd = false },
                            modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                            Column(Modifier.padding(10.dp)) {
                                Text(label, style = MaterialTheme.typography.titleSmall)
                                Text(desc, style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showAdd = false }) { Text("取消") } },
        )
    }
}

private fun typeLabel(t: String) = when (t) {
    "text" -> "文字"
    "translate" -> "翻译"
    "selector" -> "选择器"
    "score" -> "计分板"
    "concat" -> "顺序拼接"
    else -> "原始"
}

/** 按 § 代码构建彩色/样式文本 */
private fun appendSection(raw: String, builder: androidx.compose.ui.text.AnnotatedString.Builder) {
    var color: Color? = null
    var bold = false
    var italic = false
    var underline = false
    var strike = false
    val regex = Regex("§[0-9a-fk-or]")
    var last = 0
    regex.findAll(raw).forEach { m ->
        if (m.range.first > last) {
            builder.withStyle(SpanStyle(color = color ?: Color.Unspecified,
                fontWeight = if (bold) FontWeight.Bold else null,
                fontStyle = if (italic) FontStyle.Italic else null,
                textDecoration = when {
                    underline -> TextDecoration.Underline
                    strike -> TextDecoration.LineThrough
                    else -> null
                })) { append(raw.substring(last, m.range.first)) }
        }
        val c = m.value[1]
        when (c) {
            in '0'..'9', in 'a'..'f' -> color = MC_COLORS.firstOrNull { it.first == c.toString() }?.second ?: Color.White
            'l' -> bold = true
            'o' -> italic = true
            'n' -> underline = true
            'm' -> strike = true
            'k' -> {}
            'r' -> { color = null; bold = false; italic = false; underline = false; strike = false }
        }
        last = m.range.last + 1
    }
    if (last < raw.length) {
        builder.withStyle(SpanStyle(color = color ?: Color.Unspecified,
            fontWeight = if (bold) FontWeight.Bold else null,
            fontStyle = if (italic) FontStyle.Italic else null,
            textDecoration = when {
                underline -> TextDecoration.Underline
                strike -> TextDecoration.LineThrough
                else -> null
            })) { append(raw.substring(last)) }
    }
}

/** 单个元素编辑卡片 */
@Composable
private fun ElementCard(
    index: Int,
    e: TlElem,
    expanded: Boolean,
    onToggle: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onDelete: () -> Unit,
    onChanged: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = cs.surface)) {
        Column(Modifier.padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${index + 1}", style = MaterialTheme.typography.labelSmall, color = cs.primary)
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(typeLabel(e.type), style = MaterialTheme.typography.titleSmall)
                    Text(summary(e), style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant, maxLines = 1)
                }
                IconButton(onClick = onMoveUp, enabled = index > 0) {
                    Icon(Icons.Default.KeyboardArrowUp, "上移", Modifier.size(18.dp))
                }
                IconButton(onClick = onMoveDown) {
                    Icon(Icons.Default.KeyboardArrowDown, "下移", Modifier.size(18.dp))
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Default.DeleteOutline, "删除", Modifier.size(18.dp), tint = cs.error)
                }
                TextButton(onClick = onToggle) { Text(if (expanded) "收起" else "展开", style = MaterialTheme.typography.labelMedium) }
            }
            if (expanded) {
                Spacer(Modifier.height(6.dp))
                when (e.type) {
                    "text" -> TextElementEditor(e, onChanged)
                    "translate" -> KeyField("翻译识别符", e, { e.key = it }, onChanged)
                    "selector" -> SelectorEditor(e, onChanged)
                    "score" -> ScoreEditor(e, onChanged)
                    "concat" -> ConcatEditor(e, onChanged)
                    else -> OutlinedTextField(value = e.rawJson, onValueChange = { e.rawJson = it; onChanged() },
                        label = { Text("rawtext JSON (对象或数组)") }, modifier = Modifier.fillMaxWidth(),
                        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace))
                }
            }
        }
    }
}

private fun summary(e: TlElem): String = when (e.type) {
    "text" -> if (e.text.isBlank()) "显示空白文字" else e.text.replace("\n", "\\n")
    "translate" -> if (e.key.isBlank()) "翻译识别符为空" else e.key
    "selector" -> e.selector
    "score" -> "${e.scoreName} 的 ${e.scoreObjective}"
    "concat" -> "${e.key} (${e.with.size} 个槽位)"
    else -> e.rawJson.take(24)
}

@Composable
private fun KeyField(label: String, e: TlElem, set: (String) -> Unit, onChanged: () -> Unit) {
    OutlinedTextField(value = e.key, onValueChange = { set(it); onChanged() },
        label = { Text(label) }, singleLine = true, modifier = Modifier.fillMaxWidth())
}

/** 文字编辑器: 输入 + § 调色板 + 样式按钮 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TextElementEditor(e: TlElem, onChanged: () -> Unit) {
    Column {
        Text("§ 开头是颜色/样式代码：§e 黄、§l 粗、§o 斜、§k 乱码、§r 重置。点下方色块/按钮插入末尾。",
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(4.dp))
        OutlinedTextField(value = e.text, onValueChange = { e.text = it; onChanged() },
            label = { Text("要显示的文字（\\n 代表换行）") }, modifier = Modifier.fillMaxWidth(), minLines = 2)
        Spacer(Modifier.height(6.dp))
        // 色块
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            MC_COLORS.forEach { (code, color) ->
                Box(
                    Modifier.size(28.dp).clip(CircleShape).background(color)
                        .clickable { e.text += "§$code"; onChanged() }
                        .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("§$code", style = MaterialTheme.typography.labelSmall,
                        color = if (code in listOf("0", "8", "4", "5", "9", "c", "d", "1", "3", "6")) Color.White else Color.Black)
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        // 样式按钮
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("l" to "加粗", "o" to "斜体", "n" to "下划线", "m" to "删除线", "k" to "乱码", "r" to "重置颜色").forEach { (c, label) ->
                SuggestionChip(onClick = { e.text += "§$c"; onChanged() }, label = { Text(label) })
            }
        }
    }
}

/** 选择器编辑器 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SelectorEditor(e: TlElem, onChanged: () -> Unit) {
    Column {
        OutlinedTextField(value = e.selector, onValueChange = { e.selector = it; onChanged() },
            label = { Text("选择器 (如 @p / @a[tag=x])") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace))
        Spacer(Modifier.height(6.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("@p", "@a", "@e", "@s", "@r", "@a[tag=admin]").forEach { s ->
                SuggestionChip(onClick = { e.selector = s; onChanged() }, label = { Text(s, style = MaterialTheme.typography.labelSmall) })
            }
        }
    }
}

/** 计分板编辑器 */
@Composable
private fun ScoreEditor(e: TlElem, onChanged: () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(value = e.scoreName, onValueChange = { e.scoreName = it; onChanged() },
            label = { Text("玩家") }, singleLine = true, modifier = Modifier.weight(1f))
        OutlinedTextField(value = e.scoreObjective, onValueChange = { e.scoreObjective = it; onChanged() },
            label = { Text("计分项") }, singleLine = true, modifier = Modifier.weight(1f))
    }
}

/** 顺序拼接编辑器: translate + with 槽位 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ConcatEditor(e: TlElem, onChanged: () -> Unit) {
    Column {
        OutlinedTextField(value = e.key, onValueChange = { e.key = it; onChanged() },
            label = { Text("翻译识别符 (含 %1 %2 占位)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(4.dp))
        Text("槽位 (替换 %1 %2…):", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        e.with.forEachIndexed { i, w ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${i + 1}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.width(22.dp))
                OutlinedTextField(value = w, onValueChange = { e.with[i] = it; onChanged() },
                    singleLine = true, modifier = Modifier.weight(1f), textStyle = MaterialTheme.typography.bodySmall)
                IconButton(onClick = { e.with.removeAt(i); onChanged() }) {
                    Icon(Icons.Default.Close, "删除槽位", Modifier.size(16.dp))
                }
            }
        }
        TextButton(onClick = { e.with.add("%${e.with.size + 1}"); onChanged() }) {
            Icon(Icons.Default.Add, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text("添加槽位")
        }
    }
}
