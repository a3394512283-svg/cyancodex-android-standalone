package com.canglan.cyancodex.ai

/**
 * **「核对指令语法」工具**的实现 (AI 的 `check_command` 工具)。
 *
 * ## 它解决什么
 *
 * 模型写 MC 指令时最容易犯的错不是"不知道有这条指令", 而是:
 *  - 把 **Java 版专属**写法搬到网易版(基岩版内核) —— `/data`、`/execute store`、`nbt=` 选择器…
 *    用户拿去游戏里得到的是一条红字, 而且**不知道错在哪**;
 *  - 漏掉必填的目标选择器 —— `/give diamond 64`(应为 `/give @s diamond 64`);
 *  - JSON 少一层 —— `/tellraw @a {"text":"hi"}`(应为 `{"rawtext":[{"text":"hi"}]}`);
 *  - 引号/花括号不配对。
 *
 * 这些规则**只靠提示词是拦不住的**: 提示词说了它也可能忘。给它一个能**真的跑一遍**的工具,
 * 它就会在输出前自查 —— 和人类写代码前跑 linter 是一个道理。
 *
 * ## 与 [NetEaseWiki] 的分工
 *
 * [NetEaseWiki] 是**知识**(35 条指令的语法要点), 在组装 system prompt 时按关键词注入;
 * 这里是**检查器**, 逐行对着那批语法 + 一组硬规则跑一遍, 把发现的问题直接回给模型。
 * 两者共用同一份 [NetEaseWiki.SYNTAX], 不会出现"提示词说 A、检查器说 B"。
 *
 * ⚠️ 这是**静态检查**, 不连游戏、不跑命令。它只做"能不能解析"级别的判断,
 *    不保证逻辑正确(比如坐标算错了它看不出来)。返回值里必须把这一点说清楚,
 *    否则模型会以为"检查通过 = 一定没问题"。
 *
 * ## 两个入口, 别混 (2026-10-02)
 *
 * | 入口 | 谁调 | 干什么 |
 * |---|---|---|
 * | [check] | **模型**（`check_command` 工具） | 输出**之前**自查, 返回给模型看的报告 |
 * | [audit] | **客户端**（`AiClient.chat` / `AiProxyAgent.run` 收尾时自动跑） | 输出**之后**复验, 返回问题清单 |
 *
 * 加 [audit] 是因为"模型自己检查过"这件事**没法保证** —— 它可以不调, 也可以调完再改。
 * 客户端在拿到最终正文后**自己再验一遍**, 发现问题就用 [fixPrompt] 喂回模型重写。
 * 两个入口共用 [checkOne], 所以**模型看到的结论和客户端的结论永远一致** ——
 * 不会出现"检查器说通过、客户端说不通过"这种自相矛盾。
 */
object CommandCheck {

    /**
     * 网易版(基岩版内核)**没有**的 Java 专属指令。
     *
     * 保守起见只列**确定不存在**的: 基岩版后来补上的(如 `/damage`、`/ride`、`/gametest`)
     * 一律不列 —— 误报比漏报更伤, 模型会为了"修"一个不存在的问题把对的改成错的。
     */
    private val JAVA_ONLY_CMDS = mapOf(
        "data" to "方块/实体的 NBT 读写是 Java 专属",
        "attribute" to "属性修饰符是 Java 专属",
        "bossbar" to "Boss 栏是 Java 专属",
        "advancement" to "进度系统是 Java 专属",
        "recipe" to "配方解锁是 Java 专属",
        "loot" to "战利品表是 Java 专属",
        "schedule" to "计划任务是 Java 专属",
        "function" to "函数文件(.mcfunction)是 Java 专属",
        "forceload" to "强制加载区块是 Java 专属",
        "tag" to "实体标签是 Java 专属",
        "team" to "队伍是 Java 专属",
        "spectate" to "旁观是 Java 专属",
        "jfr" to "JFR 分析是 Java 专属",
    )

    /** 必须带目标选择器的指令 —— 少写 `@s`/`@a` 是最常见的一类语法错 */
    private val NEEDS_TARGET = setOf(
        "give", "clear", "effect", "enchant", "gamemode", "tp", "teleport",
        "kill", "xp", "title", "titleraw", "tellraw", "experience",
    )

    /** 行首的序号/列表符号: 模型有时会把「1. /give …」整行递过来, 得先剥掉 */
    private val LEADING_JUNK = Regex("""^\s*(?:[-*•>]|\d+[.、)）])\s*""")

