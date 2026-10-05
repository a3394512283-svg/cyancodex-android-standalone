package com.canglan.cyancodex.ai

import com.canglan.cyancodex.data.AiToolEvent
import com.canglan.cyancodex.data.ChatMsg
import com.canglan.cyancodex.data.UiMsg
import com.google.gson.Gson
import org.json.JSONObject

/**
 * 官方通道（服务器代理）的**「约定 JSON」工具调用**。
 *
 * ## 为什么需要它
 *
 * 代理只认 `messages` 一个字段（见 [AiClient] 的类注释）—— **塞不了 `tools`**，
 * 所以走不了标准 function calling。但「让 AI 自己决定搜什么」这件事在官方通道同样成立：
 * 用**约定输出格式**就能模拟出来。
 *
 * ```
 * 客户端 → 在最后一条 user 消息末尾附上协议说明
 * 模型   → 需要联网时**只输出一行 JSON**: {"tool":"search","query":"我的世界 Java版 更新"}
 * 客户端 → 解析出来 → 真搜一次 → 把结果作为一条 user 消息追加 → 再发一轮
 * 模型   → 不需要再搜就正常作答，循环结束
 * ```
 *
 * ## 与自备 Key 那套（标准 function calling）的差别
 *
 * | | 自备 Key | 官方通道 |
 * |---|---|---|
 * | 协议保障 | `tools` + `tool_calls`，有 schema、有 id 配对 | **没有**，全靠模型听话 |
 * | 解析 | 读结构化字段 | 猜文本，解析必须**宽容** |
 * | 计费 | 一次请求（多轮时多次） | **每轮都扣积分** |
 *
 * ★ 正因为"没有协议保障"，这里做了三件事兜底：
 *   ① 解析宽容（整体解析失败就从混杂文本里抠第一个 `{...}`）；
 *   ② 轮次卡死在 `maxRounds`（每轮都是真实计费请求，默认值见 `AI_TOOL_ROUNDS_DEFAULT`）；
 *   ③ 模型不听话时**就当普通回答**（`parseToolCall` 返回 null 即结束），不会卡死。
 */
object AiProxyAgent {

    /** 一轮代理调用的结果 —— 由调用方（AiScreen）用 `ApiClient.aiProxy` 填 */
    data class Turn(
        val ok: Boolean,
        val content: String,
        val error: String = "",
        val cost: Int = 0,
        val balance: Int? = null,
        val turnId: Long = 0L,
    )

    /** 整个循环的结果 */
    data class Result(
        val ok: Boolean,
        /** 最终答案（ok=false 时是错误文案） */
        val content: String,
        /** AI 自己发起并执行了的搜索关键词（空 = 没搜） */
        val searchQueries: List<String> = emptyList(),
        /** AI 查过的 MC 百科关键词（「指令专家」模式） */
        val wikiQueries: List<String> = emptyList(),
        /** 语法核对跑了几次（「指令专家」模式） */
        val checkCount: Int = 0,
        /** 实际发了几轮请求 */
        val rounds: Int = 0,
        /** **累计**消耗积分（多轮之和） */
        val cost: Int = 0,
        val balance: Int? = null,
        /** 最后一轮的 turnId（「有用/没用」反馈要用） */
        val turnId: Long = 0L,
        /** ★ 客户端在最终输出后自己复查过（同 `AiClient.ChatResult.rechecked`） */
        val rechecked: Boolean = false,
        /** 最后一次复查发现的问题数。0 = 通过（或压根没有指令块） */
        val recheckIssueCount: Int = 0,
        /** 复查发现的问题清单（最多 6 条） */
        val recheckIssues: List<String> = emptyList(),
    )

    /**
     * 最多几轮 —— 兜底值。
     *
     * 实际值由用户在「AI 设置 → 模型行为 → 工具调用轮数上限」里调, 经 [run] 的 `maxRounds`
     * 传进来, 与自备 Key 那条路**共用同一个数**（同一个概念不该有两个值）。
     *
     * ⚠️ 官方通道**每轮都是一次真实计费请求**（基础 5 积分 + 按 tokens），
     * 轮次上限就是**花钱上限** —— 设置面板的说明里写明了这一点。
     */
    private const val FALLBACK_ROUNDS = AI_TOOL_ROUNDS_DEFAULT

