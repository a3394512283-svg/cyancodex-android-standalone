package com.canglan.cyancodex.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.navigation.NavHostController
import com.canglan.cyancodex.data.AudioToMidi
import com.canglan.cyancodex.data.MidiLibrary
import com.canglan.cyancodex.data.MidiParser
import com.canglan.cyancodex.data.PendingMidi
import com.canglan.cyancodex.ui.Routes
import com.canglan.cyancodex.ui.components.AppTopBar
import com.canglan.cyancodex.ui.components.GlassActionButton
import com.canglan.cyancodex.ui.components.GlassFilterChip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * MIDI 工具页:
 *   ① MP3(及其它音频) 转 MIDI —— 全在手机本地算, 不上传音频
 *   ② MIDI 曲库 —— BitMidi 搜索 + 沧蓝自建精选, 下载后一键导入音乐生成器
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MidiScreen(nav: NavHostController) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    // ---- 转换状态 ----
    var converting by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0f) }
    var status by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<AudioToMidi.Result?>(null) }
    var bpmLabel by remember { mutableStateOf("自动识别") }

    // ---- 曲库状态 ----
    var query by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    var hits by remember { mutableStateOf<List<MidiLibrary.Track>>(emptyList()) }
    var curated by remember { mutableStateOf<List<MidiLibrary.Track>>(emptyList()) }
    var libraryMsg by remember { mutableStateOf("") }
    var busyTrack by remember { mutableStateOf("") }

    val bpmOptions = listOf("自动识别", "90", "100", "120", "140")

    fun importIntoMusic(bytes: ByteArray, label: String) {
        val song = MidiParser.parse(bytes)
        if (song == null) {
            error = "这个文件不是有效的 MIDI，换一个试试"
            return
        }
        PendingMidi.set(bytes, label)
        nav.navigate(Routes.MUSIC)
    }

    fun shareMidi(bytes: ByteArray, name: String) {
        runCatching {
            val dir = File(ctx.cacheDir, "downloads").apply { mkdirs() }
            val f = File(dir, name)
            f.writeBytes(bytes)
            val uri: Uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", f)
            val i = Intent(Intent.ACTION_SEND).apply {
                type = "audio/midi"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            ctx.startActivity(Intent.createChooser(i, "分享 MIDI"))
        }.onFailure { error = "分享失败：${it.message}" }
    }

    val audioPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            converting = true
            error = ""
            result = null
            progress = 0f
            status = "准备中…"
            val bpmOverride = bpmLabel.toIntOrNull() ?: 0
            val r = runCatching {
                withContext(Dispatchers.IO) {
                    AudioToMidi.convert(ctx, uri, bpmOverride) { p, t ->
                        progress = p
                        status = t
                    }
                }
            }
            converting = false
            r.onSuccess {
                result = it
                status = "完成"
            }.onFailure {
                error = it.message ?: "转换失败"
                status = ""
            }
        }
    }

    // 进页面先拉一次自建精选
    LaunchedEffect(Unit) {
        curated = MidiLibrary.curated()
        if (curated.isEmpty()) libraryMsg = "自建精选暂时拉不到（检查网络）；第三方搜索仍可用"
    }

    Scaffold(topBar = { AppTopBar(title = "MIDI 工具", icon = Icons.Default.MusicNote, onBack = { nav.popBackStack() }) }) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // ============ ① 音频转 MIDI ============
            Card {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("音频转 MIDI", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(
                        "选一段人声哼唱、独奏或外放旋律，App 在本地把它转成 MIDI（音频不上传服务器）。" +
                            "这是单旋律扒谱：多声部、重打击乐的音频会丢信息。",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("节拍", style = MaterialTheme.typography.labelLarge)
                        bpmOptions.forEach { opt ->
                            GlassFilterChip(selected = bpmLabel == opt, onClick = { bpmLabel = opt }, label = { Text(opt) })
                        }
                    }
                    GlassActionButton(filled = true,
                        onClick = { audioPicker.launch(arrayOf("audio/*")) },
                        enabled = !converting,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(if (converting) "转换中…" else "选择音频文件开始转换") }

                    if (converting) {
                        LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                        Text(status, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    result?.let { r ->
                        Text(
                            "识别完成：${r.noteCount} 个音符 · 时长 ${r.durationSec}s · 速度 ${r.bpm} BPM",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            GlassActionButton(filled = true, onClick = { importIntoMusic(r.bytes, "音频转 MIDI") }) { Text("导入音乐生成器") }
                            GlassActionButton(onClick = { shareMidi(r.bytes, r.file.name) }) { Text("分享 .mid") }
                        }
                        Text(r.file.absolutePath, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (error.isNotEmpty()) {
                        Text(error, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
                    }
                }
            }

            // ============ ② MIDI 曲库 ============
            Card {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("MIDI 曲库（免费下载）", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        label = { Text("搜索曲名 / 关键词") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        GlassActionButton(filled = true,
                            enabled = query.isNotBlank() && !searching,
                            onClick = {
                                scope.launch {
                                    searching = true
                                    libraryMsg = ""
                                    hits = MidiLibrary.searchBitMidi(query.trim())
                                    if (hits.isEmpty()) libraryMsg = "没搜到结果，换个关键词试试"
                                    searching = false
                                }
                            },
                        ) { Text(if (searching) "搜索中…" else "搜索") }
                        GlassActionButton(onClick = {
                            scope.launch {
                                searching = true
                                libraryMsg = ""
                                curated = MidiLibrary.curated()
                                searching = false
                                if (curated.isEmpty()) libraryMsg = "精选列表拉取失败，稍后再试"
                            }
                        }) { Text("刷新精选") }
                    }
                    Text(
                        "搜索来源：BitMidi（网友上传，仅供个人学习试听）；精选：沧蓝自建，只收公共领域或自制旋律。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    if (libraryMsg.isNotEmpty()) {
                        Text(libraryMsg, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }

                    val all = (if (hits.isNotEmpty()) hits else curated).take(30)
                    if (all.isEmpty() && !searching) {
                        Text("输入关键词搜索，或从下面的精选里挑一首。", style = MaterialTheme.typography.labelMedium)
                    }
                    all.forEach { t ->
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(t.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                                Text(
                                    buildString {
                                        append(if (t.source == "cyan") "沧蓝精选" else "BitMidi")
                                        if (t.plays > 0) append(" · ${t.plays} 次播放")
                                        if (t.sizeKb > 0) append(" · ${t.sizeKb} KB")
                                        if (t.note.isNotEmpty()) append(" · ${t.note}")
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                )
                            }
                            TextButton(
                                enabled = busyTrack != t.id,
                                onClick = {
                                    scope.launch {
                                        busyTrack = t.id
                                        error = ""
                                        runCatching { MidiLibrary.download(t.url) }
                                            .onSuccess { importIntoMusic(it, t.name) }
                                            .onFailure { error = "下载失败：${it.message}" }
                                        busyTrack = ""
                                    }
                                },
                            ) { Text(if (busyTrack == t.id) "下载中" else "下载并导入") }
                        }
                        HorizontalDivider()
                    }
                }
            }

            Text(
                "提示：导入后会自动跳到「音乐生成器」，在那里可以直接试听、调速度、导出指令。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