    private val CMD_NAME = Regex("""^/([a-zA-Z_]+)""")

    /**
     * 核对一段指令。
     *
     * @param raw 模型递进来的原文 —— 约定是**一行一条**, 但这里对"带序号""带围栏"都做了清洗,
     *            因为模型不听话是常态, 直接报"第 1 行不是指令"没有任何帮助。
     * @return 给模型看的核对报告(纯文本)。**永远返回非空文本** —— 空 content 的 tool 消息有些网关会拒。
     */
    fun check(raw: String): String {
        val lines = raw.split('\n')
            .map { it.trim().removePrefix("```").let { s -> LEADING_JUNK.replace(s, "") }.trim() }
            .filter { it.isNotBlank() && !it.startsWith("```") }

        if (lines.isEmpty()) return "没有收到任何指令。请把要核对的指令逐行传进来，一行一条，例如：/give @s diamond 64"

        val sb = StringBuilder()
        sb.append("共核对 ").append(lines.size).append(" 条指令：\n")
        var problems = 0

        lines.forEachIndexed { i, line ->
            sb.append('\n').append("第 ").append(i + 1).append(" 行：").append(line).append('\n')
            val issues = checkOne(line)
            if (issues.isEmpty()) {
                sb.append("  ✅ 语法检查通过\n")
            } else {
                problems += issues.size
                issues.forEach { sb.append("  ⚠️ ").append(it).append('\n') }
            }
            // 顺手把内置语法要点附上 —— 模型常常"知道自己写错了但不知道对的怎么写",
            // 只报错不给出正解, 它下一轮会换个姿势再错一遍。
            val name = CMD_NAME.find(line)?.groupValues?.get(1)?.lowercase()
            val ref = name?.let { NetEaseWiki.SYNTAX[it] }
            if (ref != null) sb.append("  📖 内置语法参考：").append(ref).append('\n')
        }

        sb.append('\n')
        if (problems == 0) {
            sb.append("结论：未发现语法问题。⚠️ 这是**静态检查**，只覆盖语法、网易版兼容性，")
            sb.append("以及方块/物品 ID 里**明显的**错（非法字符、基岩版没有的方块、多余的 _block 后缀）；")
            sb.append("不校验坐标算得对不对、计分项名有没有拼错、ID 到底存不存在 —— 那些要靠你自己再核一遍。")
        } else {
            sb.append("结论：发现 ").append(problems).append(" 处问题，请修正后重新输出指令。")
        }
        return sb.toString()
    }

    /** 单行检查。返回空列表 = 通过 */
    private fun checkOne(line: String): List<String> {
        val out = ArrayList<String>(3)

        if (line.startsWith("//")) {
            out.add("行首多了一个斜杠（写成了 //xxx）。指令只需要一个 /")
            return out
        }
        if (!line.startsWith("/")) {
            out.add("没有以 / 开头。每条指令都必须以 / 起头，说明文字请放在代码块外面")
            return out
        }

        val m = CMD_NAME.find(line)
        if (m == null) {
            out.add("认不出指令名。斜杠后面要紧跟英文指令名（如 give / execute / setblock）")
            return out
        }
        val name = m.groupValues[1].lowercase()

        JAVA_ONLY_CMDS[name]?.let { out.add("/$name 是 Java 版专属（$it），网易版没有这条指令，请改用基岩版方案") }

        // ---- execute 的 Java 专属子命令 ----
        if (name == "execute") {
            if (Regex("""\bexecute\b[\s\S]*\bstore\b""", RegexOption.IGNORE_CASE).containsMatchIn(line)) {
                out.add("execute store 是 Java 专属；网易版拿不到命令返回值，请改用记分板(scoreboard)间接实现")
            }
            if (Regex("""\b(if|unless)\s+data\b""", RegexOption.IGNORE_CASE).containsMatchIn(line)) {
                out.add("execute if/unless data 依赖 NBT，是 Java 专属；网易版改用 execute if block / if score")
            }
            if (Regex("""\bexecute\s+.*\brun\s+.*\brun\b""", RegexOption.IGNORE_CASE).containsMatchIn(line)) {
                out.add("一条 execute 里出现了两次 run。run 只能出现在末尾且只能用一次")
            }
        }

        // ---- Java 专属的选择器参数 ----
        listOf(
            "nbt=" to "nbt= 选择器参数是 Java 专属；基岩版用 hasitem= 或记分板条件替代",
            "predicate=" to "predicate= 是 Java 专属，网易版不支持",
            "scores=" to "scores= 是 Java 专属；基岩版请用 execute if score 判断",
            "advancements=" to "advancements= 是 Java 专属，网易版不支持",
        ).forEach { (k, msg) -> if (line.contains(k, ignoreCase = true)) out.add(msg) }

        // ---- minecraft: 前缀 (网易版物品/方块名一般不带; 粒子必须带) ----
        if (name != "particle" && Regex("""\bminecraft:""").containsMatchIn(line)) {
            out.add("网易版（基岩版内核）的指令参数一般**不带** minecraft: 前缀，建议去掉（粒子 particle 例外，它必须带）")
        }

        // ---- 必填目标选择器 ----
        if (name in NEEDS_TARGET && !line.contains('@')) {
            out.add("/$name 必须指定目标，例如 /$name @s …（@s=自己 / @a=所有玩家 / @p=最近玩家 / @e=所有实体）")
        }

        // ---- 配对检查 ----
        if (line.count { it == '"' } % 2 != 0) out.add("双引号数量是奇数，JSON 文本没有闭合")
        if (!balanced(line, '{', '}')) out.add("花括号 { } 不配对")
        if (!balanced(line, '[', ']')) out.add("方括号 [ ] 不配对")

        // ---- tellraw / titleraw 的 rawtext 结构 ----
        if ((name == "tellraw" || name == "titleraw") && !line.contains("rawtext")) {
            out.add("/$name 的文本必须是 rawtext 结构：{\"rawtext\":[{\"text\":\"文字\"}]}，不能直接写 {\"text\":\"文字\"}")
        }

        // ---- 方块 / 物品 ID (2026-10-02 补) ----
        // ★ 这一条**两个入口都要**: 模型自查时也要报 —— 原来只在客户端复查里查,
        //   于是出现"检查器说语法通过、可 ID 是错的"这种自相矛盾(用户 2026-10-02 报的)。
        out.addAll(checkIdArg(line))

        return out
    }

