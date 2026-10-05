package com.canglan.cyancodex.data

/**
 * 基岩版指令补全引擎 (参考 CHelper Auto Suggestion, 轻量版)
 * - 根据当前输入的命令 + 光标位置, 推断正在输入第几个参数
 * - 返回该参数的可选值 (目标选择器/物品/实体/坐标提示等)
 */
object CommandAssist {

    // 参数类型 -> 建议值
    private val TARGETS = listOf("@p", "@a", "@e", "@s", "@r", "@a[type=player]", "@e[type=armor_stand]")
    private val COORDS = listOf("~ ~ ~", "^ ^ ^", "~ ~1 ~", "~ ~-1 ~")
    private val BOOLEAN = listOf("true", "false")
    private val NUMBER = listOf("0", "1", "2", "3", "5", "10", "100")
    private val ITEMS = listOf(
        "diamond", "iron_ingot", "gold_ingot", "stone", "dirt", "grass_block", "oak_planks",
        "stick", "arrow", "bow", "diamond_sword", "diamond_pickaxe", "tnt", "redstone",
        "command_block", "chain_command_block", "repeating_command_block", "armor_stand",
    )
    private val ENTITIES = listOf(
        "player", "armor_stand", "creeper", "zombie", "skeleton", "enderman", "sheep", "pig",
        "cow", "chicken", "villager", "item", "arrow", "xp_orb", "tnt", "minecart",
    )
    private val BLOCKS = listOf(
        "stone", "dirt", "grass_block", "sand", "oak_log", "oak_planks", "glass", "tnt",
        "redstone_block", "command_block", "bedrock", "water", "lava", "air",
    )
    private val SOUNDS = listOf("note.harp", "note.bass", "note.hat", "note.snare", "explode", "levelup", "totem.use")
    private val GAMERULES = listOf(
        "commandBlocksEnabled", "commandBlockOutput", "doDaylightCycle", "doFireTick", "doMobLoot",
        "doMobSpawning", "doTileDrops", "keepInventory", "mobGriefing", "naturalRegeneration", "showCoordinates",
    )
    private val EFFECTS = listOf(
        "speed", "slowness", "haste", "strength", "jump_boost", "regeneration", "resistance",
        "fire_resistance", "water_breathing", "invisibility", "night_vision", "poison", "weakness",
    )
    private val MODES = listOf("0", "1", "2", "3", "survival", "creative", "adventure", "spectator")
    private val GAMETYPES = listOf("survival", "creative", "adventure", "spectator", "default")
    private val DIFFICULTY = listOf("peaceful", "easy", "normal", "hard")
    private val DIRS = listOf("~", "~1", "~-1", "^", "^1", "^ ^ ^")

    // 命令定义: 命令名 -> 参数序列 (每个参数: 标签 + 类型)
    // 类型: target / coords / item / entity / block / num / str / bool / sound / gamerule / effect / mode / gametype / difficulty / pos / rot / value
    private data class Param(val label: String, val type: String)

