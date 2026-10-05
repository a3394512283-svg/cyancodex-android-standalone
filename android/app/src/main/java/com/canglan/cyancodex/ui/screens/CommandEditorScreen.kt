package com.canglan.cyancodex.ui.screens

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.canglan.cyancodex.data.ApiClient
import com.canglan.cyancodex.data.CmdFileIn
import com.canglan.cyancodex.data.CommandBody
import com.canglan.cyancodex.data.CommandFile
import com.canglan.cyancodex.data.QuickCommand
import com.canglan.cyancodex.data.Session
import com.canglan.cyancodex.ui.AppState
import com.canglan.cyancodex.ui.Routes
import com.canglan.cyancodex.ui.components.AppTopBar
import com.canglan.cyancodex.ui.components.GlassActionButton
import com.canglan.cyancodex.ui.components.GlassFilterChip
import com.canglan.cyancodex.ui.components.RemoteImage
import com.canglan.cyancodex.ui.components.TopBarAction
import com.canglan.cyancodex.ui.components.copyToClipboard
import com.canglan.cyancodex.ui.components.safeApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** MIME -> 扩展名 (附件名拿不到扩展名时兜底, 必须落在服务端白名单里) */
private fun mimeExt(mime: String?): String? {
    val m = (mime ?: "").lowercase()
    return when {
        m.isBlank() -> null
        m.contains("json") -> "json"
        m.contains("plain") -> "txt"
        m.contains("markdown") -> "md"
        m.contains("csv") -> "csv"
        m.contains("zip") -> "zip"
        m.contains("png") -> "png"
        m.contains("jpeg") || m.contains("jpg") -> "jpg"
        m.contains("gif") -> "gif"
        m.contains("webp") -> "webp"
        m.contains("pdf") -> "pdf"
        else -> null
    }
}