    private fun balanced(s: String, open: Char, close: Char): Boolean {
        var d = 0
        // 引号内的括号不算数 —— `{"text":"[提示]"}` 里的方括号是字面文本
        var inStr = false
        for (c in s) {
            if (c == '"') inStr = !inStr
            if (inStr) continue
            if (c == open) d++
            if (c == close) { d--; if (d < 0) return false }
        }
        return d == 0
    }

    // =================================================================================
    //  ★ 最终自检 (2026-10-02) —— 「跑完后再检查一次」
    // =================================================================================

    /**
     * 方块 / 物品 ID 所在的参数位: 指令名 -> 第几个 token (**含指令名本身**, 0 起)。
     *
     * 为什么是"数第几个 token"而不是完整解析: 这几条指令的参数位置**固定且简单**
     * (坐标不可能是空格分隔的多段), 数位就够了; 完整解析器一旦写错, 报出来的位置
     * 就是错的, 反而更坏。拿不准的指令(如 `replaceitem`)宁可不查。
     */
    private val ID_ARG_INDEX = mapOf(
        "fill" to 7,        // /fill x1 y1 z1 x2 y2 z2 <block> [replace <filter>]
        "setblock" to 4,    // /setblock x y z <block> [mode]
        "give" to 2,        // /give <target> <item> [count]
        "clear" to 2,       // /clear <target> [item] [maxCount]
    )

    /**
     * ID 里**不允许**出现的字符 —— 出现就是确定错, 不是"可能错"。
     *
     * 基岩版(网易版)的方块/物品 ID 只有 `[a-z0-9_]`。实测模型最常犯的两种:
     *  - 首字母大写 (`Cobblestone`) —— 抄 Java 常量名的习惯;
     *  - 直接写中文 (`/give @s 石头 64`) —— 把"中文名"当成 ID。
     */
    private val BAD_ID_CHAR = Regex("""[A-Z\u4e00-\u9fff\-]""")

    /**
     * 基岩版(网易版)**确定没有**的方块。**保守清单 —— 只列 100% 确定的**。
     *
     * ⚠️ 误报比漏报更伤: 这里每报一条, 客户端就会**多跑一轮请求**让模型去改。
     *    拿不准的一律不列(例如 `spawner`/`mob_spawner`、`grass`/`grass_block`
     *    这两个网易版老版本和新版本还不一样, 列进去会误伤)。
     */
    private val BEDROCK_MISSING_BLOCKS = mapOf(
        "light" to "光源方块是 Java 专属",
        "cave_air" to "基岩版只有 air，没有 cave_air",
        "void_air" to "基岩版只有 air，没有 void_air",
        "piston_head" to "活塞头是 Java 专属的内部方块",
        "moving_piston" to "移动中的活塞是 Java 专属的内部方块",
    )

