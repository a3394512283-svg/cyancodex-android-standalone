package com.canglan.cyancodex.ai

/**
 * 网易版《我的世界》指令语法知识库 (等价于内置的 wiki 自查工具):
 * AI 每次输出指令前, 从当前问题里提取提到的指令, 把对应语法注入提示词,
 * 并要求先对照自查再输出 —— 降低语法错误。
 *
 * 网易版说明: 基于基岩版内核(版本滞后于国际版), 命令方块需开启"作弊";
 * 支持 execute(新旧两种写法)/scoreboard/tellraw/titleraw(较新版本)/summon/
 * setblock/fill/clone/testfor 系列/replaceitem 等; 没有 Java 版的 /data、/execute store 等。
 */
object NetEaseWiki {

    /** 指令 -> 网易版语法要点 (与问题中提到的指令做匹配) */
    val SYNTAX: Map<String, String> = linkedMapOf(
        "execute" to "execute 两种写法:\n  新版: /execute as <目标> at @s run <指令> (需较新网易版)\n  旧版: /execute <目标> ~ ~ ~ <指令>\n  条件: /execute as @a if score @s <计分项> matches <数值> run <指令>",
        "scoreboard" to "计分板:\n  /scoreboard objectives add <名称> dummy <显示名> (网易版只有 dummy 计分项)\n  /scoreboard objectives setdisplay sidebar <名称>\n  /scoreboard players set @a <名称> <数值>\n  /scoreboard players add @a <名称> <数值>",
        "titleraw" to "T显示(较新网易版): /titleraw @a title {\"rawtext\":[{\"text\":\"文字\",\"color\":\"gold\"},{\"score\":{\"name\":\"@s\",\"objective\":\"计分项\"}}]}",
        "title" to "T显示: /title <目标> title <文字> (老版本用 /title, 不支持 rawtext JSON 时用多条 title/subtitle)",
        "tellraw" to "聊天栏 JSON 消息: /tellraw @a {\"rawtext\":[{\"text\":\"文字\",\"color\":\"red\"}]}",
        "give" to "给予物品(不带 minecraft: 前缀): /give @s diamond 1 (或 /give @s diamond_sword 1)",
        "tp" to "传送: /tp @s 100 64 100 或 /tp @p @s (传送目标到执行者)",
        "summon" to "生成实体: /summon zombie ~ ~ ~ 或 /summon armor_stand ~ ~ ~ {标记}",
        "setblock" to "放置方块: /setblock ~ ~1 ~ stone (网易版可用方块名, 数据值用数字如 stone 1)",
        "fill" to "填充: /fill x1 y1 z1 x2 y2 z2 stone 0 replace (或 destroy/keep/outline/hollow)",
        "clone" to "复制: /clone x1 y1 z1 x2 y2 z2 目标x 目标y 目标z",
        "testfor" to "探测(条件方块): /testfor @a[r=5] (半径5内有无玩家)",
        "testforblock" to "探测方块: /testforblock x y z stone",
        "testforblocks" to "区域对比: /testforblocks x1 y1 z1 x2 y2 z2 x y z",
        "replaceitem" to "替换物品: /replaceitem entity @s slot.inventory 0 diamond 1",
        "effect" to "效果: /effect @a speed 30 1 (时长30秒 等级1); /effect @a speed 0 移除",
        "gamemode" to "游戏模式: /gamemode creative @s (生存 survival / 创造 creative / 冒险 adventure)",
        "gamerule" to "规则: /gamerule keepInventory true / doDaylightCycle false / mobGriefing false",
        "playsound" to "音效: /playsound note.harp @a ~ ~ ~ 1 1 (音色 note.harp/bd/snare/hat/bass/flute/bell/guitar/chime/xylophone/iron_xylophone/cow_bell/didgeridoo/bit/banjo/pling; 音量 1, 音高 0.5~2)",
        "particle" to "粒子: /particle minecraft:heart_particle ~ ~1 ~ 0 0 0 0 5 (网易版粒子名需 minecraft: 前缀)",
        "spreadplayers" to "分散: /spreadplayers x z 0 10 @a (以 x,z 为中心半径10内随机分散)",
        "say" to "公告: /say 文字 (聊天栏黄色显示)",
        "time" to "时间: /time set day / night / 1000; /time add 1000",
        "weather" to "天气: /weather clear / rain / thunder",
        "clear" to "清空物品: /clear @s (或 /clear @s diamond 1 指定物品)",
        "kill" to "击杀: /kill @e[type=item] (清理掉落物; 需谨慎用 @e 全清)",
        "locate" to "定位结构: /locate village / stronghold / temple (网易版结构名有限)",
        "difficulty" to "难度: /difficulty peaceful / easy / normal / hard",
        "enchant" to "附魔: /enchant @s sharpness 5 (需先有物品)",
        "stopsound" to "停止音效: /stopsound @a * (全部)",
        "xp" to "经验: /xp 100 @s (或 /xp -10L @s 减等级)",
        "toggledownfall" to "切换天气: /toggledownfall",
        "op" to "管理员: /op 玩家名 (仅管理员/主机可用)",
        "ban" to "封禁: /ban 玩家名 (仅管理员可用)",
        "kick" to "踢出: /kick 玩家名 (仅管理员可用)",
    )

    /** 从问题文本提取提到的指令, 返回匹配到的语法条目文本 */
    fun lookup(text: String): String {
        val low = text.lowercase()
        val hits = SYNTAX.keys.filter { k -> k in low || "/$k " in low }.take(6)
        return if (hits.isEmpty()) "" else hits.joinToString("\n") { "- $it: ${SYNTAX[it]}" }
    }

    /** 完整的网易版要点(注入系统提示用) */
    val FULL_NOTES: String = """【网易版《我的世界》说明】
- 这是网易版(手机基岩版内核, 版本滞后国际版): 没有 /data、/execute store 等 Java 指令;
- 命令方块需要先开启"作弊"; 指令里的物品/实体/效果一般不带 minecraft: 前缀(粒子除外);
- execute 同时支持新版(execute as @a at @s run ...)与旧版(execute @a ~ ~ ~ ...)写法;
- 计分板只有 dummy 计分项; T显示用 /titleraw(较新版本) 或 /title;
- 输出指令前必须先对照【网易版语法自查】逐条核对, 语法不对就改正后再输出。"""
}