    /**
     * 附在最后一条 user 消息末尾的**输出格式说明**。
     *
     * ★ 与自备 API 那条路的 `AiClient.FORMAT_PROMPT` 是**同一份约定**（`mc` 围栏 + `#` 标题 +
     *   一行一条）。客户端 UI 靠它把指令渲染成独立卡片并提供「一键上传」——
     *   官方通道没有 system prompt 可用，只能把这段拼进 user 消息。
     */
    private val FORMAT_PROMPT = """
        
        ---
        【指令输出格式】需要给出可执行的指令时，一律使用代码块，语言标记写 mc，后面可跟适用版本：
        ```mc 1.20+
        # 一句话标题
        /第一条指令
        /第二条指令
        ```
        一行一条指令，行首以 / 开头；代码块第一行可以是 "# 标题"；解释文字写在代码块外面。
        方块/物品 ID 一律写小写英文 ID（只能用 a-z 0-9 和下划线）：不要写中文名（"石头"✗ 应为 cobblestone），
        不要首字母大写，不要凭空加 _block 后缀（cobblestone_block ✗ 应为 cobblestone）。拿不准先查 wiki。
    """.trimIndent()

    /**
     * 一轮里最多接受几条工具调用。
     *
     * 官方通道没有 schema 约束, 模型理论上可以刷几十行 JSON —— 那是失控的形态,
     * 而且**每执行一条都真扣积分**。卡在 4 条足够覆盖「同时查几个源」的正常需求。
     */
    private const val MAX_CALLS_PER_ROUND = 4

    /**
     * 协议说明的**公共头部** —— 联网搜索 + 查百科这两条, 与开关无关。
     *
     * ★★ 2026-10-05 补上「每行一个 JSON」的并行写法 —— 用户要「同时搜索几个 / 查看几个
     *    接口」。不说这一句的话模型只会一条条来, 每次都要多花一轮(多扣一次积分)。
     */
    private val TOOL_HEAD = """
        
        ---
        【工具】需要时**只输出一行 JSON**，不要有任何其他文字（不要解释、不要加代码块）：
        · 联网搜索：{"tool":"search","query":"搜索关键词"}
        · 查 MC 百科/我的世界 Wiki（写指令、查语法、确认 ID、确认网易版支不支持时用）：
          {"tool":"wiki","query":"命令 execute"}
    """.trimIndent()

    /** 语法核对那条 —— **只在语法核对开着**时拼进去（见 [TOOL_PROMPT] / [TOOL_PROMPT_NO_CHECK]） */
    private val CHECK_LINE = """
        · 核对指令语法（把指令逐行放进 commands，用 \n 分隔）：
          {"tool":"check","commands":"/give @s diamond 64\n/kill @e[type=item]"}
          它会检查语法**和方块/物品 ID**，报出来的必须改。
    """.trimIndent()

    /** 公共尾部 —— 并行写法 + 收尾叮嘱 */
    private val TOOL_TAIL = """
        ★ 需要同时查好几样东西时，**每行写一个 JSON**（最多 4 行），我会一次并行帮你查完，比一条条来更快：
          {"tool":"wiki","query":"命令 execute"}
          {"tool":"wiki","query":"目标选择器"}
          {"tool":"search","query":"我的世界 1.21 新方块"}
        关键词要简洁，不要照抄我的整句提问。我会把结果发给你，然后你再作答。
        不需要工具时，直接正常回答即可。简单问候、闲聊不要调工具。
    """.trimIndent()