    /**
     * 「名字本身就是方块」的方块 —— 后面再加 `_block` 就是错的。
     *
     * `cobblestone_block` ✗ / `cobblestone` ✓。这是模型非常爱犯的一种错:
     * 它记住了 `iron_block` / `gold_block` 这种形态, 于是给所有方块都加 `_block`。
     *
     * ⚠️ 只在这个白名单里才报 —— 否则 `iron_block` / `diamond_block` / `coal_block`
     *    这些**本来就是对的**会被误杀。
     */
    private val PLAIN_BLOCKS = setOf(
        "cobblestone", "mossy_cobblestone", "stone", "dirt", "coarse_dirt", "sand",
        "red_sand", "gravel", "clay", "obsidian", "bedrock", "netherrack",
        "end_stone", "glass", "sandstone", "red_sandstone", "bricks", "stone_bricks",
        "deepslate", "tuff", "calcite", "basalt", "blackstone", "andesite",
        "diorite", "granite", "soul_sand", "soul_soil", "magma", "prismarine",
    )

    /**
     * 任意 ``` 围栏: 第 1 组是语言标记(`mc 1.20+` / `json` / 空), 第 2 组是正文。
     *
     * ⚠️ 用 `[\s\S]*?` 而不是 `.*?` —— 正文是**多行**的, `.` 默认不匹配换行。
     */
    private val FENCE = Regex("```([^\\n]*)\\n([\\s\\S]*?)```")

    /**
     * 从文本里抠出所有**像指令的行**。
     *
     * 两档:
     *  - ```mc 围栏（[AiClient.FORMAT_PROMPT] 约定的那种）→ 里面的行**全算**;
     *  - 其它围栏（模型有时忘了写 `mc` 标记）→ 只认 `/` 开头的行。
     *
     * 为什么要把"忘了写 mc"也兜住: 忘了写标记的话 UI 渲染不出指令卡片, 而**复查也会一起失效** ——
     * 用户看到的正是"它说检查过了, 可 ID 还是错的"。
     *
     * 两种都跳过 `#` 开头的行 —— 那是 [AiClient.FORMAT_PROMPT] 允许的标题行, 不是指令。
     * 不跳的话每轮都会凭空报一条"没有以 / 开头", 白白多跑一轮修正。
     */
    private fun commandLines(text: String): List<String> {
        val out = ArrayList<String>()
        for (m in FENCE.findAll(text)) {
            val isMc = m.groupValues[1].trim().lowercase().startsWith("mc")
            // ⚠️ 用 for 而不是嵌套 forEach: 两层 forEach 里写 `return@forEach` 指向的是
            //    **最近**那一层, 容易看错; 显式 continue 没有歧义。
            for (raw in m.groupValues[2].split('\n')) {
                val s = raw.trim().removePrefix("```").let { LEADING_JUNK.replace(it, "") }.trim()
                if (s.isBlank() || s.startsWith("```") || s.startsWith("#")) continue
                if (!isMc && !s.startsWith("/")) continue
                out.add(s)
            }
        }
        return out
    }

    /**
     * ★★ **对模型的最终输出做一遍自检** (2026-10-02)。
     *
     * ## 为什么必须有这一层
     *
     * 原来只有**模型自己**在输出前调 `check_command` 这一道。两个洞:
     *  1. 模型**可以压根不调** —— 提示词说了也不保证, 尤其上游不支持工具调用时;
     *  2. 就算调了, 它**改完之后**再输出的那一版**没人验** ——
     *     "我核对过的那版"和"我最后交出去的那版"完全可以是两份东西。
     *
     * 用户报的就是这个:「这个审核不对啊，石头 id 都不对」。
     * 所以现在**客户端在拿到最终正文后自己再跑一遍**, 发现问题就喂回模型改。
     *
     * ## 只查代码块里的指令
     * 没有代码块的回答(闲聊、解释)直接返回空 —— 不打扰, 也不多花一轮请求。
     * 见 [commandLines] 对"哪些行算指令"的定义。
     *
     * @return 问题清单(每条形如 `指令 —— 问题`)。**空 = 通过**
     */
    fun audit(text: String): List<String> {
        val out = ArrayList<String>()
        commandLines(text).forEach { line ->
            // ⚠️ 走**同一个** checkOne —— 和模型自查用的是同一份规则。
            //    两边各写一套的话, 迟早出现"模型说没问题、客户端说有问题"。
            checkOne(line).forEach { out.add("$line  →  $it") }
        }
        return out
    }

