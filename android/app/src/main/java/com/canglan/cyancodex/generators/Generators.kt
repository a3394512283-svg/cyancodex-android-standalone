package com.canglan.cyancodex.generators

import java.util.Locale

/**
 * 生成器合集 (基岩版 Bedrock Edition): T显/计分板、穷举、滚动字幕、音乐 —— 全部本地算法, 离线可用
 * 基岩版要点: 计分项只有 dummy; 使用 /titleraw + rawtext; 音效 note.*; 无 minecraft: 前缀
 */
object Generators {

    // ==================== T显 / 计分板 (基岩版) ====================
    data class ScoreboardEntry(val player: String, val value: Int)

    data class TDisplayLine(val prefix: String, val objective: String, val suffix: String)

    data class ScoreboardArgs(
        val objective: String = "sb",
        val displayName: String = "分数",
        val slot: String = "sidebar",     // sidebar / belowname / list
        val mode: String = "sidebar",     // sidebar=侧边栏计分板 / tdisplay=T显字幕
        val tpos: String = "title",       // title / subtitle / actionbar
        val entries: List<ScoreboardEntry> = emptyList(),
        val tlines: List<TDisplayLine> = emptyList(),
    )

    fun scoreboard(args: ScoreboardArgs): List<String> {
        val out = mutableListOf<String>()
        val obj = sanitizeName(args.objective.ifBlank { "sb" })
        val display = escapeQuote(args.displayName.ifBlank { obj })
        // 基岩版只有 dummy 计分项
        out += "/scoreboard objectives add $obj dummy \"$display\""

        if (args.mode == "tdisplay") {
            // T显字幕: 用 /titleraw 在屏幕显示分数
            out += "/scoreboard objectives setdisplay sidebar $obj"
            args.tlines.filter { it.objective.isNotBlank() }.forEach { l ->
                val parts = mutableListOf<String>()
                if (l.prefix.isNotBlank()) parts += """{"text":"${escapeJson(l.prefix)}"}"""
                parts += """{"score":{"name":"@s","objective":"${sanitizeName(l.objective)}"}}"""
                if (l.suffix.isNotBlank()) parts += """{"text":"${escapeJson(l.suffix)}"}"""
                out += "/titleraw @a ${args.tpos} {\"rawtext\":[${parts.joinToString(",")}]}"
            }
        } else {
            if (args.slot.isNotBlank()) {
                out += "/scoreboard objectives setdisplay ${args.slot} $obj"
            }
            args.entries.filter { it.player.isNotBlank() }.forEach { e ->
                out += "/scoreboard players set ${e.player.trim()} $obj ${e.value}"
            }
        }
        return out
    }

    // ==================== 穷举 ====================
    data class EnumerateArgs(
        val template: String = "",
        val variables: Map<String, List<String>> = emptyMap(),
        val maxCount: Int = 100,
    )

    /** 笛卡尔积展开模板中的 {变量} */
    fun enumerate(args: EnumerateArgs): List<String> {
        if (args.template.isBlank() || args.variables.isEmpty()) return emptyList()
        var combos: List<Map<String, String>> = listOf(emptyMap())
        args.variables.forEach { (name, values) ->
            if (values.isNotEmpty()) {
                combos = combos.flatMap { base -> values.map { v -> base + (name to v) } }
            }
        }
        return combos.mapNotNull { vars ->
            var s = args.template
            vars.forEach { (k, v) -> s = s.replace("{$k}", v) }
            s.takeIf { it.isNotBlank() }
        }.take(if (args.maxCount in 1..5000) args.maxCount else 100)
    }

    // ==================== 滚动字幕 (基岩版) ====================
    val MC_COLORS = mapOf(
        "白色" to "white", "黄色" to "yellow", "金色" to "gold",
        "红色" to "red", "绿色" to "green", "深绿" to "dark_green",
        "蓝色" to "blue", "深蓝" to "dark_blue", "天蓝" to "aqua",
        "紫色" to "light_purple", "深紫" to "dark_purple",
        "灰色" to "gray", "深灰" to "dark_gray", "黑色" to "black",
    )

    data class ScrollerArgs(
        val text: String = "",
        val color: String = "gold",
        val mode: String = "roll",   // roll=轮播公告 / banner=静态横幅
        val intervalTicks: Int = 20,
        val x: Int = 0, val y: Int = 100, val z: Int = 0,
        val gap: Int = 2,
    )