    /**
     * 附在最后一条 user 消息末尾的协议说明（**含**语法核对）。
     *
     * ★ 措辞是刻意写强的（"只输出一行 JSON""不要有任何其他文字"）——
     *   这套协议没有任何 schema 约束，全靠模型自觉，说得含糊它就会把 JSON 混在正文里。
     */
    private val TOOL_PROMPT: String = TOOL_HEAD + "\n" + CHECK_LINE + "\n" + TOOL_TAIL

    /**
     * **不含**语法核对的版本（2026-10-05）—— 用户关掉「语法核对」chip 时用。
     *
     * ⛔ 关掉后**绝不能**再提 `check`: 没这个工具可用, 提了模型会一遍遍幻造调用,
     *    客户端只能回"无法执行", 白烧一到两轮积分。
     */
    private val TOOL_PROMPT_NO_CHECK: String = TOOL_HEAD + "\n" + TOOL_TAIL

    /** 工具返回 —— 以 **user 角色**回传：代理只保证转发 user/assistant/system 三种角色 */
    private fun toolReply(label: String, body: String) = """
        【$label】
        $body

        （还需要更多信息就继续只输出一行 JSON；否则直接回答我的问题。）
    """.trimIndent()

    /** 宽松匹配一个扁平 JSON 对象（工具的 JSON 是扁平的，不支持嵌套） */
    private val JSON_OBJ = Regex("""\{[^{}]*\}""")

    /** 解析出来的工具调用 */
    data class ToolCall(val kind: String, val arg: String)

    /**
     * 从模型输出里解析工具调用。
     *
     * @return 工具调用；不是工具调用则 null（调用方据此结束循环、按普通回答处理）
     */
    fun parseToolCall(content: String): ToolCall? = parseToolCalls(content).firstOrNull()

    /**
     * **解析一轮里模型给出的全部工具调用** (2026-10-05)。
     *
     * 为什么从"只取第一个"改成"取全部": 用户要「可以让 ai 同时搜索几个或者查看几个 api 接口」。
     * 官方通道没有 `tools` schema, 模型想并行只能**一次输出多行 JSON** —— 而旧实现
     * `parseToolCall` 用 `findAll(...).firstOrNull()` 只认第一条, 后面几条被**静默丢弃**,
     * 模型以为都调了、其实只执行了一个, 于是反复重发。
     *
     * 约定(会写进 [TOOL_PROMPT]): 需要同时查多个时,**每行一个 JSON**, 一行一个工具调用。
     *
     * ⚠️ 去重: 同一行 JSON 重复出现(模型偶尔会把同一句写两遍)只保留一次 ——
     *    重复执行等于白扣一次积分。
     * ⚠️ 上限 [MAX_CALLS_PER_ROUND]: 一行行无节制地刷 JSON 是"模型失控"的形态,
     *    卡在 4 条以内, 免得一次调用把积分烧光。
     */
    fun parseToolCalls(content: String, limit: Int = MAX_CALLS_PER_ROUND): List<ToolCall> {
        val t = content.trim()
        if (t.isEmpty()) return emptyList()
        val out = ArrayList<ToolCall>()
        val seen = HashSet<String>()

        fun take(tc: ToolCall?) {
            if (tc == null || out.size >= limit) return
            // 用 "kind\u0000arg" 当去重键 —— 工具名+参数完全相同才算重复
            if (seen.add(tc.kind + '\u0000' + tc.arg)) out.add(tc)
        }

        // ① 理想情况: 整个输出就是那行 JSON
        take(parseOne(t))
        // ② 现实情况: 多行 JSON / 裹在代码块里 / 前后带了"好的，我来查"
        for (m in JSON_OBJ.findAll(t)) {
            if (out.size >= limit) break
            take(parseOne(m.value))
        }
        // ③ check 的 commands 里可能嵌着 tellraw 的 rawtext，`{[^{}]*}` 会在第一个内层 } 处截断 ——
        //    退一步：取第一个 { 到最后一个 } 之间的整段再试一次。
        if (out.isEmpty()) {
            val a = t.indexOf('{')
            val b = t.lastIndexOf('}')
            if (a >= 0 && b > a) take(parseOne(t.substring(a, b + 1)))
        }
        return out
    }