    private val COMMANDS: Map<String, List<Param>> = mapOf(
        "/execute" to listOf(
            Param("目标(可多次 as/at)", "execute"),
        ),
        "/give" to listOf(
            Param("目标", "target"), Param("物品", "item"), Param("数量", "num"), Param("数据", "num"),
        ),
        "/tp" to listOf(
            Param("目标", "target"), Param("坐标 x y z", "coords"), Param("朝向 y x", "rot"),
        ),
        "/teleport" to listOf(
            Param("目标", "target"), Param("坐标 x y z", "coords"), Param("朝向 y x", "rot"),
        ),
        "/summon" to listOf(
            Param("实体", "entity"), Param("坐标 x y z", "coords"), Param("生成事件/数据", "str"),
        ),
        "/setblock" to listOf(
            Param("坐标 x y z", "coords"), Param("方块", "block"), Param("方块状态", "str"), Param("模式", "mode"),
        ),
        "/fill" to listOf(
            Param("起点 x y z", "coords"), Param("终点 x y z", "coords"), Param("方块", "block"),
            Param("方块状态", "str"), Param("模式", "mode"),
        ),
        "/clone" to listOf(
            Param("起点 x y z", "coords"), Param("终点 x y z", "coords"), Param("目标 x y z", "coords"),
        ),
        "/effect" to listOf(
            Param("目标", "target"), Param("效果", "effect"), Param("时长(秒)", "num"), Param("等级", "num"), Param("隐藏粒子", "bool"),
        ),
        "/scoreboard" to listOf(
            Param("子命令 objectives/players", "scoreboard"),
        ),
        "/gamemode" to listOf(
            Param("模式", "gametype"), Param("目标", "target"),
        ),
        "/difficulty" to listOf(
            Param("难度", "difficulty"),
        ),
        "/gamerule" to listOf(
            Param("规则名", "gamerule"), Param("值", "value"),
        ),
        "/playsound" to listOf(
            Param("声音", "sound"), Param("目标", "target"), Param("坐标 x y z", "coords"),
            Param("音量", "num"), Param("音调", "num"), Param("最小音量", "num"),
        ),
        "/stopsound" to listOf(
            Param("目标", "target"), Param("声音", "sound"),
        ),
        "/particle" to listOf(
            Param("粒子", "particle"), Param("坐标 x y z", "coords"),
        ),
        "/say" to listOf(Param("消息", "str")),
        "/title" to listOf(Param("目标", "target"), Param("模式", "title")),
        "/titleraw" to listOf(Param("目标", "target"), Param("模式", "title"), Param("JSON文本", "str")),
        "/tellraw" to listOf(Param("目标", "target"), Param("JSON文本", "str")),
        "/clear" to listOf(Param("目标", "target"), Param("物品", "item"), Param("数量", "num")),
        "/kill" to listOf(Param("目标", "target")),
        "/time" to listOf(Param("子命令 set/add/query", "time")),
        "/weather" to listOf(Param("天气", "weather")),
        "/enchant" to listOf(Param("目标", "target"), Param("附魔", "enchant"), Param("等级", "num")),
        "/xp" to listOf(Param("数量", "num"), Param("目标", "target")),
        "/tag" to listOf(Param("目标", "target"), Param("子命令 add/remove/list", "tag")),
        "/bossbar" to listOf(Param("子命令", "bossbar")),
        "/team" to listOf(Param("子命令", "team")),
        "/testfor" to listOf(Param("目标", "target")),
        "/spreadplayers" to listOf(
            Param("中心 x z", "coords"), Param("范围", "num"), Param("最大范围", "num"), Param("目标", "target"),
        ),
        "/locate" to listOf(Param("结构", "structure")),
        "/recipe" to listOf(Param("子命令 give/take", "recipe")),
        "/camerashake" to listOf(Param("目标", "target"), Param("模式", "str"), Param("强度", "num"), Param("时长", "num")),
        "/inputpermission" to listOf(Param("目标", "target"), Param("权限", "str"), Param("值", "str")),
    )

    private val SCOREBOARD_SUB = listOf("objectives add", "objectives remove", "objectives setdisplay", "players add", "players remove", "players set", "players reset", "players list", "players test")
    private val TITLE_MODES = listOf("title", "subtitle", "actionbar", "times", "clear", "reset")
    private val TIME_SUB = listOf("set day", "set noon", "set sunset", "set night", "set midnight", "add 100", "query daytime")
    private val WEATHER = listOf("clear", "rain", "thunder")
    private val TAG_SUB = listOf("add", "remove", "list")
    private val BOSSBAR_SUB = listOf("add", "remove", "set", "get", "players")
    private val TEAM_SUB = listOf("add", "remove", "join", "leave", "empty", "list", "modify")
    private val STRUCTURES = listOf("stronghold", "village", "mineshaft", "monument", "temple", "ruined_portal", "shipwreck", "buried_treasure")
    private val RECIPE_SUB = listOf("give", "take")
    private val PARTICLES = listOf("minecraft:basic_flame_particle", "minecraft:critical_hit_emitter", "minecraft:heart_particle", "minecraft:villager_happy", "minecraft:note_particle", "minecraft:water_splash_particle", "minecraft:lava_particle", "minecraft:explosion_particle")
    private val ENCHANTS = listOf("sharpness", "protection", "fire_aspect", "power", "punch", "infinity", "mending", "efficiency", "fortune", "unbreaking")