    /** 生成基岩版滚动/轮播/横幅指令 */
    fun scroller(args: ScrollerArgs): List<String> {
        val color = args.color.ifBlank { "gold" }
        val text = args.text.ifBlank { "CyanCodex" }
        if (args.mode == "banner") {
            // 逐字横幅: 基岩版 summon 带名字 (无 NBT), 每个字一个盔甲架
            val out = mutableListOf<String>()
            out += "# 逐字横幅 (基岩版, 盔甲架带名字, 会显示本体):"
            text.forEachIndexed { i, ch ->
                if (ch == ' ') return@forEachIndexed
                val x = args.x + i * args.gap
                out += "/summon armor_stand $x ${args.y} ${args.z} \"${ch}\""
            }
            out += "# 可选: 隐藏盔甲架本体: /effect @e[type=armor_stand] invisibility 999999 1 true"
            return out
        }
        // 轮播模式: 按空格分词, 逐行 titleraw 显示 (基岩版时钟)
        val lines = text.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        if (lines.isEmpty()) return emptyList()
        val out = mutableListOf<String>()
        val obj = "cyx_scroll"
        val max = lines.size + 1
        out += "/scoreboard objectives add $obj dummy \"滚动字幕\""
        out += "/scoreboard players set @a $obj 0"
        out += "# 以下命令按顺序放入命令方块:"
        out += "# [循环/保持开启] 节拍器:"
        out += "/execute as @a run scoreboard players add @s $obj 1"
        lines.forEachIndexed { i, line ->
            val json = """{"rawtext":[{"text":"${escapeJson(line)}","color":"$color"}]}"""
            out += "# [链式/条件开启] 第${i + 1}行:"
            out += "/execute as @a if score @s $obj matches ${i + 1} run titleraw @a title $json"
        }
        out += "# [链式/条件开启] 循环复位:"
        out += "/execute as @a if score @s $obj matches $max run scoreboard players set @s $obj 0"
        return out
    }

    // ==================== 音乐生成器 (基岩版) ====================
    // 基岩版 note_block 音效名 (与 Java 不同)
    val INSTRUMENTS = listOf(
        "harp", "bd", "snare", "hat", "bass", "flute", "bell",
        "guitar", "chime", "xylophone", "iron_xylophone", "cow_bell",
        "didgeridoo", "bit", "banjo", "pling",
    )

    data class NoteEvent(val tick: Int, val instrument: String, val note: Int) // note 0..24

    data class MusicArgs(
        val tempo: Int = 10,             // 每拍间隔(游戏刻)
        val loop: Boolean = true,
        val events: List<NoteEvent> = emptyList(),
    )

    fun music(args: MusicArgs): List<String> {
        if (args.events.isEmpty()) return listOf("# 请先在网格中放置音符")
        val obj = "cyx_music"
        val maxTick = (args.events.maxOfOrNull { it.tick } ?: 0) + 1
        val out = mutableListOf<String>()
        out += "/scoreboard objectives add $obj dummy \"音乐\""
        out += "/scoreboard players set @a $obj 0"
        out += "# [循环/始终开启] 节拍器:"
        out += "/execute as @a run scoreboard players add @s $obj 1"
        val grouped = args.events.groupBy { it.tick }
        grouped.keys.sorted().forEach { tick ->
            out += "# [链式/条件开启] tick $tick:"
            grouped[tick]!!.forEach { ev ->
                val inst = if (INSTRUMENTS.contains(ev.instrument)) ev.instrument else "harp"
                val pitch = pitchOf(ev.note)
                out += "/execute as @a if score @s $obj matches $tick run playsound note.$inst @s ~ ~ ~ 1 $pitch"
            }
        }
        if (args.loop) {
            out += "# [链式/条件开启] 循环复位:"
            out += "/execute as @a if score @s $obj matches $maxTick run scoreboard players set @s $obj 0"
        }
        return out
    }

    fun pitchOf(note: Int): String {
        val p = 0.5 * Math.pow(2.0, note.coerceIn(0, 24) / 12.0)
        return String.format(Locale.US, "%.3f", p)
    }

    // ==================== 工具 ====================
    fun sanitizeName(s: String): String =
        s.replace(Regex("[^a-zA-Z0-9_.:-]"), "_").take(16)

    fun escapeJson(s: String): String =
        s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")

    private fun escapeQuote(s: String): String = s.replace("\"", "\\\"")
}
