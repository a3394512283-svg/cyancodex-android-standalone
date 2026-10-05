package com.canglan.cyancodex.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.canglan.cyancodex.data.Block
import com.canglan.cyancodex.data.BlockKind
import com.canglan.cyancodex.data.PluginBuilder
import com.canglan.cyancodex.plugin.PluginManager
import com.canglan.cyancodex.ui.Routes
import com.canglan.cyancodex.ui.components.AppTopBar
import com.canglan.cyancodex.ui.components.GlassActionButton
import com.canglan.cyancodex.ui.components.GlassFilterChip
import com.canglan.cyancodex.ui.components.TopBarTextAction
import com.canglan.cyancodex.ui.components.copyToClipboard
import org.json.JSONArray
import org.json.JSONObject

/**
 * 插件工坊: 图形化(积木式)制作 CyanCodex 插件, 一键打包成可加载的 JAR
 *
 * 积木 -> 行为规格(cyancodex-plugin.json) -> 与内置运行时 classes.dex 一起打包成 JAR -> 写入插件目录并热重载
 * 全程离线, 不需要编译器
 */
private val PRESET_COLORS = listOf(
    "#0B1A14", "#10352B", "#0E7490", "#1E3A8A", "#4C1D95",
    "#831843", "#7C2D12", "#14532D", "#111827", "#000000",
)