    /** 根据命令 + 当前输入片段, 返回建议列表 */
    fun suggest(line: String): List<String> {
        val t = line.trimStart()
        if (!t.startsWith("/")) return emptyList()
        // 提取命令名
        val cmdMatch = Regex("^/([a-zA-Z_]+)").find(t)
        if (cmdMatch == null) {
            // 输入 /xx 前缀时补全命令名
            val prefix = t.substring(1)
            if (prefix.isBlank()) return COMMANDS.keys.sorted()
            return COMMANDS.keys.filter { it.startsWith("/" + prefix) }.sorted().take(12)
        }
        val cmd = "/" + cmdMatch.groupValues[1]
        val params = COMMANDS[cmd] ?: return emptyList()
        // 当前光标前的参数索引 (空格分隔, 去掉命令名)
        val afterCmd = t.substring(cmdMatch.range.last + 1).trim()
        if (afterCmd.isBlank()) {
            // 刚输完命令名, 提示第一个参数
            return suggestFor(params[0].type, "")
        }
        // 当前正在输入的片段 = 最后一个空格后的内容; 参数序号 = 非空片段数
        val segments = afterCmd.split(" ").filter { it.isNotBlank() }
        val current = segments.lastOrNull() ?: ""
        val argIndex = segments.size - 1
        if (argIndex >= params.size) return emptyList()
        // 特殊子命令处理
        val special = specialSuggest(cmd, segments)
        if (special != null) return special
        return suggestFor(params[argIndex].type, current)
    }

    private fun specialSuggest(cmd: String, segments: List<String>): List<String>? {
        val first = segments.getOrNull(0) ?: return null
        when (cmd) {
            "/scoreboard" -> {
                val sub = segments.take(2).joinToString(" ")
                return SCOREBOARD_SUB.filter { it.startsWith(sub) }
            }
            "/title", "/titleraw" -> {
                if (segments.size == 2) return TITLE_MODES.filter { it.startsWith(segments[1]) }
            }
            "/time" -> {
                val sub = segments.take(2).joinToString(" ")
                return TIME_SUB.filter { it.startsWith(sub) }
            }
            "/weather" -> {
                if (segments.size == 1) return WEATHER.filter { it.startsWith(first) }
            }
            "/tag" -> {
                if (segments.size == 2) return TAG_SUB.filter { it.startsWith(segments[1]) }
            }
            "/bossbar" -> {
                if (segments.size == 1) return BOSSBAR_SUB.filter { it.startsWith(first) }
            }
            "/team" -> {
                if (segments.size == 1) return TEAM_SUB.filter { it.startsWith(first) }
            }
            "/locate" -> {
                if (segments.size == 1) return STRUCTURES.filter { it.startsWith(first) }
            }
            "/recipe" -> {
                if (segments.size == 1) return RECIPE_SUB.filter { it.startsWith(first) }
            }
            "/execute" -> {
                val subs = listOf("as @p", "as @a", "as @e", "as @s", "at @p", "at @s", "positioned ~ ~ ~", "rotated ~ ~", "if block ~ ~ ~", "unless block ~ ~ ~", "if entity @e", "run")
                return subs.filter { it.startsWith(segments.joinToString(" ")) }
            }
        }
        return null
    }

    private fun suggestFor(type: String, prefix: String): List<String> {
        val pool = when (type) {
            "target" -> TARGETS
            "coords", "pos", "rot" -> COORDS
            "item" -> ITEMS
            "entity" -> ENTITIES
            "block" -> BLOCKS
            "num" -> NUMBER
            "bool" -> BOOLEAN
            "sound" -> SOUNDS
            "gamerule" -> GAMERULES
            "effect" -> EFFECTS
            "mode" -> MODES
            "gametype" -> GAMETYPES
            "difficulty" -> DIFFICULTY
            "particle" -> PARTICLES
            "enchant" -> ENCHANTS
            "weather" -> WEATHER
            else -> emptyList()
        }
        if (prefix.isBlank()) return pool.take(10)
        return pool.filter { it.startsWith(prefix, ignoreCase = true) }.take(10)
    }
}
