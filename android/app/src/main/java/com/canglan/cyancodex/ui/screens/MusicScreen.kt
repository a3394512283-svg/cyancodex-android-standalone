package com.canglan.cyancodex.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.canglan.cyancodex.data.ApiClient
import com.canglan.cyancodex.data.MidiParser
import com.canglan.cyancodex.data.PendingMidi
import com.canglan.cyancodex.data.MusicPreview
import com.canglan.cyancodex.data.Session
import com.canglan.cyancodex.generators.Generators
import com.canglan.cyancodex.generators.Generators.INSTRUMENTS
import com.canglan.cyancodex.generators.Generators.MusicArgs
import com.canglan.cyancodex.generators.Generators.NoteEvent
import com.canglan.cyancodex.ui.components.AppTopBar
import com.canglan.cyancodex.ui.AppState
import com.canglan.cyancodex.ui.components.CommandOutputBox
import com.canglan.cyancodex.ui.components.GlassActionButton
import com.canglan.cyancodex.ui.components.GlassFilterChip
import com.canglan.cyancodex.ui.components.TopBarAction
import com.canglan.cyancodex.ui.components.TopBarTextAction
import com.canglan.cyancodex.ui.components.safeApi
import com.google.gson.Gson
import kotlinx.coroutines.launch

private val instNames = listOf(
    "竖琴", "底鼓", "军鼓", "踩镲", "贝斯", "长笛", "铃铛", "吉他",
    "风铃", "木琴", "铁琴", "牛铃", "迪吉里", "比特", "班卓", "拨弦",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MusicScreen(nav: NavHostController) {
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    // key = (tick, instrumentIndex) -> note
    var events by remember { mutableStateOf<Map<Pair<Int, Int>, Int>>(emptyMap()) }
    var pitch by remember { mutableStateOf(12) }
    var tempo by remember { mutableStateOf(10) }
    var loop by remember { mutableStateOf(true) }
    var showJson by remember { mutableStateOf(false) }
    var jsonText by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<List<String>?>(null) }
    var previewing by remember { mutableStateOf(false) }
    // 网格列数 (意见 #33: 16 列 -> 可调, 上限 256)
    var cols by remember { mutableStateOf(16) }

    fun buildEvents(): List<NoteEvent> =
        events.map { (k, note) -> NoteEvent(k.first, INSTRUMENTS[k.second], note) }.sortedBy { it.tick }

    fun eventsJson(): String {
        val evs = buildEvents().map { mapOf("t" to it.tick, "i" to it.instrument, "n" to it.note) }
        return Gson().toJson(mapOf("tempo" to tempo, "loop" to loop, "events" to evs))
    }

    fun generate() {
        val evs = buildEvents()
        val cmds = Generators.music(MusicArgs(tempo = tempo, loop = loop, events = evs))
        result = cmds
        Session.addLocalHistory("music", eventsJson(), cmds.joinToString("\n"))
        scope.launch {
            if (AppState.token != null) {
                val input = eventsJson()
                safeApi { ApiClient.get().saveHistory(mapOf(
                    "gen_type" to "music", "input_json" to input, "output_command" to cmds.joinToString("\n"))) }
            }
        }
    }

    /** GM 音色号 -> 音符盒乐器 */
    fun midiInstrument(program: Int, channel: Int, midiNote: Int): String {
        if (channel == 9) { // 打击乐通道
            return when (midiNote) {
                35, 36 -> "bd"                                          // 大鼓/底鼓
                38, 40, 48 -> "snare"                                   // 军鼓/边击/高音嗵
                42, 44, 46, 49, 57 -> "hat"                             // 踩镲/镲片
                else -> "snare"
            }
        }
        return when (program) {
            in 8..15 -> when (program) {                                // 键盘打击乐
                9 -> "bell"                                             // 钟琴
                11 -> "xylophone"                                       // 木琴
                12 -> "iron_xylophone"                                  // 管钟
                14 -> "cow_bell"                                        // 牛铃
                else -> "bell"
            }
            in 24..28 -> "guitar"                                       // 尼龙/钢弦吉他
            in 29..31 -> "banjo"                                        // 电吉他/失真/过载
            in 32..39 -> "bass"                                         // 贝斯
            in 56..63 -> "pling"                                        // 铜管
            in 64..79 -> "flute"                                        // 簧片/吹管
            in 80..87 -> "bit"                                          // 合成主音
            in 88..95 -> "chime"                                        // 合成垫
            in 104..111 -> "didgeridoo"                                 // 民族乐器
            in 112..119 -> "cow_bell"                                   // 打击乐器
            in 120..127 -> "bit"                                        // 音效
            else -> "harp"
        }
    }

    /** MIDI 音高 -> 音符盒 0..24 (F#3 = MIDI 54), 超范围按八度平移 */
    fun midiPitch(midiNote: Int): Int {
        var p = midiNote - 54
        while (p < 0) p += 12
        while (p > 24) p -= 12
        return p
    }

    // ============ MIDI 导入 (参考 HASOC 工具箱) ============
    // 抽成函数: 除了系统文件选择器, 「MIDI 工具」页(音频转 MIDI / 曲库下载)也会把字节直接送进来
    fun importMidiBytes(bytes: ByteArray) {
        if (bytes.isEmpty()) {
            android.widget.Toast.makeText(ctx, "无法读取文件", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        val song = MidiParser.parse(bytes)
        if (song == null) {
            android.widget.Toast.makeText(ctx, "无法解析 MIDI 文件", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        // 速度: 每四分音符微秒 -> 每拍游戏刻 (1 游戏刻 = 50ms)
        tempo = (Math.round(song.tempoUs / 50000.0).toInt()).coerceIn(2, 40)
        loop = true
        val ppq = song.ppq
        val m = mutableMapOf<Pair<Int, Int>, Int>()
        song.notes.forEach { n ->
            // 量化到最近的拍 -> 网格列 (前 cols 拍, 超长截断)
            val col = Math.round(n.tick.toDouble() / ppq).toInt()
            if (col !in 0 until cols) return@forEach
            val inst = midiInstrument(song.programs.getOrElse(n.channel) { -1 }, n.channel, n.note)
            val instIdx = INSTRUMENTS.indexOf(inst).takeIf { it >= 0 } ?: 0
            val key = Pair(col, instIdx)
            // 同列同乐器冲突时保留音高更高的
            val p = midiPitch(n.note)
            m[key] = maxOf(m[key] ?: -1, p)
        }
        events = m
        result = null
        val dropped = song.notes.count { Math.round(it.tick.toDouble() / ppq).toInt() !in 0 until cols }
        android.widget.Toast.makeText(
            ctx,
            if (dropped > 0) "MIDI 导入成功: ${song.notes.size} 音符, 超出 $cols 拍截断 $dropped 个" else "MIDI 导入成功: ${song.notes.size} 音符",
            android.widget.Toast.LENGTH_LONG).show()
    }

    val midiLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                val bytes = runCatching {
                    ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                }.getOrNull()
                if (bytes == null) {
                    android.widget.Toast.makeText(ctx, "无法读取文件", android.widget.Toast.LENGTH_SHORT).show()
                } else {
                    importMidiBytes(bytes)
                }
            }
        }
    }

    // 从「MIDI 工具」页带过来的内容: 取一次就导入
    LaunchedEffect(Unit) {
        PendingMidi.take()?.let { importMidiBytes(it) }
    }

    Scaffold(
        topBar = {
            AppTopBar(title = "音乐生成器", icon = Icons.Default.MusicNote, onBack = { nav.popBackStack() }, actions = {
                TopBarAction(onClick = { events = emptyMap(); result = null }) {
                    Icon(Icons.Default.DeleteSweep, "清空", Modifier.size(20.dp))
                }
                TopBarTextAction(onClick = { midiLauncher.launch(arrayOf("audio/midi", "audio/x-midi", "application/x-midi", "*/*")) }) {
                    Text("MIDI")
                }
                TopBarTextAction(onClick = {
                    showJson = !showJson
                    if (showJson) jsonText = eventsJson()
                }) { Text("JSON") }
            })
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
            Text("节奏: 每拍 $tempo 游戏刻", style = MaterialTheme.typography.labelMedium)
            Slider(value = tempo.toFloat(), onValueChange = { tempo = it.toInt() }, valueRange = 2f..40f)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("音高: ${pitch} (${Generators.pitchOf(pitch)})", style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
                Switch(checked = loop, onCheckedChange = { loop = it })
                Text("循环", style = MaterialTheme.typography.labelMedium)
            }
            Slider(value = pitch.toFloat(), onValueChange = { pitch = it.toInt() }, valueRange = 0f..24f)

            // 网格列数调节 (意见 #33: 16 列 -> 无限制, 上限 256)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("网格长度: $cols 拍", style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
                listOf(16, 32, 64, 128, 256).forEach { n ->
                    GlassFilterChip(
                        selected = cols == n,
                        onClick = {
                            // 缩短时截掉超出的音符
                            if (n < cols) events = events.filterKeys { (t, _) -> t < n }
                            cols = n
                        },
                        label = { Text("$n", style = MaterialTheme.typography.labelSmall) },
                        modifier = Modifier.padding(end = 4.dp),
                    )
                }
            }
            Spacer(Modifier.height(6.dp))

            // 音符网格
            Row(Modifier.horizontalScroll(rememberScrollState())) {
                Column {
                    // 表头
                    Row {
                        Spacer(Modifier.width(56.dp))
                        (0 until cols).forEach { t ->
                            Box(Modifier.size(22.dp), contentAlignment = Alignment.Center) {
                                Text("$t", fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    INSTRUMENTS.forEachIndexed { instIdx, inst ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(instNames[instIdx], style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(54.dp))
                            (0 until cols).forEach { tick ->
                                val has = events[Pair(tick, instIdx)]
                                val note = has ?: -1
                                Box(
                                    Modifier
                                        .padding(1.dp)
                                        .size(20.dp)
                                        .background(
                                            if (note >= 0) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.surfaceVariant,
                                            RoundedCornerShape(3.dp),
                                        )
                                        .border(
                                            1.dp,
                                            if (note >= 0) MaterialTheme.colorScheme.onSecondary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f),
                                            RoundedCornerShape(3.dp),
                                        )
                                        .clickable {
                                            val key = Pair(tick, instIdx)
                                            val placing = note < 0
                                            events = if (note >= 0) events - key else events + (key to pitch)
                                            // 放置音符即出声, 边点边听 (参考 HASOC)
                                            if (placing) MusicPreview.playNote(INSTRUMENTS[instIdx], pitch)
                                        },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    if (note >= 0) Text("$note", fontSize = 7.sp, color = MaterialTheme.colorScheme.onSecondary)
                                }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Text("点击格子放置音符(音高由上方滑杆决定), 再点取消。网格长度可调(16-256 拍)。", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)

            if (showJson) {
                OutlinedTextField(value = jsonText, onValueChange = { jsonText = it },
                    label = { Text("高级: 音符 JSON (t=tick, i=乐器, n=音高)") },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp),
                    textStyle = androidx.compose.ui.text.TextStyle(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, fontSize = 11.sp),
                )
                TextButton(onClick = {
                    // 从 JSON 恢复
                    runCatching {
                        val j = com.google.gson.JsonParser().parse(jsonText).asJsonObject
                        val evs = j.getAsJsonArray("events")
                        val m = mutableMapOf<Pair<Int, Int>, Int>()
                        evs.forEach { e ->
                            val o = e.asJsonObject
                            val i = INSTRUMENTS.indexOf(o.get("i").asString).takeIf { it >= 0 } ?: 0
                            m[Pair(o.get("t").asInt, i)] = o.get("n").asInt
                        }
                        events = m
                        tempo = j.get("tempo")?.asInt ?: tempo
                        loop = j.get("loop")?.asBoolean ?: loop
                    }
                }) { Text("从 JSON 恢复网格") }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 4.dp)) {
                GlassActionButton(filled = true, onClick = {
                    val evs = buildEvents()
                    if (evs.isEmpty()) {
                        android.widget.Toast.makeText(ctx, "请先在网格中放置音符", android.widget.Toast.LENGTH_SHORT).show()
                        return@GlassActionButton
                    }
                    if (previewing) { MusicPreview.stop(); previewing = false; return@GlassActionButton }
                    previewing = true
                    MusicPreview.play(evs, tempo * 50) {
                        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch { previewing = false }
                    }
                }, modifier = Modifier.weight(1f)) {
                    Icon(if (previewing) Icons.Default.Stop else Icons.Default.PlayArrow, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(if (previewing) "停止试听" else "试听")
                }
                GlassActionButton(filled = true, onClick = { generate() }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.PlayArrow, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("生成音乐指令")
                }
            }
            result?.let { cmds ->
                Spacer(Modifier.height(10.dp))
                CommandOutputBox(cmds)
                Text("使用方法: 按顺序放入命令方块 — 第1条[循环/始终开启], 其余[链式/条件开启]。",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