    private fun parseOne(s: String): ToolCall? = try {
        val o = JSONObject(s)
        // ⚠️ 走 jsonStr 而不是 optString: `{"tool":"search","query":null}` 用 optString
        //    会得到字符串 "null", `ifBlank { null }` 拦不住 → **真的拿 "null" 去搜**。
        when (jsonStr(o, "tool")) {
            "search" -> jsonStr(o, "query").trim().ifBlank { null }?.let { ToolCall("search", it) }
            "wiki" -> jsonStr(o, "query").trim().ifBlank { null }?.let { ToolCall("wiki", it) }
            "check" -> jsonStr(o, "commands").trim().ifBlank { null }?.let { ToolCall("check", it) }
            else -> null
        }
    } catch (e: Exception) {
        null
    }

    /**
     * 跑完整个「发请求 → 解析 → 执行工具 → 再发」的循环。
     *
     * @param messages 完整对话历史（含本轮刚发出的用户消息）
     * @param searchEnabled 关掉时不注入搜索协议、也不解析 search —— 省 token，也免得正常回答里的
     *                      `{"tool":...}` 字样被误判
     * @param cmdExpert 「指令专家」模式：额外挂 MC 百科 + 语法核对两个工具。
     *                  与 [searchEnabled] **互相独立** —— 它俩都不需要 Key，谁开谁生效。
     * @param syntaxCheck ★ **核语法细粒度开关**（2026-10-05）。见 `AiClient.chat` 的同名参数。
     *                  关掉后 [TOOL_PROMPT] 会走"没有核对工具"的版本, 且不解析 `check`。
     * @param recheck ★ **客户端复查开关**（2026-10-05）。见 `Session.aiRecheckOn`。
     * @param maxRounds 工具调用轮数上限（用户在设置里调）。轮次用尽时会提示"这个值可以调"。
     * @param call 实际发一轮请求。入参是 messages 的 JSON，出参是 [Turn]
     * @param onEvent ★ **过程事件**（2026-10-02）。每走一步回调一次，内容是一句给用户看的话。
     *                与 `AiClient.chat` 的同名参数**语义完全一致** —— 两条通道的过程展示不该有差别。
     * @param onToolEvent ★ **结构化工具事件**（2026-10-05）。见 `AiClient.chat` 的同名参数。
     *                官方通道与自备通道共用同一套事件模型, 所以时间线 UI 只需写一份。
     */
    suspend fun run(
        messages: List<UiMsg>,
        searchEnabled: Boolean,
        cmdExpert: Boolean = false,
        syntaxCheck: Boolean = true,
        recheck: Boolean = true,
        maxRounds: Int = FALLBACK_ROUNDS,
        onEvent: ((String) -> Unit)? = null,
        onToolEvent: ((AiToolEvent) -> Unit)? = null,
        call: suspend (String) -> Turn,
    ): Result {
        val gson = Gson()
        val hist = messages.toMutableList()
        // 夹一道范围, 理由同 AiClient.chat —— 官方通道尤其不能放任, 每轮都是钱
        val roundLimit = maxRounds.coerceIn(AI_TOOL_ROUNDS_MIN, AI_TOOL_ROUNDS_MAX)

        // 格式约定**总是**附上：不附的话官方通道的回答里指令不是 `mc` 围栏，UI 渲染不出指令卡片。
        // 协议说明拼在**最后一条 user 消息**末尾，而不是单插一条 system ——
        // 中间插 system 有些实现会拒（"system 只能在开头"），拼在 user 里最保险。
        val i = hist.indexOfLast { it.role == "user" }
        if (i >= 0) {
            var extra = FORMAT_PROMPT
            // ★ 协议说明按开关分流 (2026-10-05): 关掉语法核对时就别在 user 消息里承诺
            //   `check` 工具 —— 提了模型会一直发 {"tool":"check"}, 客户端又只能回"无法执行",
            //   白烧一到两轮。用**两份完整的常量**而不是字符串替换, 免得替换失配时静默失效。
            if (searchEnabled || cmdExpert) extra += (if (syntaxCheck) TOOL_PROMPT else TOOL_PROMPT_NO_CHECK)
            hist[i] = hist[i].copy(content = hist[i].content + extra)
        }

        val queries = ArrayList<String>()
        val wikiQueries = ArrayList<String>()
        var checkCount = 0
        var cost = 0
        var balance: Int? = null
        var turnId = 0L
        var round = 0
        /** 客户端复查额外发过几轮（上限 1）。⚠️ 官方通道**每轮都扣积分**，所以这个 1 更要守住 */
        var recheckRounds = 0
        var rechecked = false
        var recheckIssueCount = 0
        var recheckIssues: List<String> = emptyList()

        while (round < roundLimit) {
            round++
            val json = gson.toJson(hist.map { ChatMsg(it.role, it.content) })
            val t = call(json)

            if (!t.ok) {
                // 失败就把已经发生的事（搜过什么、扣了多少）一并交出去, 别让 UI 只显示一句报错
                return Result(
                    ok = false,
                    content = t.error.ifBlank { "AI 请求失败" },
                    searchQueries = queries, wikiQueries = wikiQueries, checkCount = checkCount,
                    rounds = round, cost = cost + t.cost, balance = balance, turnId = turnId,
                )
            }
            cost += t.cost
            if (t.balance != null) balance = t.balance
            if (t.turnId > 0) turnId = t.turnId

            // ★ 2026-10-05: 从"只认第一条工具调用"改成"认一轮里的**全部**" ——
            //   模型一次输出多行 JSON 时, 旧实现只执行第一条、静默丢弃其余,
            //   于是模型以为都调了、实际只做了一个, 反复重发。详见 [parseToolCalls]。
            val tcs = parseToolCalls(t.content)
            // 关掉的工具不执行 —— 否则用户明明没开搜索，模型写了个 {"tool":"search"} 也会真去搜
            val usable = tcs.filter { tc ->
                when (tc.kind) {
                    "search" -> searchEnabled
                    "wiki" -> cmdExpert
                    // ★ 2026-10-05: 核语法要**同时**满足"指令专家开着"与"语法核对开着"
                    "check" -> cmdExpert && syntaxCheck
                    else -> false
                }
            }
            if (tcs.isEmpty() || usable.isEmpty()) {
                // 不是工具调用 = 终局
                // ---- ★ 最终自检: 客户端自己再验一遍 (2026-10-02), 与自备 Key 那条路同一套规则 ----
                // ★ 2026-10-05: 加 syntaxCheck / recheck 两道闸, 理由见 AiClient.chat 的同名处
                if (cmdExpert && syntaxCheck && recheck && CommandCheck.hasCommandBlock(t.content)) {
                    onEvent?.invoke("复查方块 / 物品 ID…")
                    // 与 AiClient.chat 的同名处保持**逐字一致**的写法(同一个 t0、结束带 endAt) ——
                    // 少一个 endAt, 时间线上的耗时会恒为 0, 两条通道观感就对不上了。
                    val t0 = android.os.SystemClock.uptimeMillis()
                    onToolEvent?.invoke(
                        AiToolEvent(
                            kind = AiToolEvent.KIND_RECHECK,
                            title = "复查方块 / 物品 ID",
                            state = AiToolEvent.STATE_RUNNING,
                            startAt = t0,
                        )
                    )
                    val issues = CommandCheck.audit(t.content)
                    rechecked = true
                    recheckIssueCount = issues.size
                    recheckIssues = issues.take(6)
                    onToolEvent?.invoke(
                        AiToolEvent(
                            kind = AiToolEvent.KIND_RECHECK,
                            title = "复查方块 / 物品 ID",
                            detail = if (issues.isEmpty()) "通过，未发现问题" else "发现 ${issues.size} 处疑似问题",
                            state = if (issues.isEmpty()) AiToolEvent.STATE_OK else AiToolEvent.STATE_FAILED,
                            startAt = t0,
                            endAt = android.os.SystemClock.uptimeMillis(),
                        )
                    )
                    if (issues.isNotEmpty() && recheckRounds < 1 && round < roundLimit) {
                        recheckRounds++
                        onEvent?.invoke("复查发现 ${issues.size} 处问题，正在让 AI 修正…")
                        hist.add(UiMsg("assistant", t.content))
                        hist.add(UiMsg("user", CommandCheck.fixPrompt(issues)))
                        continue
                    }
                    if (issues.isEmpty()) {
                        onEvent?.invoke(if (recheckRounds > 0) "复查通过" else "复查通过 · 未发现 ID 问题")
                    } else {
                        onEvent?.invoke("复查发现 ${issues.size} 处疑似问题")
                    }
                }
                return Result(
                    true, t.content, queries, wikiQueries, checkCount, round, cost, balance, turnId,
                    rechecked = rechecked,
                    recheckIssueCount = recheckIssueCount,
                    recheckIssues = recheckIssues,
                )
            }

            // ---- ★ 并发执行本轮全部工具 (2026-10-05) ----
            // 执行层与自备通道**共用** [AiToolRunner] —— 两条通道的工具语义必须一致,
            // 否则会出现"自备通道查了维基百科、官方通道没查"这种不对称 bug。
            val plan = usable.map { tc ->
                when (tc.kind) {
                    "search" -> AiToolRunner.Call(AiToolRunner.TOOL_SEARCH, query = tc.arg)
                    "check" -> AiToolRunner.Call(AiToolRunner.TOOL_CHECK, commands = tc.arg)
                    else -> AiToolRunner.Call(AiToolRunner.TOOL_WIKI, query = tc.arg)
                }
            }
            // 账本先记(不等结果)
            plan.forEach { c ->
                when (c.name) {
                    AiToolRunner.TOOL_SEARCH -> queries.add(c.query)
                    AiToolRunner.TOOL_WIKI -> wikiQueries.add(c.query)
                    AiToolRunner.TOOL_CHECK -> checkCount++
                }
            }
            val outcomes = AiToolRunner.runAll(
                plan,
                wikiMulti = true,
                onEvent = { ev ->
                    onToolEvent?.invoke(ev)
                    // 官方通道的"发一条 user 消息"是**一次多行**的, 所以这里的 onEvent
                    // 只在开始时报一句 —— 每行都单独报一遍会刷屏。
                    if (ev.isRunning) onEvent?.invoke(ev.title)
                },
            )

            // 模型那行 JSON 要放回历史 —— 不放的话下一轮它会看到"自己什么都没说"就收到结果, 容易懵
            hist.add(UiMsg("assistant", t.content))
            // ★ 多条结果**合成一条** user 消息回传, 而不是每条各发一条:
            //   官方通道每条 user 消息都会触发模型再想一轮, 多条 = 多轮 = 多扣积分。
            //   合成一条既省积分, 又和"并行查完一起给你"的语义对得上。
            val reply = buildString {
                append("【工具结果】\n")
                outcomes.forEachIndexed { i, o ->
                    if (i > 0) append('\n')
                    append("· ").append(o.call.query.ifBlank { o.call.commands.take(40) })
                        .append(" → ").append(o.summary).append('\n')
                    append(o.text.take(1600)).append('\n')
                }
                append("\n（以上为查询结果。信息够了就直接回答我的问题；还需要别的就继续只输出一行 JSON。）")
            }
            hist.add(UiMsg("user", reply))
        }

        return Result(
            ok = true,
            content = "（已连续调用工具 $roundLimit 轮仍未得出结论。\n" +
                "可以在「AI 设置 → 模型行为 → 工具调用轮数上限」把上限调高（当前 $roundLimit），" +
                "或者把问题拆小一点再问。注意官方通道每一轮都会计费。）",
            searchQueries = queries, wikiQueries = wikiQueries, checkCount = checkCount,
            rounds = round, cost = cost, balance = balance, turnId = turnId,
        )
    }
}