    /**
     * 文本里有没有可检查的指令 —— [audit] 只扫代码块里的行, 见 [commandLines]。
     *
     * 调用方拿它决定**要不要跑复查**: 没有指令的回答(闲聊、解释、报错)直接跳过,
     * 既不打扰用户, 也不多发一轮请求。
     */
    fun hasCommandBlock(text: String): Boolean = commandLines(text).isNotEmpty()

    /**
     * 把 [audit] 的结果拼成一条**给模型看的修正要求** —— 两个通道共用。
     *
     * ★ 措辞上的两个要求:
     *  ① 明确说"重新输出**完整**答案" —— 不说的话模型容易只回一个 diff 或者一句"已修正";
     *  ② 明确说"不要解释改了什么" —— 它的解释会混进正文, 用户看到的就不再是干净的指令块。
     */
    fun fixPrompt(issues: List<String>): String {
        val sb = StringBuilder()
        sb.append("【客户端自动复查 · 发现问题】\n")
        sb.append("我把你上面输出的指令逐条跑了一遍静态复查，发现 ")
            .append(issues.size).append(" 处问题：\n")
        issues.take(20).forEach { sb.append("· ").append(it).append('\n') }
        if (issues.size > 20) sb.append("· …还有 ").append(issues.size - 20).append(" 处\n")
        sb.append("\n请**修正后重新输出完整答案**，保持原来的格式（```mc 代码块、一行一条指令）和结构。\n")
        sb.append("不要解释你改了什么，直接给修正后的完整内容。")
        return sb.toString()
    }

    /**
     * 检查指令里**方块/物品 ID 那一个参数**。
     *
     * 只查"确定错"的三类: 非法字符 / 基岩版没有的方块 / 多余的 `_block` 后缀。
     * 「这个 ID 存不存在」这种需要全量 ID 表才能答的问题**不在这里做** ——
     * 让模型自己去 `mc_wiki` 查(见 [CommandCheck] 类注释的分工)。
     */
    private fun checkIdArg(line: String): List<String> {
        val out = ArrayList<String>(2)
        var body = line.removePrefix("/").trim()
        // `execute ... run <cmd>` —— 只看最后一个 run 后面那段(真正的动作)
        val ri = body.lastIndexOf(" run ")
        if (ri >= 0) body = body.substring(ri + 5).trim()

        val tokens = body.split(Regex("\\s+"))
        if (tokens.isEmpty()) return out
        val idx = ID_ARG_INDEX[tokens[0].lowercase()] ?: return out
        if (idx >= tokens.size) return out

        // 去掉方块状态 `oak_log[axis=y]` → `oak_log`
        val id = tokens[idx].substringBefore('[').trim()
        if (id.isBlank()) return out
        // 纯数字 = 旧版数字 ID, 网易版认, 合法
        if (id.all { it.isDigit() }) return out
        // 选择器 / 相对坐标 / 槽位名 —— 不是 ID
        if (id.startsWith("@") || id.startsWith("~") || id.startsWith("^")) return out
        if (id.startsWith("slot.")) return out

        BAD_ID_CHAR.find(id)?.let {
            out.add("方块/物品 ID「$id」里有非法字符「${it.value}」—— " +
                "基岩版 ID 只能是小写字母、数字和下划线（中文名和首字母大写都不行）")
        }
        val bare = id.removePrefix("minecraft:")
        BEDROCK_MISSING_BLOCKS[bare]?.let { out.add("「$bare」$it，请换一个基岩版有的方块") }
        if (bare.endsWith("_block") && bare.removeSuffix("_block") in PLAIN_BLOCKS) {
            out.add("「$bare」多了 `_block` 后缀 —— 这个方块就叫「${bare.removeSuffix("_block")}」")
        }
        return out
    }

    /**
     * 一段文本里是否像"指令集合" —— 给 UI 判断要不要挂「上传到指令库」按钮。
     * 判据宽松: 非空行里**过半数**以 `/` 开头就算。宁可多给一个按钮, 也别让用户
     * 看着一条明明能用的指令却找不到上传入口。
     */
    fun looksLikeCommands(text: String): Boolean {
        val lines = text.split('\n').map { it.trim() }.filter { it.isNotBlank() && !it.startsWith("#") }
        if (lines.isEmpty()) return false
        return lines.count { it.startsWith("/") } * 2 >= lines.size
    }
}