/**
 * 指令编辑器: 快捷编辑/修改指令, 可上传到指令库 (支持附件)
 *
 * @param initialMc    预填的适用版本 (AI 回答里那个 `mc 1.20+` 语言标记)。空串 = 用默认的 1.20+
 * @param initialCat   预填的分类。空串 = other
 * @param initialTitle 预填的指令名 (AI 代码块首行的 `# 标题`)。空串 = 留空让用户自己写
 *
 * ⚠️ 三个新参数都给了默认值 —— 老调用点 (`Routes.editor()` / `Routes.editor(cmd, id)`)
 *    一个字都不用改, 加参数不会把别处编译弄挂。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CommandEditorScreen(
    nav: NavHostController,
    initialCmd: String,
    editId: Long,
    initialMc: String = "",
    initialCat: String = "",
    initialTitle: String = "",
) {
    val scope = rememberCoroutineScope()
    val ctx = androidx.compose.ui.platform.LocalContext.current

    var lines by remember {
        mutableStateOf(initialCmd.split("\n").map { it.trim() }.filter { it.isNotBlank() }.ifEmpty { listOf("") })
    }
    var title by remember { mutableStateOf(initialTitle) }
    var description by remember { mutableStateOf("") }
    var category by remember { mutableStateOf(initialCat.ifBlank { "other" }) }
    var tags by remember { mutableStateOf("") }
    var mcVersion by remember { mutableStateOf(initialMc.ifBlank { "1.20+" }) }
    // 付费指令 (意见 #83): 0=免费
    var price by remember { mutableStateOf("") }
    // 命令方块属性 (意见 #27: 可视化选择 + 颜色标识)
    var blockType by remember { mutableStateOf("pulse") }      // pulse/chain/cycle/chat
    var chainMode by remember { mutableStateOf("off") }        // on/off
    var redstone by remember { mutableStateOf("keep") }        // keep/need
    var delayTicks by remember { mutableStateOf("0") }         // 延迟刻 0-500
    var saving by remember { mutableStateOf(false) }
    var saved by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf("") }
    // 命令补全 (参考 CHelper Auto Suggestion): 当前行索引 -> 建议列表
    var assistIdx by remember { mutableStateOf(-1) }
    var assistList by remember { mutableStateOf<List<String>>(emptyList()) }

    // ===== 附件 (可以添加附件, 也可以只发附件: 有人是用文件形式发指令的) =====
    var picked by remember { mutableStateOf<List<Pair<String, Uri>>>(emptyList()) }   // 新选的: 文件名 -> uri
    var existingFiles by remember { mutableStateOf<List<CommandFile>>(emptyList()) }  // 已有的(编辑时)
    var removeFileIds by remember { mutableStateOf<List<Long>>(emptyList()) }
    var fileErr by remember { mutableStateOf("") }

    /** 刷新某行的补全建议: 行以 / 开头时启用 */
    fun refreshAssist(idx: Int) {
        val text = lines.getOrNull(idx) ?: ""
        if (!text.trimStart().startsWith("/")) {
            assistIdx = -1
            assistList = emptyList()
            return
        }
        assistIdx = idx
        assistList = com.canglan.cyancodex.data.CommandAssist.suggest(text)
    }

    /** 用建议替换当前参数: 保留命令和已确定的参数, 替换最后一个片段 */
    fun applyAssist(suggestion: String) {
        if (assistIdx !in lines.indices) return
        val line = lines[assistIdx]
        val t = line.trimStart()
        if (!t.startsWith("/")) return
        // 命令名位置
        val cmdMatch = Regex("^/([a-zA-Z_]+)").find(t)
        val idx = assistIdx
        val newLine = if (cmdMatch == null) {
            // 补全命令名
            suggestion
        } else {
            val head = t.substring(0, cmdMatch.range.last + 1) // 含 /cmd
            val after = t.substring(cmdMatch.range.last + 1)
            val segs = after.split(" ").toMutableList()
            // 去掉末尾空片段(刚打完空格)或替换最后片段
            if (segs.isNotEmpty() && segs.last().isBlank()) segs.removeAt(segs.size - 1)
            if (segs.isEmpty()) head + " " + suggestion
            else {
                segs[segs.size - 1] = suggestion
                head + " " + segs.filter { it.isNotBlank() }.joinToString(" ")
            }
        }
        lines = lines.toMutableList().also { it[idx] = newLine.trimEnd() }
        refreshAssist(idx)
    }
    // 整段粘贴对话框 (意见 #29: 恢复整段编辑, 换行自动分析)
    var showPasteDialog by remember { mutableStateOf(false) }
    var pasteText by remember { mutableStateOf("") }
    // 效果图 (保存成功后附加, 意见反馈: 指令库想看图片)
    var currentId by remember { mutableStateOf(editId) }
    var uploadedImage by remember { mutableStateOf<String?>(null) }
    var uploading by remember { mutableStateOf(false) }

    // 编辑模式: 加载指令完整数据 (标题/说明/分类/标签/效果图) (意见 #49: 编辑不能丢字段)
    LaunchedEffect(editId) {
        if (editId > 0) {
            val r = safeApi { ApiClient.get().commandDetail(editId) }
            if (r.ok) {
                val c = r.data
                if (c != null) {
                    title = c.title ?: ""
                    description = c.description ?: ""
                    category = c.category ?: "other"
                    tags = c.tags ?: ""
                    mcVersion = c.mcVersion
                    price = if (c.price > 0) c.price.toString() else ""
                    uploadedImage = c.image
                    existingFiles = c.files
                    removeFileIds = emptyList()
                    if (lines.size == 1 && lines[0].isBlank()) {
                        lines = (c.command ?: "").split("\n").map { it.trim() }.filter { it.isNotBlank() }.ifEmpty { listOf("") }
                    }
                    msg = ""
                } else msg = "指令不存在或无权编辑"
            } else msg = r.error ?: "加载失败"
        }
    }
    val imgPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null && currentId > 0 && !uploading) {
            scope.launch {
                uploading = true
                try {
                    val bytes = withContext(Dispatchers.IO) {
                        val bmp = ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) } ?: return@withContext null
                        val max = 900f
                        val scale = minOf(1f, max / maxOf(bmp.width.toFloat(), bmp.height.toFloat()))
                        val out = Bitmap.createScaledBitmap(bmp,
                            (bmp.width * scale).toInt().coerceAtLeast(1), (bmp.height * scale).toInt().coerceAtLeast(1), true)
                        if (out != bmp) bmp.recycle()
                        val bos = java.io.ByteArrayOutputStream()
                        out.compress(Bitmap.CompressFormat.JPEG, 80, bos)
                        out.recycle()
                        android.util.Base64.encodeToString(bos.toByteArray(), android.util.Base64.DEFAULT)
                    }
                    if (bytes != null) {
                        val r = safeApi { ApiClient.get().uploadCommandImage(currentId, mapOf("data" to bytes, "ext" to "jpg")) }
                        if (r.ok) { uploadedImage = r.data?.image; msg = "效果图上传成功" }
                        else msg = r.error ?: "效果图上传失败"
                    } else msg = "图片读取失败"
                } catch (e: Exception) {
                    msg = "图片处理失败: ${e.message}"
                } finally { uploading = false }
            }
        }
    }
    // 直接打开指令文件 (TXT, 每行一个命令块) —— 意见 id=17
    val fileLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                val text = runCatching {
                    ctx.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
                }.getOrNull() ?: ""
                val loaded = text.split("\n").map { it.trim() }.filter { it.isNotBlank() }
                if (loaded.isEmpty()) msg = "文件为空或无法读取（支持 .txt，每行一个命令块）"
                else {
                    lines = loaded.toMutableList()
                    msg = "已加载 ${loaded.size} 个命令块"
                }
            }
        }
    }

    fun command(): String = lines.joinToString("\n") { it.trim() }.trim()

    /** 命令方块属性编码进 tags: __block=xx|__chain=xx|__redstone=xx|__delay=xx (意见 #27) */
    fun finalTags(): String {
        val props = listOf(
            "__block=$blockType", "__chain=$chainMode", "__redstone=$redstone",
            "__delay=${delayTicks.toIntOrNull()?.coerceIn(0, 500) ?: 0}"
        )
        val base = tags.split(",").map { it.trim() }.filter { it.isNotBlank() && !it.startsWith("__") }
        return (base + props).joinToString(",")
    }

    val snippets = listOf(
        "@p", "@a", "@e", "@s", "~", "^", "true", "false",
        "/execute as @a at @s run ", "/tp @s ", "/give @s ",
        "/effect @a ", "/scoreboard objectives add ",
        """/titleraw @a title {"rawtext":[{"text":"","color":"gold"}]}""",
    )

    // 记录当前编辑中的行 (#53: 快捷插入应落在当前行, 而不是固定最后一行)
    var focusLine by remember { mutableStateOf(-1) }

    val attachPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris: List<Uri> ->
        if (uris.isNotEmpty()) {
            val add = uris.map { u ->
                // 文件名: 显式查询 DISPLAY_NAME; 拿不到就用 Uri 末段; 都没有扩展名时按 MIME 补一个
                // (否则服务端会按"未知格式"直接拒绝, 用户会一头雾水)
                val display = runCatching {
                    ctx.contentResolver.query(
                        u,
                        arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),
                        null, null, null,
                    )?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
                }.getOrNull()
                val tail = u.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
                var nm = display?.takeIf { it.isNotBlank() } ?: tail ?: "file"
                if (!nm.contains('.')) {
                    val ext = mimeExt(runCatching { ctx.contentResolver.getType(u) }.getOrNull()) ?: "txt"
                    nm = "$nm.$ext"
                }
                nm to u
            }
            val merged = (picked + add).take(5)
            if (picked.size + add.size > 5) fileErr = "最多 5 个附件，多余的已忽略"
            picked = merged
        }
    }

    fun insert(s: String) {
        val idx = if (focusLine in lines.indices) focusLine else lines.size - 1
        lines = lines.toMutableList().also { it[idx] = it[idx] + s }
    }

    fun saveToLibrary() {
        val hasAttach = picked.isNotEmpty() || (existingFiles.size - removeFileIds.size) > 0
        if (title.isBlank()) { msg = "请填写标题"; return }
        if (command().isBlank() && !hasAttach) { msg = "请填写指令内容，或添加一个附件（可以只发附件）"; return }
        scope.launch {
            saving = true
            try {
                // 读取待上传附件 (base64)
                val payload = withContext(Dispatchers.IO) {
                    var totalBytes = 0
                    val list = mutableListOf<CmdFileIn>()
                    for ((name, uri) in picked) {
                        // 意见 #101: 先按文件大小拦掉超大附件, 再读进内存, 避免 OOM 直接闪退
                        val declared = runCatching {
                            ctx.contentResolver.query(
                                uri,
                                arrayOf(android.provider.OpenableColumns.SIZE),
                                null, null, null,
                            )?.use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else -1L } ?: -1L
                        }.getOrDefault(-1L)
                        if (declared > 6 * 1024 * 1024) {
                            fileErr = "附件「$name」超过 6MB"
                            continue
                        }
                        val bytes = runCatching {
                            ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        }.getOrNull()
                        if (bytes == null) { fileErr = "附件「$name」读取失败"; continue }
                        totalBytes += bytes.size
                        if (bytes.size > 6 * 1024 * 1024) { fileErr = "附件「$name」超过 6MB"; continue }
                        val b64 = runCatching {
                            android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
                        }.getOrNull()
                        if (b64 == null) { fileErr = "附件「$name」编码失败（文件可能过大）"; continue }
                        list.add(CmdFileIn(name, b64))
                    }
                    if (totalBytes > 8 * 1024 * 1024) { fileErr = "附件合计超过 8MB，请减少后再传"; return@withContext emptyList<CmdFileIn>() }
                    list
                }
                if (picked.isNotEmpty() && payload.isEmpty()) {
                    msg = fileErr.ifBlank { "附件读取失败" }
                    return@launch
                }
                val body = CommandBody(
                    title = title, description = description, command = command(),
                    category = category, mcVersion = mcVersion, tags = finalTags(),
                    price = price.trim().ifEmpty { "0" },
                    files = payload, removeFiles = removeFileIds,
                )
                val r = if (editId > 0) {
                    safeApi { ApiClient.get().updateCommand(editId, body) }
                } else {
                    safeApi { ApiClient.get().uploadCommand(body) }
                }
                if (r.ok) {
                    saved = true
                    val newId = (r.data as? com.canglan.cyancodex.data.IdResp)?.id
                    currentId = if (editId > 0) editId else (newId ?: 0)
                    val warn = fileErr
                    msg = if (warn.isNotBlank()) "保存成功（$warn）" else "保存成功"
                    picked = emptyList()
                    removeFileIds = emptyList()
                    if (editId > 0) {
                        // 编辑后刷新已有附件列表
                        val d = safeApi { ApiClient.get().commandDetail(editId) }
                        if (d.ok && d.data != null) existingFiles = d.data.files
                    }
                    if (editId <= 0) {
                        android.widget.Toast.makeText(ctx, "已提交审核，审核通过后将在指令库展示", android.widget.Toast.LENGTH_LONG).show()
                    }
                } else msg = r.error ?: "保存失败"
            } finally { saving = false }
        }
    }

    Scaffold(
        topBar = {
            AppTopBar(title = if (editId > 0) "编辑指令" else "指令编辑器", icon = Icons.Default.Edit,
                onBack = { nav.popBackStack() }, actions = {
                    TopBarAction(onClick = { fileLauncher.launch(arrayOf("text/plain", "text/*", "*/*")) }) {
                        Icon(Icons.Default.FileUpload, "打开指令文件", Modifier.size(20.dp))
                    }
                    TopBarAction(onClick = { nav.navigate(Routes.MC_ID) }) {
                        Icon(Icons.Default.Search, "快捷查询 ID", Modifier.size(20.dp))
                    }
                    TopBarAction(onClick = {
                        copyToClipboard(ctx, command())
                    }) { Icon(Icons.Default.ContentCopy, "复制指令", Modifier.size(20.dp)) }
                    TopBarAction(onClick = {
                        Session.addQuickCommand(QuickCommand(title.ifBlank { "编辑器" }, command()))
                        Session.addRecent(command())
                        android.widget.Toast.makeText(ctx, "已加入快捷指令", android.widget.Toast.LENGTH_SHORT).show()
                    }) { Icon(Icons.Default.Star, "加入快捷指令", Modifier.size(20.dp)) }
                })
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
            if (msg.isNotBlank()) {
                Text(msg, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            OutlinedTextField(
                value = title, onValueChange = { title = it },
                label = { Text("标题 (保存到指令库需要)") },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(10.dp))
            Text("指令内容（每行 = 一个命令块，可分别复制粘贴到命令方块/聊天框）",
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(6.dp))
            lines.forEachIndexed { idx, line ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${idx + 1}", style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.width(20.dp))
                    OutlinedTextField(
                        value = line, onValueChange = { v ->
                            // 意见 #39: 粘贴/输入多行时自动按行拆分命令块
                            if (v.contains('\n')) {
                                val parts = v.split('\n').map { it.trim() }
                                val newLines = lines.toMutableList()
                                newLines[idx] = parts[0]
                                // 把后续行插入到当前行之后
                                val rest = parts.drop(1).filter { it.isNotBlank() }
                                if (rest.isNotEmpty()) newLines.addAll(idx + 1, rest)
                                lines = newLines
                                refreshAssist(idx)
                            } else {
                                lines = lines.toMutableList().also { it[idx] = v }
                                refreshAssist(idx)
                            }
                        },
                        label = { Text("命令块 ${idx + 1}") },
                        modifier = Modifier.weight(1f)
                            .onFocusChanged { if (it.isFocused) focusLine = idx },
                        textStyle = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace),
                        // 语法高亮 (CHelper 式): 指令名/选择器/数字/字符串/NBT 实时着色
                        visualTransformation = androidx.compose.ui.text.input.VisualTransformation { s ->
                            androidx.compose.ui.text.input.TransformedText(
                                com.canglan.cyancodex.ui.components.BedrockHighlight.highlight(s.text),
                                androidx.compose.ui.text.input.OffsetMapping.Identity,
                            )
                        },
                    )
                    if (lines.size > 1) {
                        IconButton(onClick = { lines = lines.filterIndexed { i, _ -> i != idx } }) {
                            Icon(Icons.Default.Close, "删除该行", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                // 命令补全建议 (CHelper 式自动补全)
                if (assistIdx == idx && assistList.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(assistList) { s ->
                            SuggestionChip(
                                onClick = { applyAssist(s) },
                                label = { Text(s, fontFamily = FontFamily.Monospace, fontSize = 12.sp) },
                            )
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GlassActionButton(onClick = { lines = lines + "" }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.Add, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("添加一行")
                }
                GlassActionButton(onClick = { showPasteDialog = true; pasteText = "" }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.ContentPaste, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("整段粘贴")
                }
            }
            Spacer(Modifier.height(10.dp))

            // 快捷插入
            Text("快捷插入:", style = MaterialTheme.typography.labelMedium)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(snippets) { s ->
                    SuggestionChip(onClick = { insert(s) }, label = { Text(s, fontFamily = FontFamily.Monospace) })
                }
            }
            Spacer(Modifier.height(10.dp))

            OutlinedTextField(value = description, onValueChange = { description = it },
                label = { Text("说明") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(10.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                // 分类: 用行内 chips 选择
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(listOf("survival" to "生存", "adventure" to "冒险", "redstone" to "红石",
                        "scoreboard" to "计分板", "gamerule" to "游戏规则", "other" to "其他")) { (k, v) ->
                        GlassFilterChip(selected = category == k, onClick = { category = k }, label = { Text(v) })
                    }
                }
            }
            Spacer(Modifier.height(10.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(value = tags, onValueChange = { tags = it },
                    label = { Text("标签(逗号分隔)") }, singleLine = true, modifier = Modifier.weight(1f))
                OutlinedTextField(value = mcVersion, onValueChange = { mcVersion = it },
                    label = { Text("MC版本") }, singleLine = true, modifier = Modifier.weight(1f))
            }
            Spacer(Modifier.height(10.dp))
            // 付费指令 (意见 #83): 创作者可设置积分价格, 0 或留空=免费
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = price,
                    onValueChange = { price = it.filter { c -> c.isDigit() }.take(5) },
                    label = { Text("积分价格 (0或留空=免费)") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
                )
                Spacer(Modifier.width(10.dp))
                Text("设为付费后，其他人需支付积分购买才能查看指令内容，积分直接到你的账户",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1.4f))
            }
            Spacer(Modifier.height(12.dp))

            // ---- 命令方块属性 (意见 #27: 可视化选择 + 颜色标识) ----
            Text("命令方块属性:", style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.height(6.dp))

            // 1. 方块类型
            Text("方块类型", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(4.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(listOf(
                    Triple("pulse", "脉冲", Color(0xFFFBBF24)),
                    Triple("chain", "连锁", Color(0xFF4ADE80)),
                    Triple("cycle", "循环", Color(0xFFC084FC)),
                    Triple("chat", "聊天栏", Color(0xFF94A3B8)),
                )) { (k, v, c) ->
                    GlassFilterChip(
                        selected = blockType == k,
                        onClick = { blockType = k },
                        label = { Text(v, color = if (blockType == k) c else MaterialTheme.colorScheme.onSurfaceVariant) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = c.copy(alpha = 0.2f),
                            selectedLabelColor = c,
                        ),
                        // 玻璃路径读不到 SelectableChipColors(internal), 定制配色要再给一份
                        glassAccent = c,
                    )
                }
            }
            Spacer(Modifier.height(8.dp))

            // 2. 连锁模式
            Text("连锁模式", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(4.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(listOf(
                    Triple("on", "连锁", Color(0xFF4ADE80)),
                    Triple("off", "无连锁", Color(0xFF94A3B8)),
                )) { (k, v, c) ->
                    GlassFilterChip(
                        selected = chainMode == k,
                        onClick = { chainMode = k },
                        label = { Text(v, color = if (chainMode == k) c else MaterialTheme.colorScheme.onSurfaceVariant) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = c.copy(alpha = 0.2f),
                            selectedLabelColor = c,
                        ),
                        // 玻璃路径读不到 SelectableChipColors(internal), 定制配色要再给一份
                        glassAccent = c,
                    )
                }
            }
            Spacer(Modifier.height(8.dp))

            // 3. 红石控制
            Text("红石控制", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(4.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(listOf(
                    Triple("keep", "保持开启", Color(0xFF4ADE80)),
                    Triple("need", "需要红石", Color(0xFFF87171)),
                )) { (k, v, c) ->
                    GlassFilterChip(
                        selected = redstone == k,
                        onClick = { redstone = k },
                        label = { Text(v, color = if (redstone == k) c else MaterialTheme.colorScheme.onSurfaceVariant) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = c.copy(alpha = 0.2f),
                            selectedLabelColor = c,
                        ),
                        // 玻璃路径读不到 SelectableChipColors(internal), 定制配色要再给一份
                        glassAccent = c,
                    )
                }
            }
            Spacer(Modifier.height(8.dp))

            // 4. 延迟刻
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("延迟刻", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(8.dp))
                OutlinedTextField(
                    value = delayTicks, onValueChange = { v ->
                        delayTicks = v.filter { it.isDigit() }.take(3)
                    },
                    label = { Text("0-500") },
                    singleLine = true, modifier = Modifier.width(120.dp),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
                )
            }
            Spacer(Modifier.height(16.dp))

            // ===== 附件 (支持只发附件) =====
            HorizontalDivider()
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("附件", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                Text("${existingFiles.size - removeFileIds.size + picked.size}/5",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(4.dp))
            Text("用文件形式发指令时可以只加附件、指令内容留空。支持 txt / json / mcfunction / md / csv / 图片 / zip / mcpack 等，最多 5 个，单个 ≤6MB、合计 ≤8MB；文件里的内容同样会经过审核。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            existingFiles.filter { it.id !in removeFileIds }.forEach { f ->
                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.FileUpload, null, Modifier.size(15.dp),
                        tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(6.dp))
                    Text("${f.name}  (${f.sizeText})", style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f), maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    TextButton(onClick = { removeFileIds = removeFileIds + f.id }) {
                        Text("移除", color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
            picked.forEachIndexed { idx, (name, _) ->
                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Add, null, Modifier.size(15.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(6.dp))
                    Text(name, style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f), maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    TextButton(onClick = { picked = picked.toMutableList().also { it.removeAt(idx) } }) {
                        Text("移除", color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
            if (fileErr.isNotBlank()) {
                Text(fileErr, style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error)
            }
            GlassActionButton(
                onClick = { attachPicker.launch(arrayOf("*/*")) },
                enabled = existingFiles.size - removeFileIds.size + picked.size < 5,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Default.FileUpload, null, Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("添加附件")
            }
            Spacer(Modifier.height(16.dp))

            if (AppState.token != null) {
                GlassActionButton(filled = true, onClick = { saveToLibrary() }, enabled = !saving, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Save, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(if (saving) "保存中…" else if (editId > 0) "更新到指令库" else "上传到指令库")
                }
            } else {
                GlassActionButton(onClick = { nav.navigate(Routes.LOGIN) }, modifier = Modifier.fillMaxWidth()) {
                    Text("登录后可上传到指令库")
                }
            }

            // 效果图上传/管理 (意见 #47: 图片功能入口始终可见; 新建保存前禁用并提示)
            Spacer(Modifier.height(8.dp))
            GlassActionButton(
                onClick = {
                    if (currentId <= 0) {
                        msg = "请先保存/上传指令，再添加效果图"
                        return@GlassActionButton
                    }
                    imgPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                },
                enabled = !uploading,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Default.Image, null, Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(if (uploading) "上传中…" else if (currentId <= 0) "先保存后可上传效果图" else if (uploadedImage != null) "更换效果图" else "上传效果图（可选）")
            }
            if (!uploadedImage.isNullOrBlank()) {
                Spacer(Modifier.height(8.dp))
                RemoteImage(uploadedImage, modifier = Modifier.fillMaxWidth().height(160.dp), corner = 10.dp)
                TextButton(onClick = {
                    if (currentId <= 0) return@TextButton
                    scope.launch {
                        val r = safeApi { ApiClient.get().deleteCommandImage(currentId) }
                        if (r.ok) { uploadedImage = null; msg = "效果图已移除" }
                        else msg = r.error ?: "移除失败"
                    }
                }, modifier = Modifier.align(Alignment.End)) {
                    Text("移除效果图", color = MaterialTheme.colorScheme.error)
                }
            }
            Text("效果图展示指令运行效果，审核通过后与指令一起展示", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

    // 整段粘贴: 多行文本按换行自动拆分为命令块 (意见 #29)
    if (showPasteDialog) {
        AlertDialog(
            onDismissRequest = { showPasteDialog = false },
            title = { Text("整段粘贴") },
            text = {
                Column {
                    Text("粘贴整段指令文本，每行自动成为一个命令块。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = pasteText, onValueChange = { pasteText = it },
                        placeholder = { Text("/give @p diamond 1\n/say 你好\n/tp @p 0 64 0") },
                        modifier = Modifier.fillMaxWidth().height(160.dp),
                        textStyle = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val loaded = pasteText.split("\n").map { it.trim() }.filter { it.isNotBlank() }
                    if (loaded.isEmpty()) {
                        msg = "没有解析到任何指令，请检查粘贴内容"
                    } else {
                        val merged = lines.filter { it.isNotBlank() } + loaded
                        lines = merged.toMutableList()
                        msg = "已解析 ${loaded.size} 个命令块"
                    }
                    showPasteDialog = false
                }) { Text("解析填入") }
            },
            dismissButton = { TextButton(onClick = { showPasteDialog = false }) { Text("取消") } },
        )
    }
}