private fun parseColor(hex: String, fallback: Color): Color = try {
    Color(android.graphics.Color.parseColor(hex.trim()))
} catch (e: Exception) { fallback }

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun PluginBuilderScreen(nav: NavHostController) {
    val ctx = LocalContext.current

    // ---------------- 状态 ----------------
    var name by remember { mutableStateOf("") }
    var version by remember { mutableStateOf("1.0.0") }
    var desc by remember { mutableStateOf("") }
    var bgEnabled by remember { mutableStateOf(true) }
    var bgKind by remember { mutableStateOf("gradient") }
    var colorTop by remember { mutableStateOf("#0B1A14") }
    var colorBottom by remember { mutableStateOf("#10352B") }
    val onEnable = remember { mutableStateListOf<Block>() }
    val onDisable = remember { mutableStateListOf<Block>() }

    var msg by remember { mutableStateOf("") }
    var msgErr by remember { mutableStateOf(false) }
    var showAddFor by remember { mutableStateOf<String?>(null) }   // "on" / "off" / null
    var showCode by remember { mutableStateOf(false) }
    var codeText by remember { mutableStateOf("") }
    var loaded by remember { mutableStateOf(false) }

    // ---------------- 草稿: 打开时恢复, 改动时保存 ----------------
    LaunchedEffect(Unit) {
        PluginBuilder.loadDraft(ctx)?.let { raw ->
            runCatching {
                val o = JSONObject(raw)
                name = o.optString("name", "")
                version = o.optString("version", "1.0.0")
                desc = o.optString("description", "")
                val bg = o.optJSONObject("background")
                if (bg != null) {
                    bgEnabled = bg.optBoolean("enabled", true)
                    bgKind = bg.optString("kind", "gradient")
                    colorTop = bg.optString("colorTop", "#0B1A14")
                    colorBottom = bg.optString("colorBottom", "#10352B")
                }
                onEnable.clear(); onEnable.addAll(parseBlocks(o.optJSONArray("onEnable")))
                onDisable.clear(); onDisable.addAll(parseBlocks(o.optJSONArray("onDisable")))
            }
        }
        loaded = true
    }
    fun spec(): String = PluginBuilder.specJson(
        name = name, version = version, description = desc,
        bgEnabled = bgEnabled, bgKind = bgKind, colorTop = colorTop, colorBottom = colorBottom,
        onEnable = onEnable.toList(), onDisable = onDisable.toList(),
    )

    fun javaCode(): String = PluginBuilder.javaPreview(
        name = name, version = version, description = desc,
        bgEnabled = bgEnabled, bgKind = bgKind, colorTop = colorTop, colorBottom = colorBottom,
        onEnable = onEnable.toList(), onDisable = onDisable.toList(),
    )

    LaunchedEffect(loaded, spec()) {
        if (!loaded) return@LaunchedEffect
        PluginBuilder.saveDraft(ctx, spec())
    }

    fun check(): Boolean {
        val err = PluginBuilder.validate(name, version, onEnable.toList(), onDisable.toList())
            ?: if (bgEnabled) PluginBuilder.validateColors(colorTop, colorBottom) else null
        if (err != null) { msg = err; msgErr = true; return false }
        return true
    }

    fun build(enableAfter: Boolean) {
        if (!check()) return
        val id = PluginBuilder.makeId(name)
        runCatching {
            val bytes = PluginBuilder.packJar(ctx, spec())
            val fileName = PluginBuilder.fileNameFor(name)
            val file = PluginManager.install(ctx, fileName, bytes)
                ?: error("写入插件目录失败")
            if (enableAfter) PluginManager.setEnabled(ctx, id, true)
            file
        }.onSuccess { f ->
            msgErr = false
            msg = buildString {
                append("打包成功: ").append(f.name).append("（").append(f.length()).append(" 字节）\n")
                if (enableAfter) append("已自动启用插件「").append(name).append("」\n")
                else append("到「优化设置 → 插件」里打开开关即可生效\n")
                append("插件 id: ").append(id)
            }
        }.onFailure { e ->
            msgErr = true
            msg = "打包失败: " + (e.message ?: e.toString())
        }
    }

    Scaffold(topBar = {
        AppTopBar(
            title = "插件工坊", icon = Icons.Default.Extension,
            onBack = { nav.popBackStack() },
            actions = {
                TopBarTextAction(onClick = { codeText = javaCode(); showCode = true }) { Text("预览代码") }
                TopBarTextAction(onClick = { nav.navigate(Routes.PLUGIN_DEV) }) { Text("教程") }
            },
        )
    }) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // 说明
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(Modifier.padding(12.dp)) {
                    Text("图形化做插件", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    Text(
                        "用积木拼出插件行为, 点「打包 JAR」直接生成可加载的插件文件 (完全离线, 不需要编译器)。" +
                            "打包后会写入插件目录并热重载, 在「优化设置 → 插件」里开开关即可。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // 插件信息
            SectionCard(title = "插件信息", icon = Icons.Default.Build) {
                OutlinedTextField(value = name, onValueChange = { name = it.take(24) },
                    label = { Text("插件名称 (必填)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(value = version, onValueChange = { version = it.take(12) },
                    label = { Text("版本号") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(value = desc, onValueChange = { desc = it.take(60) },
                    label = { Text("插件简介") }, modifier = Modifier.fillMaxWidth())
                if (name.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text("插件 id: ${PluginBuilder.makeId(name)}", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary)
                }
            }

            // 背景积木
            SectionCard(title = "背景积木", icon = Icons.Default.Palette) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("自定义主界面背景", style = MaterialTheme.typography.bodyMedium)
                        Text("打开后, 启用本插件会改变 App 首页背景", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = bgEnabled, onCheckedChange = { bgEnabled = it })
                }
                if (bgEnabled) {
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        GlassFilterChip(selected = bgKind == "gradient", onClick = { bgKind = "gradient" },
                            label = { Text("上下渐变") })
                        GlassFilterChip(selected = bgKind == "color", onClick = { bgKind = "color" },
                            label = { Text("纯色") })
                    }
                    Spacer(Modifier.height(8.dp))
                    // 预览
                    Box(
                        Modifier.fillMaxWidth().height(52.dp).clip(RoundedCornerShape(10.dp)).background(
                            if (bgKind == "color") Brush.horizontalGradient(listOf(parseColor(colorTop, Color.Black), parseColor(colorTop, Color.Black)))
                            else Brush.verticalGradient(listOf(parseColor(colorTop, Color.Black), parseColor(colorBottom, Color(0xFF10352B))))
                        ).border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(10.dp)),
                    )
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(value = colorTop, onValueChange = { colorTop = it.take(7) },
                            label = { Text("起始色") }, singleLine = true, modifier = Modifier.weight(1f))
                        if (bgKind != "color") {
                            OutlinedTextField(value = colorBottom, onValueChange = { colorBottom = it.take(7) },
                                label = { Text("结束色") }, singleLine = true, modifier = Modifier.weight(1f))
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text("常用颜色", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        PRESET_COLORS.forEach { hex ->
                            Box(
                                Modifier.size(26.dp).clip(RoundedCornerShape(6.dp))
                                    .background(parseColor(hex, Color.Black))
                                    .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(6.dp))
                                    .clickable { if (bgKind == "color") { colorTop = hex } else colorBottom = hex },
                            )
                        }
                    }
                    Text("点色块填到" + (if (bgKind == "color") "颜色" else "结束色") + "；起始色直接输入",
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            // 启用时积木
            BlockListCard(
                title = "启用插件时执行", blocks = onEnable,
                onAdd = { showAddFor = "on" },
                onMove = { i, d -> moveBlock(onEnable, i, d) },
                onDelete = { i -> if (i in onEnable.indices) onEnable.removeAt(i) },
            )

            // 禁用时积木
            BlockListCard(
                title = "禁用插件时执行", blocks = onDisable,
                onAdd = { showAddFor = "off" },
                onMove = { i, d -> moveBlock(onDisable, i, d) },
                onDelete = { i -> if (i in onDisable.indices) onDisable.removeAt(i) },
            )

            // 操作
            Card {
                Column(Modifier.padding(12.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        GlassActionButton(filled = true, onClick = { build(false) }, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Default.Build, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("打包 JAR")
                        }
                        GlassActionButton(onClick = { build(true) }, modifier = Modifier.weight(1f)) {
                            Text("打包并启用")
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                        TextButton(onClick = { codeText = javaCode(); showCode = true }) { Text("预览生成的 Java 代码") }
                        TextButton(onClick = {
                            name = ""; version = "1.0.0"; desc = ""
                            bgEnabled = true; bgKind = "gradient"; colorTop = "#0B1A14"; colorBottom = "#10352B"
                            onEnable.clear(); onDisable.clear()
                            msg = "已清空，可以重新开始"; msgErr = false
                        }) { Text("清空重做") }
                    }
                    if (msg.isNotBlank()) {
                        Spacer(Modifier.height(8.dp))
                        Card(colors = CardDefaults.cardColors(
                            containerColor = if (msgErr) MaterialTheme.colorScheme.errorContainer
                            else MaterialTheme.colorScheme.primaryContainer)) {
                            Column(Modifier.padding(10.dp)) {
                                Text(msg, style = MaterialTheme.typography.labelMedium,
                                    color = if (msgErr) MaterialTheme.colorScheme.onErrorContainer
                                    else MaterialTheme.colorScheme.onPrimaryContainer)
                                if (!msgErr && msg.contains("打包成功")) {
                                    Spacer(Modifier.height(6.dp))
                                    TextButton(onClick = { nav.navigate(Routes.PLUGINS) }) { Text("打开插件库") }
                                }
                            }
                        }
                    }
                }
            }

            // 插件目录提示
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(Modifier.padding(12.dp)) {
                    Text("插件目录", style = MaterialTheme.typography.labelMedium)
                    Text(PluginManager.pluginDir(ctx).absolutePath,
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.secondary)
                    Text("打包好的 JAR 也会出现在这里, 可以直接分享给其他 CyanCodex 用户。",
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.height(20.dp))
        }
    }

    // 添加积木
    showAddFor?.let { target ->
        AlertDialog(
            onDismissRequest = { showAddFor = null },
            title = { Text(if (target == "on") "启用时执行 · 添加积木" else "禁用时执行 · 添加积木") },
            text = {
                Column {
                    BlockKind.entries.groupBy { it.group }.forEach { (group, kinds) ->
                        Text(group, style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 4.dp))
                        kinds.forEach { k ->
                            Card(
                                onClick = {
                                    val b = newBlock(k)
                                    if (target == "on") onEnable.add(b) else onDisable.add(b)
                                    showAddFor = null
                                },
                                modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                            ) {
                                Column(Modifier.padding(10.dp)) {
                                    Text(k.label, style = MaterialTheme.typography.titleSmall)
                                    Text(k.hint, style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showAddFor = null }) { Text("取消") } },
        )
    }

    // 代码预览
    if (showCode) {
        AlertDialog(
            onDismissRequest = { showCode = false },
            title = { Text("等价的 Java 代码") },
            text = {
                Column {
                    Text("这是你当前积木对应的 Java 写法, 供学习参考; 打包时实际使用的是同一套行为的运行时+规格。",
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(6.dp))
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                        Text(codeText, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            modifier = Modifier.padding(8.dp).heightIn(max = 380.dp).verticalScroll(rememberScrollState()))
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { copyToClipboard(ctx, codeText, "已复制 Java 代码") }) { Text("复制") }
            },
            dismissButton = { TextButton(onClick = { showCode = false }) { Text("关闭") } },
        )
    }
}

// ---------------- 辅助 ----------------

private fun newBlock(k: BlockKind): Block = when (k) {
    BlockKind.LOG -> Block(k, message = "插件已启用")
    BlockKind.TOAST -> Block(k, message = "欢迎使用我的插件")
    BlockKind.SET_PREF -> Block(k, key = "last_enabled", value = "yes")
    BlockKind.LOG_PREF -> Block(k, key = "last_enabled", label = "上次启用: ")
    BlockKind.CLEAR_PREF -> Block(k, key = "last_enabled")
}

private fun <T> moveBlock(list: MutableList<T>, i: Int, d: Int) {
    val j = i + d
    if (i in list.indices && j in list.indices) {
        val t = list[i]; list[i] = list[j]; list[j] = t
    }
}

private fun parseBlocks(arr: JSONArray?): List<Block> {
    if (arr == null) return emptyList()
    val out = mutableListOf<Block>()
    for (i in 0 until arr.length()) {
        val o = arr.optJSONObject(i) ?: continue
        val kind = BlockKind.entries.firstOrNull { it.type == o.optString("type") } ?: continue
        out.add(Block(
            kind = kind,
            message = o.optString("message", ""),
            key = o.optString("key", ""),
            value = o.optString("value", ""),
            label = o.optString("label", ""),
        ))
    }
    return out
}

@Composable
private fun SectionCard(title: String, icon: androidx.compose.ui.graphics.vector.ImageVector, content: @Composable ColumnScope.() -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(6.dp))
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

@Composable
private fun BlockListCard(
    title: String,
    blocks: List<Block>,
    onAdd: () -> Unit,
    onMove: (Int, Int) -> Unit,
    onDelete: (Int) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f))
                GlassActionButton(onClick = onAdd) {
                    Icon(Icons.Default.Add, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text("添加积木")
                }
            }
            if (blocks.isEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text("还没有积木（不添加也可以打包）", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            blocks.forEachIndexed { i, b ->
                Spacer(Modifier.height(8.dp))
                BlockCard(b, i, blocks.size, { d -> onMove(i, d) }, { onDelete(i) })
            }
        }
    }
}

@Composable
private fun BlockCard(b: Block, index: Int, total: Int, onMove: (Int) -> Unit, onDelete: () -> Unit) {
    val accent = when (b.kind.group) {
        "提示" -> Color(0xFFFB923C)
        "配置" -> Color(0xFFA78BFA)
        else -> Color(0xFF38BDF8)
    }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).clip(RoundedCornerShape(3.dp)).background(accent))
                Spacer(Modifier.width(8.dp))
                Text("${index + 1}. ${b.kind.label}", style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f))
                IconButton(onClick = { onMove(-1) }, enabled = index > 0) {
                    Icon(Icons.Default.KeyboardArrowUp, "上移", Modifier.size(18.dp))
                }
                IconButton(onClick = { onMove(1) }, enabled = index < total - 1) {
                    Icon(Icons.Default.KeyboardArrowDown, "下移", Modifier.size(18.dp))
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Default.DeleteOutline, "删除", Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.error)
                }
            }
            Spacer(Modifier.height(4.dp))
            when (b.kind) {
                BlockKind.LOG, BlockKind.TOAST -> OutlinedTextField(
                    value = b.message, onValueChange = { b.message = it },
                    label = { Text("内容") }, modifier = Modifier.fillMaxWidth())
                BlockKind.SET_PREF -> {
                    OutlinedTextField(value = b.key, onValueChange = { b.key = it },
                        label = { Text("配置名") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(6.dp))
                    OutlinedTextField(value = b.value, onValueChange = { b.value = it },
                        label = { Text("配置值") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                }
                BlockKind.LOG_PREF -> {
                    OutlinedTextField(value = b.key, onValueChange = { b.key = it },
                        label = { Text("配置名") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(6.dp))
                    OutlinedTextField(value = b.label, onValueChange = { b.label = it },
                        label = { Text("日志前缀(可空)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                }
                BlockKind.CLEAR_PREF -> OutlinedTextField(
                    value = b.key, onValueChange = { b.key = it },
                    label = { Text("配置名") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}
