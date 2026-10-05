package com.canglan.cyancodex.ai

import android.os.SystemClock
import com.canglan.cyancodex.data.AiToolEvent
import com.canglan.cyancodex.data.Session
import com.canglan.cyancodex.data.UiMsg
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 安全读一个**可能为 JSON `null`** 的字符串字段。
 *
 * ⛔ **不要用 `JSONObject.optString` 读这类字段。** `org.json` 对 `JSONObject.NULL`
 *    走的是 `String.valueOf(NULL)`, 返回的是**字符串 `"null"`** ——
 *    既不是空串, 也**不会**走 `optString(key, fallback)` 的兜底分支。
 *
 * 这个坑在"读大模型返回体"这个场景里是**常态, 不是边角**, 已经踩到第四次:
 *  ① `message.content` —— 带 `tool_calls` 的 assistant 消息按协议**就应该是 `null`**;
 *  ② `message.reasoning_content` —— 第一轮有思考、第二轮没有时返回 `null`,
 *     于是思考过程末尾凭空多出一个 " null"(2026-10-01 用户截图报的);
 *  ③ `tool_calls[].function.arguments` 里的 `query` —— 会真的拿字符串 "null" 去搜;
 *  ④ `tool_calls[].id` —— 变成 "null" 后跟 tool 消息配不上对, 上游直接 400。
 *
 * ★ 通律: **凡是读模型 / 第三方接口的字段, 一律走这里。** 只有"字段值一定是字符串"
 *   (比如自家常量、内部构造的对象)才可以放心用 `optString`。
 */
internal fun jsonStr(o: JSONObject?, key: String): String {
    // ⚠️ `opt` 在"键不存在"时返回 null, 在"值显式为 null"时返回 `JSONObject.NULL` 这个**对象**。
    //    所以 elvis 只兜前者, 后者必须再判一次 —— 少判一次就是上面那四个 bug。
    val v = o?.opt(key) ?: return ""
    return if (v === JSONObject.NULL) "" else v.toString()
}

/** [jsonStr] 的数组版: `JSONArray.optString(i)` 对元素为 `null` 时同样是返回字符串 "null"。 */
internal fun jsonStr(a: JSONArray?, index: Int): String {
    val v = a?.opt(index) ?: return ""
    return if (v === JSONObject.NULL) "" else v.toString()
}

/**
 * 「工具调用轮数上限」的可调范围与默认值。
 *
 * 用户在「AI 设置 → 模型行为」里用滑杆调, 存在 [com.canglan.cyancodex.data.Session.aiToolRounds]。
 * 两条通道（自备 Key 直连 / 官方代理）共用这一个值, 免得"同一个概念两个数"。
 *
 * ★ 默认从 4 提到 6 是**用户报出来的**: 加了「指令专家」之后, 一轮问答里模型可能要
 *   `check_command` 核一遍、`mc_wiki` 查一次、改完再核一遍 —— 4 轮不够, 于是撞上
 *   「已连续调用工具 4 轮仍未得出结论」。6 轮覆盖这个正常形态。
 *
 * ★★ 2026-10-05 上限 12 → **50**（用户明确要求「工具使用次数上限改为 50」）：
 *   默认值也一并提到 50。用户的实际使用形态是**一次让 AI 查很多东西**
 *   （多源并发检索 + 反复核对 + 改完再核），12 轮经常不够用。
 *   ⚠️ 上限就是**成本上限**（每轮都是一次真实请求，官方通道还按轮计费），
 *   50 轮在官方通道是笔不小的开销 —— 设置面板的说明里写明了这一点，用户自己权衡。
 */
const val AI_TOOL_ROUNDS_MIN = 2
const val AI_TOOL_ROUNDS_MAX = 50
const val AI_TOOL_ROUNDS_DEFAULT = 50

/**
 * AI Agent: **直连**用户自备的 OpenAI 兼容接口 (支持 DeepSeek/通义/Kimi 等)。
 *
 * 为什么是直连而不是走服务器代理 (2026-10-01 起):
 *  - 「获取模型」要打 `GET /models`, 代理根本没有这个端点;
 *  - 「思考强度」要往请求里塞 `reasoning_effort` / `enable_thinking` / `thinking`,
 *    代理只认 apiBase/apiKey/model/messages 四个字段, 塞进去大概率被丢掉 —— 功能会静默失效;
 *  - 「思考过程」要读响应里的 `reasoning_content`, 代理的返回体只有 content;
 *  - 直连还能带上 [buildSystemPrompt] (MC 指令系统提示 + 网易版语法自查), 代理路径是不带的。
 *
 * 没配自备 Key 时仍然走官方平台 AI (积分计费), 见 AiScreen 的分支 —— 那条路没有直连的可能,
 * 因为平台 Key 在服务器上。
 */
object AiClient {

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    const val SYSTEM_PROMPT = """你是网易版《我的世界》的指令助手。
你的任务: 帮助用户编写网易版(手机基岩版内核)指令, 每次输出指令前必须先用【网易版语法自查】对照核对语法, 语法不对就改正后再输出。
要求:
1. 直接输出可直接粘贴的指令, 用 / 开头;
2. 涉及多条指令时, 分条列出并说明每条放入什么模式(脉冲/循环/链式)的命令方块;
3. 回答简洁, 中文;
4. 生成音乐、T显、滚动字幕等时, 给出完整可用的指令集合;
5. 用户提到指令时, 严格对照下发的语法条目检查 (尤其 execute/scoreboard/titleraw 的写法)。
6. 数量换算铁律: 中文"一组/一叠" = 64 个, "半组" = 32 个, "一箱" = 1728 个(27组); 用户要"一组钻石/一组xx"时必须生成 give 指令数量为 64, 绝不生成 1 个; 物品数量按《我的世界》堆叠上限换算 (钻石/铁锭/羊毛等 64, 末影珍珠/雪球 16, 工具/装备 1)。
7. 方块/物品 ID 铁律: 一律写**小写英文 ID**, 只能用 a-z 0-9 和下划线 —— 不要写中文名("石头"✗ 应为 cobblestone), 不要首字母大写(Cobblestone ✗), 不要凭空加 _block 后缀(cobblestone_block ✗ 应为 cobblestone)。拿不准的 ID **先查 mc_wiki 再写**, 不要凭印象编。"""

    /**
     * **指令输出格式约定** —— App 的 UI 靠它把指令渲染成独立卡片并提供「一键上传」。
     *
     * ⚠️ 改这段等于改**前后端协议**: 解析在 `ui/components/Markdown.kt` 的 [splitCodeBlocks],
     *    两边的字段名必须同步改。
     *
     * 为什么要把格式写死: 之前模型爱怎么写就怎么写 —— 有的用 ``` 不带语言, 有的用行内代码,
     * 有的把标题写在代码块外面。UI 只能一律按普通代码块渲染, 用户既看不到"这是一条指令",
     * 也没法一键带走。约定成 `mc` 围栏 + `#` 标题 + 一行一条之后, 上面三件事全都成立。
     */
    private const val FORMAT_PROMPT = """

【指令输出格式 · 必须遵守】
需要给出可执行的指令时, 一律使用代码块, 语言标记写 mc, 后面可跟适用版本:
```mc 1.20+
# 一句话标题
/第一条指令
/第二条指令
```
- 一行一条指令, 行首以 / 开头; 不要写序号、不要在行内写注释;
- 代码块第一行可以是 "# 标题" (会作为指令名自动填进上传表单), 也可以不写;
- 解释文字一律写在代码块**外面**, 不要混进代码块;
- 只要指令是对的, 就**不要**在代码块里用 "..." 之类省略号, 用户会直接复制去用。"""

    /**
     * **「指令专家」模式**追加的提示段 (见 [chat] 的 `cmdExpert`)。
     *
     * 只讲"怎么用工具", 不讲"怎么写指令" —— 后者在 [SYSTEM_PROMPT] 和 [NetEaseWiki.FULL_NOTES] 里。
     */
    private const val TOOLS_PROMPT = """

【工具 · 指令专家模式】
- mc_wiki: 查《我的世界》Wiki / MC 百科的条目原文。**不确定语法、方块/实体 ID、或者"网易版到底支不支持"时, 先查再写**, 不要凭印象编。
  ⚠️ 返回内容里标注「[仅Java版]」的写法在网易版(基岩版内核)**不可用**, 必须换基岩版写法或放弃。
- check_command: 核对指令语法。**在输出指令代码块之前**, 把你写好的指令逐行传给这个工具核对一遍, 有报错就改好再输出。
  它除了语法, 还会查**方块/物品 ID**（非法字符、基岩版没有的方块、多余的 _block 后缀）—— 报出来的必须改。
  一次把所有指令都传进去(一行一条), 不要一条一条调。
- 简单问候、常识问答、闲聊**不要**调工具, 直接回答。"""

    /**
     * [TOOLS_PROMPT] 的**去掉核语法**版 (2026-10-05) —— 用户关掉「语法核对」chip 时用。
     *
     * ⛔ 关掉核对工具后**不能再提 `check_command`**: 提了模型会一遍遍尝试调用一个
     *    不存在的工具(幻造), 客户端回它"无法执行", 白烧两轮。所以这里把它整段去掉,
     *    只留 `mc_wiki`, 并把"输出前自查"的落点改成**靠自己仔细检查**。
     */
    private const val TOOLS_PROMPT_NO_CHECK = """

【工具 · 指令专家模式】
- mc_wiki: 查《我的世界》Wiki / MC 百科 / 维基百科等**多个知识源**的条目原文。**不确定语法、方块/实体 ID、或者"网易版到底支不支持"时, 先查再写**, 不要凭印象编。
  ⚠️ 返回内容里标注「[仅Java版]」的写法在网易版(基岩版内核)**不可用**, 必须换基岩版写法或放弃。
- ⚠️ 本次**没有开启语法核对工具**, 所以输出指令前请**自己逐条仔细检查语法与方块/物品 ID**（小写英文 ID、无非法字符、无多余的 _block 后缀）。
- 简单问候、常识问答、闲聊**不要**调工具, 直接回答。"""

    /**
     * ★ **「先联网搜索」提示段** (2026-10-05) —— 用户开了「智能搜索」时追加。
     *
     * 用户原话:「进行任务时先进行查询」。他要的是 **DeepSeek 那种"先搜后答"**:
     * 不是"等模型自己想起来才搜", 而是**把搜索固定成回答前的第一步**。
     *
     * 为什么要写死这一句: 模型默认倾向"凭已有知识直接答", 只在它**自认为**不确定时才调工具。
     * 而时效性/版本相关的问题(网易版某个方块 ID 改没改、某条指令在新版本还行不行),
     * 模型的"自信"恰恰是最不可靠的。明说"先搜一次"能把这条路径变成默认路径。
     *
     * ⚠️ 留了例外口子: 纯寒暄/翻译/写代码这类**不需要外部信息**的问题直接答 ——
     *   不然用户说一句"你好"也要先烧一次搜索, 又慢又费。
     */
    private const val SEARCH_FIRST_PROMPT = """

【智能搜索 · 先查后答】
- 用户开启了智能搜索。凡是**事实性 / 时效性 / 版本相关**的问题，**回答前先调用 web_search 联网搜索一次**（角度不够时可以换关键词多搜几组），拿到资料后再作答，不要凭记忆直接下结论。
  （若本轮**没有**可用的搜索工具，就直接凭已有知识回答并说明未联网。）
- 回答里引用到搜索结果时标出来源编号（如 [1]）。
- 若搜索接口超时或没有取到资料，**如实说明"未查到"**，再用已有知识给出通用做法，**不要编造来源**。
- 纯寒暄、翻译、写作、纯代码改写这类不需要外部信息的问题，直接回答即可，不必搜索。"""

    /** 组装系统提示词: 基础提示 + 网易版要点 + 本次问题相关的指令语法自查 */
    private fun buildSystemPrompt(
        userText: String,
        extra: String = "",
        cmdExpert: Boolean = false,
        syntaxCheck: Boolean = true,
        searchTool: Boolean = false,
    ): String {
        val hit = NetEaseWiki.lookup(userText)
        val sb = StringBuilder(SYSTEM_PROMPT)
        sb.append(FORMAT_PROMPT)
        sb.append('\n').append(NetEaseWiki.FULL_NOTES)
        if (hit.isNotEmpty()) sb.append('\n').append("【网易版语法自查】\n").append(hit)
        // ★ 2026-10-05: 把「核语法」那段的措辞按开关分流 —— 关掉核对工具时不能再叫模型
        //   去"调 check_command 核对", 那个工具压根没挂, 模型会一直试。
        if (cmdExpert) sb.append(if (syntaxCheck) TOOLS_PROMPT else TOOLS_PROMPT_NO_CHECK)
        // ★ 2026-10-05: 「先查后答」—— 开了智能搜索就把"先搜一次"写进系统提示
        if (searchTool) sb.append(SEARCH_FIRST_PROMPT)
        if (extra.isNotEmpty()) sb.append(extra)
        return sb.toString()
    }

    // =================================================================================
    //  对话
    // =================================================================================

    /**
     * 一次直连对话的结果。
     *
     * @param content   正文 (最终答案)
     * @param reasoning 思考过程, 上游没返回就是空串。来源字段见 [readReasoning]。
     *                  ★ 走工具调用时是**多轮思考拼接**起来的
     * @param note      需要提示给用户的旁注 (例如"思考参数不被支持, 已自动降级重试")
     * @param searchQueries AI 自己决定发起并执行了的搜索关键词 (见 [chat] 的 `searchTool`)。
     *                  空 = 这轮没搜。UI 拿它显示「使用搜索能力」那一行
     * @param wikiQueries AI 查过 MC 百科的关键词 (「指令专家」模式, 见 [chat] 的 `cmdExpert`)。
     *                  UI 拿它显示「查阅 MC 百科 · …」那一行 —— 用户看得到"它去哪查了资料",
     *                  比只显示一句"已思考"可信得多。
     * @param checkCount 这轮把指令递给语法核对工具跑了几次。0 = 没核对
     * @param rechecked ★ **客户端自己复查过** (2026-10-02)。最终正文里有 ```mc 代码块时,
     *                  由客户端跑一遍 [CommandCheck.audit] —— 不看模型"说自己检查过了"。
     * @param recheckIssueCount 最后一次复查发现的问题数。0 = 通过（或压根没有指令块）
     * @param recheckIssues 复查发现的问题清单（最多留 6 条给 UI 展示）。空 = 通过
     */
    data class ChatResult(
        val content: String,
        val reasoning: String = "",
        val note: String = "",
        val searchQueries: List<String> = emptyList(),
        val wikiQueries: List<String> = emptyList(),
        val checkCount: Int = 0,
        val rechecked: Boolean = false,
        val recheckIssueCount: Int = 0,
        val recheckIssues: List<String> = emptyList(),
    )

    // =================================================================================
    //  工具调用 (function calling)
    // =================================================================================

    /** 工具名。模型在 `tool_calls[].function.name` 里回的就是它 */
    private const val TOOL_SEARCH = "web_search"

    /** 查 MC 百科 / Minecraft Wiki (免费无 Key, 属于「指令专家」模式) */
    private const val TOOL_WIKI = "mc_wiki"

    /** 核对指令语法 (纯本地规则, 属于「指令专家」模式) */
    private const val TOOL_CHECK = "check_command"

    /**
     * 工具调用最多几轮 —— 兜底值。
     *
     * 实际值由用户在「AI 设置 → 模型行为 → 工具调用轮数上限」里调, 经 [chat] 的
     * `maxToolRounds` 传进来。这里只在该参数没传时用。
     *
     * 为什么必须要有上限: 模型理论上可以"调工具 → 看结果 → 再调"无限循环下去,
     * 每次都是一次真实请求 + 一次真实计费。
     */
    private const val FALLBACK_TOOL_ROUNDS = AI_TOOL_ROUNDS_DEFAULT

    /**
     * 交给模型的搜索工具定义 (OpenAI 兼容的 `tools` 形态)。
     *
     * ★ 描述里的每一句都在**降低乱调用的概率**:
     *  - "涉及最新信息/不确定时才调用" —— 不写这句, 模型会拿"你好"也去搜一次;
     *  - "关键词而非整句" —— 搜索 API 对整句提问的召回明显更差;
     *  - "可多次调用、每次换关键词" —— 用户要的就是"AI 自己决定怎么搜"。
     */
    private val SEARCH_TOOL: JSONObject = JSONObject().apply {
        put("type", "function")
        put("function", JSONObject().apply {
            put("name", TOOL_SEARCH)
            put(
                "description",
                "搜索互联网获取实时资料。当问题涉及最新版本/新闻/近期变动，或你不确定、需要核对事实时调用。" +
                    "可以连续调用多次，每次换一组更聚焦的关键词。"
            )
            put("parameters", JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("query", JSONObject().apply {
                        put("type", "string")
                        put(
                            "description",
                            "搜索关键词。用简短的关键词组合，不要照抄用户的整句提问；" +
                                "用户用中文就用中文，涉及英文专有名词时带上原文。"
                        )
                    })
                })
                put("required", JSONArray().put("query"))
            })
        })
    }

    /** 从 `tool_calls[].function.arguments` 里抠出某个字符串参数。模型偶尔会吐非法 JSON, 兜住别炸 */
    private fun parseToolArg(args: String, key: String): String = try {
        // ⛔ 这里必须走 jsonStr: `{"query":null}` 用 optString 会得到字符串 "null",
        //    然后**真的拿 "null" 去搜**(还搜得回来一堆结果), 用户完全看不出哪里错了。
        jsonStr(JSONObject(args.ifBlank { "{}" }), key).trim()
    } catch (e: Exception) {
        ""
    }

    /**
     * MC 百科 / Minecraft Wiki 检索工具 (「指令专家」模式)。
     *
     * ★ 描述里的三句话各治一个毛病:
     *  - "不确定时先查" —— 不写它只会在被明确要求"搜一下"时才调;
     *  - "用中文条目名" —— 中文站的条目叫「命令/execute」「记分板」, 拿英文词去搜召回很差;
     *  - "标注 [仅Java版] 的不可用" —— **最关键的一句**。wiki 正文里 Java/基岩两版的写法
     *    混在一起, 不点破的话模型会把 `/data`、`/execute store` 当成通用写法抄给网易版用户。
     */
    private val WIKI_TOOL: JSONObject = JSONObject().apply {
        put("type", "function")
        put("function", JSONObject().apply {
            put("name", TOOL_WIKI)
            put(
                "description",
                "查询《我的世界》Wiki / MC 百科 / 维基百科等**多个知识源**的条目原文（免费、权威）。" +
                    "一次调用会**同时检索多个源**（MC 百科中/英、mcmod.cn、维基百科中/英）并合并结果，" +
                    "所以你不必为同一个问题拆成多次调用 —— 想查不同条目时分别调用即可。" +
                    "写指令前若不确定语法、方块/实体/物品 ID、或某个功能在网易版（基岩版内核）是否可用，" +
                    "先用它查证，不要凭印象编。可以用中文条目名，如「命令 execute」「命令方块」「记分板」「目标选择器」。"
            )
            put("parameters", JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("query", JSONObject().apply {
                        put("type", "string")
                        put("description", "条目关键词。用中文条目名或指令名，如「命令 execute」「记分板」「/fill」。")
                    })
                })
                put("required", JSONArray().put("query"))
            })
        })
    }

    /**
     * 指令语法核对工具 (「指令专家」模式)。
     *
     * 参数只有一个 `commands` 字符串而不是数组 —— 数组形态对模型来说更容易写坏
     * (漏引号、漏逗号), 而"一行一条"的自然语言约定它执行得比 JSON 数组稳。
     * 客户端 [CommandCheck.check] 里对序号、围栏、空行都做了清洗, 容错足够。
     */
    private val CHECK_TOOL: JSONObject = JSONObject().apply {
        put("type", "function")
        put("function", JSONObject().apply {
            put("name", TOOL_CHECK)
            put(
                "description",
                "核对 Minecraft 指令的语法（网易版/基岩版规则）。把要检查的指令逐行传入，一行一条，" +
                    "会返回每条指令的语法要点与发现的问题（Java 专属写法、缺目标选择器、JSON 结构错误、" +
                    "方块/物品 ID 非法或基岩版不存在等）。输出指令给用户之前先调它自查一遍。" +
                    "注意：这是静态检查，不保证逻辑正确。"
            )
            put("parameters", JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("commands", JSONObject().apply {
                        put("type", "string")
                        put("description", "要核对的指令原文，一行一条，只写指令本身，不要带序号、不要带解释文字。")
                    })
                })
                put("required", JSONArray().put("commands"))
            })
        })
    }

    /**
     * 读 `message.content`。
     *
     * ⛔ **绝不能用 `optString`** —— `org.json` 的 `optString` 对 `JSONObject.NULL` 走的是
     * `String.valueOf(NULL)`, 结果是**字符串 "null"**, 不是空串。
     * 于是 `optString("content").ifBlank { 兜底 }` 这种写法在 content 真的为 null 时
     * 既不会走兜底、还会把 "null" 当成正文显示给用户。
     *
     * ★ 这在工具调用场景下是**常态而非边角**: 带 `tool_calls` 的 assistant 消息本来就要求
     * `content: null` —— 每轮中间消息都会命中这条路径。
     */
    private fun contentOf(msg: JSONObject): String = jsonStr(msg, "content")

    /**
     * 把一段文字追加到**最后一条 user 消息**上 —— 工具调用被上游拒绝时的降级路径用。
     *
     * ⚠️ `optJSONObject` 返回的是数组里那个对象的**引用**, 所以就地 `put` 就能改到。
     */
    private fun appendToLastUser(msgs: JSONArray, extra: String) {
        for (i in msgs.length() - 1 downTo 0) {
            val o = msgs.optJSONObject(i) ?: continue
            if (jsonStr(o, "role") == "user") {
                // content 走 jsonStr: 万一那条 user 消息的 content 是 JSON null,
                // optString 会给出字符串 "null", 拼完就是 "null<搜索结果>" —— 直接污染上下文。
                o.put("content", jsonStr(o, "content") + extra)
                return
            }
        }
    }

    /**
     * 直连对话。**非流式**。
     *
     * @param history 完整对话历史 (含本轮刚发出的用户消息); 带 err 标记的本地错误气泡会被剔除,
     *                它们不是真实的一轮, 塞给上游只会污染上下文
     * @param think   思考强度。AUTO 时一个思考参数都不发
     * @param searchContext 预搜到的资料 (见 [AiSearch.buildContext]), 拼进 system prompt; 空串 = 没有
     * @param searchTool ★ **让 AI 自己决定搜什么** (2026-10-01)。开了之后不再由客户端"拿用户原话去搜",
     *                而是把 [SEARCH_TOOL] 交给模型, 由它自己产出关键词、必要时连搜几轮。
     *
     *                为什么非改不可: 用户问「重点介绍 Java 版」这种**追问**, 原话里根本没有可搜的关键词 ——
     *                预搜索模式只能拿"重点介绍Java版"去搜, 召回一堆无关页面。
     *                工具调用模式下模型会自己合成「我的世界 Java 版 特性」这种真正能搜到东西的词。
     *
     *                不支持 `tools` 的上游 (400/422) 会自动**降级回预搜索**, 并在 [ChatResult.note] 里说明。
     * @param cmdExpert ★ **「指令专家」模式** (2026-10-01)。开了之后额外挂两个工具:
     *                [WIKI_TOOL] 查 MC 百科 + [CHECK_TOOL] 核对指令语法, 并在 system prompt 里
     *                告诉模型什么时候该用它们。
     *
     *                为什么单列一个开关而不是永远开: 两个工具都要走 `tools` 字段,
     *                而**部分网关不支持工具调用** —— 不支持时整轮请求会被 400 打回、
     *                再摘掉参数重试, 用户白等一个来回。留个开关, 遇到这种上游可以关掉。
     *                默认值由调用方给 (UI 上默认开)。
     * @param syntaxCheck ★ **核语法细粒度开关** (2026-10-05)。`cmdExpert=true` 时才有意义:
     *                - true  → 挂 [CHECK_TOOL] + 客户端复查
     *                - false → 只挂 [WIKI_TOOL] (纯查资料), 且跳过客户端复查
     *                用户要一个能单独切的"语法核对"开关, 见 `Session.aiSyntaxCheckOn`。
     * @param recheck   ★ **客户端复查开关** (2026-10-05)。见 `Session.aiRecheckOn`。
     *                与 [syntaxCheck] 独立 —— 但 [syntaxCheck]=false 时**强制不复查**
     *                (没开语法核对却还在复查, 语义上说不通, 且会白跑一轮)。
     * @param maxToolRounds 工具调用轮数上限 —— 用户在设置里调 (见 [AI_TOOL_ROUNDS_DEFAULT])。
     *                轮次用尽时会返回一句提示, 让用户知道**这个值可以调**。
     * @param onStreamStart ★ **流式输出** (2026-10-02)。**每一轮请求开始前**回调一次, 调用方
     *                据此清空上一轮的残留增量。只在传了 [onDelta] 时有意义。
     * @param onDelta ★ **流式增量回调**。传了它就带 `stream: true` 发请求, 正文**边收边吐**。
     *
     *                ⚠️ **回调跑在 IO 线程**(整个 [chat] 都在 `withContext(Dispatchers.IO)` 里),
     *                   回调里要改 Compose 状态必须自己切主线程。
     *                ⚠️ 传了它**不等于**上游一定支持 —— 有的网关直接 400, 有的忽略 `stream`
     *                   字段照旧返回整段 JSON。两条路都兜住了: 前者摘掉参数重试(见 [sendOnce]),
     *                   后者按 `Content-Type` 判断, 走原来的非流式解析(见 [readStreamMessage])。
     *                ⚠️ 中间那些"要调工具"的轮次**也会吐正文**(通常为空, 但不保证)。所以每轮
     *                   开始都要 [onStreamStart] 清一次, 否则半截内容会和最终答案粘在一起。
     * @param onEvent ★ **过程事件** (2026-10-02)。每走一步回调一次, 内容是一句**给用户看**的话
     *                (如「查阅 MC 百科 · 命令 execute」)。用户原话是"在思考过程和调用工具的过程中
     *                应该让用户知道 AI 在动, 展示过程" —— 光转圈是不够的。
     *
     *                ⚠️ 同 [onDelta], 回调**跑在 IO 线程**, 调用方要自己切主线程。
     *                ⚠️ 事件是**给人看的短句**, 不要塞路径、URL、token 数这类东西。
     * @param onToolEvent ★ **结构化工具事件** (2026-10-05)。与 [onEvent] 并存但**更细**:
     *                [onEvent] 只有一句话, 这个是 [AiToolEvent] —— 带状态/耗时/并发分组,
     *                支撑「全程实时时间线」。
     *
     *                每条工具调用会回调**两次**: 开始跑(RUNNING) 与 跑完(OK/FAILED),
     *                两次用同一个 `group`。调用方按 `group`+`kind` 覆盖前一条即可。
     *
     *                ⚠️ 同样**跑在 IO 线程**。UI 侧见 `AiTaskRunner` 的 `upsertEvent`。
     *                ⚠️ 不传它就退化成只有 [onEvent] 的旧行为, 不会崩。
     * @param onThinkDelta ★ **思考增量回调** (2026-10-05)。与 [onDelta] 对称, 但吐的是
     *               `reasoning_content` / `reasoning` 的**推理正文增量**。
     *
     *               用户要的是"像 WorkBuddy / ima 一样**展示中间思考的内容**" —— 光有
     *               [onEvent] 的一句话状态不够, 得把模型真实在想的字**边想边流出来**。
     *               流式通道逐片回调; 非流式通道(网关忽略 `stream`)拿不到增量, 就在
     *               读完那一刻**整段回调一次**(见 [chat] 里 `rsn` 那两行)。
     *
     *               ⚠️ 每轮开始前 [onStreamStart] 会同时被调用方用来清空**正文与思考**两个缓冲,
     *                  否则上一轮的半截思考会和这一轮粘起来。
     *               ⚠️ 同样**跑在 IO 线程**, 调用方要自己切主线程。
     *               ⚠️ 上游不返回思考内容时**一次都不会回调** —— 别指望它一定有。
     * @param shouldStop ★ **协作式停止** (2026-10-05)。用户点了「停止生成」时, 调用方把
     *               这个回调翻成 `true`, 本函数会在**下一次检查点**抛
     *               [CancellationException] 退出 —— 为什么要这个:
     *
     *               本函数内部是**阻塞**的 OkHttp `execute()` 与逐行 `readLine()`,
     *               它们**不响应协程取消**。光靠 `job.cancel()` 要等当前那次 HTTP
     *               响应读完才生效 —— 用户点了停止却还看字往外蹦, 会以为按钮坏了。
     *               所以在读流循环里插一个检查点, 才能做到"点了立刻停"。
     *
     *               ⚠️ 不传它 = 只能靠 `job.cancel()`, 停止会有延迟(旧行为)。
     */
    suspend fun chat(
        history: List<UiMsg>,
        base: String,
        key: String,
        model: String,
        think: ThinkLevel = ThinkLevel.AUTO,
        searchContext: String = "",
        searchTool: Boolean = false,
        cmdExpert: Boolean = false,
        syntaxCheck: Boolean = true,
        recheck: Boolean = true,
        maxToolRounds: Int = FALLBACK_TOOL_ROUNDS,
        onStreamStart: (() -> Unit)? = null,
        onDelta: ((String) -> Unit)? = null,
        onEvent: ((String) -> Unit)? = null,
        onToolEvent: ((com.canglan.cyancodex.data.AiToolEvent) -> Unit)? = null,
        onThinkDelta: ((String) -> Unit)? = null,
        shouldStop: (() -> Boolean)? = null,
    ): ChatResult = withContext(Dispatchers.IO) {
        val b = normalizeAiBase(base)
        val m = model.trim().ifBlank { "gpt-4o-mini" }
        val url = b + "chat/completions"
        val style = thinkStyleOf(b)
        // ★ 夹一道范围: 设置存的是用户输入/滑杆值, 万一被改坏(或老版本写进一个离谱的数),
        //   这里兜住 —— 0 轮会让工具调用彻底失效, 100 轮会烧钱。
        val roundLimit = maxToolRounds.coerceIn(AI_TOOL_ROUNDS_MIN, AI_TOOL_ROUNDS_MAX)

        val real = history.filter { !it.err && it.role.isNotBlank() && it.content.isNotBlank() }
        val userText = real.lastOrNull { it.role == "user" }?.content.orEmpty()

        // 会随着工具调用**增长**的消息数组。payload() 每次深拷贝一份发给上游,
        // 所以这里可以放心就地追加 —— 上游看到的是快照, 不是同一个对象。
        val msgs = JSONArray().apply {
            put(
                JSONObject().put("role", "system")
                    .put("content", buildSystemPrompt(userText, searchContext, cmdExpert, syntaxCheck, searchTool))
            )
            real.forEach { put(JSONObject().put("role", it.role).put("content", it.content)) }
        }

        var note = ""
        /** 工具调用被上游拒了就不再带 —— 别每轮都白试一次 */
        var toolsOn = searchTool || cmdExpert
        /** 智能搜索被上游拒了要单独摘掉 —— 指令专家那两个工具是另一码事, 别一起陪葬 */
        var searchOn = searchTool
        /** 指令专家的两个工具, 同理 */
        var expertOn = cmdExpert
        /** 思考参数同理 */
        var thinkOn = think != ThinkLevel.AUTO
        /**
         * 流式是否还开着。上游用 400/422 拒掉 `stream` 之后就摘掉它 ——
         * 别每轮都白试一次, 也别让"不支持流式"这件事表现成"请求失败"。
         */
        var streamOn = onDelta != null || onThinkDelta != null
        val queries = ArrayList<String>()
        val wikiQueries = ArrayList<String>()
        var checkCount = 0
        val thinkAll = StringBuilder()

        /**
         * ★ **客户端复查** (2026-10-02) 的三个游标。
         *
         * 为什么要**额外**一轮请求: 用户报「这个审核不对啊，石头 ID 都不对」——
         * 模型自己调 `check_command` 说"通过", 交出来的却是错的。所以客户端拿到最终正文后
         * 自己再验一遍, 有问题就把问题**喂回模型**让它改。
         *
         * ⚠️ [recheckRounds] 上限是 **1** —— 只给一次机会。
         *   给两次的话模型可以一直"改错再改", 每一轮都是真实请求(官方通道还真实扣费),
         *   而且没有收敛保证。改一次还不通过就如实告诉用户, 别装。
         */
        var recheckRounds = 0
        var rechecked = false
        var recheckIssueCount = 0
        var recheckIssues: List<String> = emptyList()

        /** 本次要挂的工具数组。★ 空数组**不能**塞进 payload —— 有些网关见 `"tools":[]` 直接 400 */
        fun toolsNow(): JSONArray = JSONArray().apply {
            if (searchOn) put(SEARCH_TOOL)
            if (expertOn) {
                put(WIKI_TOOL)
                // ★ 2026-10-05: 核语法工具**只在语法核对开着**时才挂。
                //   用户要的就是"能单独切" —— 只查资料不自查时, 挂它会诱导模型每轮都去核一遍。
                if (syntaxCheck) put(CHECK_TOOL)
            }
        }

        /**
         * 组装请求体。
         *
         * @param thinkLevel **思考字段发什么**: 传 [think](= `Session.effectiveThink()`,
         *        AUTO 表示用户关了开关 → 显式下发"关闭"参数);
         *        传 `null` = 降级路径, 一个思考字段都不发。两者别混。
         */
        fun payload(thinkLevel: ThinkLevel?, withTools: Boolean, withStream: Boolean): String = JSONObject().apply {
            put("model", m)
            put("stream", withStream)
            put("messages", JSONArray(msgs.toString()))
            if (withTools) {
                val t = toolsNow()
                if (t.length() > 0) put("tools", t)
            }
            applyThink(this, style, thinkLevel)
        }.toString()

        /**
         * 发一次请求, 并把"上游不收的参数"逐项摘掉重试。
         *
         * ★ 顺序: 先摘思考, 再摘工具, **最后摘流式**。反过来会白摘 —— 思考参数被拒时若直接放弃工具,
         *   用户就白白失去"AI 自动搜索"这个能力; 流式放在最后是因为它只是**体验**层面的东西,
         *   前面那些是**功能**, 功能优先保。
         *
         * ★ 注意思考这一层现在**总会试一次**: 用户关了开关时我们发的是"显式关闭"参数
         *   (`reasoning_effort:"none"` 等, 见 [applyThink]), 那也可能被上游拒 —— 一样要降级。
         *   `thinkSent` 记的就是"这一轮有没有发过思考字段"。
         *
         * ⚠️ 必须是 `suspend`: 降级路径里要真搜一次 (`AiSearch.search`), 那是挂起函数。
         *   写成普通局部函数会报 "Suspension functions can only be called within coroutine body"。
         */
        suspend fun sendOnce(): okhttp3.Response {
            var wantTools = toolsOn
            var wantStream = streamOn
            var thinkSent = true
            var r = post(url, key, payload(think, wantTools, wantStream))
            if (thinkSent && (r.code == 400 || r.code == 422)) {
                r.close()   // 这一次的 body 不读了, 必须关掉否则连接泄漏
                thinkSent = false
                note = if (thinkOn) "该模型不支持所选思考设置，已自动忽略重试"
                else "该模型不支持关闭思考，已改回它的默认行为"
                r = post(url, key, payload(null, wantTools, wantStream))
            }
            if (wantTools && (r.code == 400 || r.code == 422)) {
                r.close()
                // ★ 分开处理: 「智能搜索」被拒要走**预搜索兜底**(用户开了搜索却什么也没搜到是 bug),
                //   而「指令专家」被拒只是少两个工具, 直接摘掉即可 —— 没必要也不该替用户去搜网页。
                val wasSearch = searchOn
                toolsOn = false
                searchOn = false
                expertOn = false
                wantTools = false
                if (wasSearch) {
                    // 降级 = 退回"预搜索": 拿用户原话搜一次, 结果并进最后一条 user 消息。
                    // 关键词质量不如模型自己合成的, 但总好过"开了搜索却什么也没搜"。
                    val fb = runCatching { AiSearch.buildContext(AiSearch.search(userText)) }.getOrDefault("")
                    if (fb.isNotBlank()) appendToLastUser(msgs, fb)
                    note = if (fb.isNotBlank()) "该模型不支持 AI 自动搜索，已改用关键词直接搜索"
                    else "该模型不支持 AI 自动搜索"
                } else {
                    note = "该模型不支持工具调用，已关闭指令工具"
                }
                r = post(url, key, payload(if (thinkSent) think else null, false, wantStream))
            }
            if (wantStream && (r.code == 400 || r.code == 422)) {
                r.close()
                streamOn = false
                wantStream = false
                // ⚠️ 用 ifBlank 而不是直接赋值: 上面两段可能已经写了 note, 别把它盖掉
                if (note.isBlank()) note = "该接口不支持流式输出，已改回整段返回"
                r = post(url, key, payload(if (thinkSent) think else null, wantTools, false))
            }
            return r
        }

        var round = 0
        while (round < roundLimit) {
            round++
            // ★ 用户点了「停止生成」→ 立刻退出 (2026-10-05)。检查点放在**每轮开头**:
            //   工具执行完、下一轮请求发出之前, 是唯一"没卡在阻塞 IO 上"的自然位置。
            if (shouldStop?.invoke() == true) throw CancellationException("已停止生成")
            // ★ 每一轮开始前清空调用方的流式缓冲: 上一轮若吐了半截正文又被判为"还要调工具",
            //   那半截内容已经作废 —— 不清掉会和最终答案粘成一段谁也看不懂的东西。
            //   ⚠️ 调用方在 [onStreamStart] 里**正文与思考两个缓冲都要清** (见 onThinkDelta 注释)。
            if (onDelta != null || onThinkDelta != null) onStreamStart?.invoke()

            val resp = sendOnce()
            if (!resp.isSuccessful) {
                val text = resp.body?.string().orEmpty()
                throw RuntimeException("AI 请求失败(${resp.code})：${extractErr(text)}")
            }
            val respBody = resp.body ?: throw RuntimeException("AI 响应解析失败：响应体为空")

            // ★ 用 **Content-Type** 判断是不是流, 而不是"我请求时带了 stream 所以它一定是流":
            //   有的网关把 stream 字段当耳旁风, 照旧回一整段 JSON。按实际形态解析才不会炸。
            val subtype = respBody.contentType()?.subtype.orEmpty()
            val msg = if (subtype.equals("event-stream", ignoreCase = true)) {
                readStreamMessage(respBody, onDelta, onThinkDelta, shouldStop)
            } else {
                val text = respBody.string()
                JSONObject(text).optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")
                    ?: throw RuntimeException("AI 响应解析失败：${text.take(200)}")
            }

            // 多轮思考要**拼起来** —— 只看最后一轮的话, 前面几轮的推理过程就丢了,
            // 用户看到"已思考"点开却是空的。
            val rsn = readReasoning(msg)
            if (rsn.isNotBlank()) {
                thinkAll.append(rsn)
                // ★ 非流式通道(网关忽略 `stream`, 或流式被 400/422 摘掉)拿不到增量,
                //   就在读到整段思考的这一刻**整段回调一次** —— 上层照样能显示推理正文,
                //   只是不会逐字流出来而已。流式通道下这里**不重复回调**: 增量已在
                //   [readStreamMessage] 里逐片吐过了, 再吐一遍会看到思考内容翻倍。
                if (subtype != "event-stream") onThinkDelta?.invoke(rsn)
            }

            val calls = msg.optJSONArray("tool_calls")
            if (calls == null || calls.length() == 0) {
                val raw = contentOf(msg).ifBlank {
                    // 有的模型把答案全塞进思考里, content 为 null; 此时拿思考当正文, 免得用户看到空白气泡
                    readReasoning(msg).ifBlank { "（模型没有返回内容）" }
                }

                // ---- ★ 最终自检: 客户端自己再验一遍 (2026-10-02) ----
                // 只在「指令专家」模式下做, 且只在**真的有 ```mc 代码块**时才做 ——
                // 闲聊、解释、报错这类回答没什么可查的, 白跑一趟还多发一轮请求。
                // ★ 2026-10-05: 还要 [syntaxCheck] 与 [recheck] 都开着 ——
                //   关掉语法核对却还在复查, 语义上说不通; 用户显式关复查就不该再跑。
                if (cmdExpert && syntaxCheck && recheck && CommandCheck.hasCommandBlock(raw)) {
                    onEvent?.invoke("复查方块 / 物品 ID…")
                    val t0 = SystemClock.uptimeMillis()
                    onToolEvent?.invoke(
                        AiToolEvent(
                            kind = AiToolEvent.KIND_RECHECK,
                            title = "复查方块 / 物品 ID",
                            state = AiToolEvent.STATE_RUNNING,
                            startAt = t0,
                        )
                    )
                    val issues = CommandCheck.audit(raw)
                    rechecked = true
                    recheckIssueCount = issues.size
                    recheckIssues = issues.take(6)
                    onToolEvent?.invoke(
                        AiToolEvent(
                            kind = AiToolEvent.KIND_RECHECK,
                            title = "复查方块 / 物品 ID",
                            detail = if (issues.isEmpty()) "通过，未发现问题"
                            else "发现 ${issues.size} 处疑似问题",
                            state = if (issues.isEmpty()) AiToolEvent.STATE_OK else AiToolEvent.STATE_FAILED,
                            startAt = t0,
                            endAt = SystemClock.uptimeMillis(),
                        )
                    )
                    if (issues.isNotEmpty() && recheckRounds < 1 && round < roundLimit) {
                        recheckRounds++
                        onEvent?.invoke("复查发现 ${issues.size} 处问题，正在让 AI 修正…")
                        // 把这一版放回历史再追加一条"修正要求" —— 模型下一轮看到的就是
                        // "你刚才输出了这个 + 它有什么问题", 比重新描述一遍问题准得多。
                        msgs.put(JSONObject().apply {
                            put("role", "assistant")
                            put("content", raw)
                        })
                        msgs.put(JSONObject().apply {
                            put("role", "user")
                            put("content", CommandCheck.fixPrompt(issues))
                        })
                        continue
                    }
                    if (issues.isEmpty()) {
                        onEvent?.invoke(if (recheckRounds > 0) "复查通过" else "复查通过 · 未发现 ID 问题")
                    } else {
                        // 改过一次还是有 / 或轮次已经用完 —— 如实说, 别装成"已检查通过"
                        onEvent?.invoke("复查发现 ${issues.size} 处疑似问题")
                    }
                }

                return@withContext ChatResult(
                    content = raw,
                    reasoning = thinkAll.toString(),
                    note = note,
                    searchQueries = queries,
                    wikiQueries = wikiQueries,
                    checkCount = checkCount,
                    rechecked = rechecked,
                    recheckIssueCount = recheckIssueCount,
                    recheckIssues = recheckIssues,
                )
            }

            // ---- 执行工具调用 ----
            // 把 assistant 那条消息放回历史。★ 必须**重建**而不是把上游返回的 message 原样塞回去:
            // 里面可能带 reasoning_content / refusal / annotations 等字段, 部分网关见到会直接 400。
            // content 为空时必须显式写 null —— 带 tool_calls 的 assistant 消息本来就该 content=null,
            // 写成空串有些实现会当成"空回复"处理。
            msgs.put(JSONObject().apply {
                put("role", "assistant")
                put("content", contentOf(msg).takeIf { it.isNotBlank() } ?: JSONObject.NULL)
                put("tool_calls", calls)
            })

            // ---- ★ 真并发执行本轮所有工具调用 (2026-10-05) ----
            // 改之前是 `for (i in 0 until calls.length())` **串行**: 3 个工具 = 3 次网络往返叠加。
            // 现在先解析出全部调用, 交给 [AiToolRunner.runAll] 用 async **并发**打出去,
            // 总耗时 ≈ 最慢的那一个。执行层的分发逻辑两条通道共用, 见该类的注释。
            //
            // ⚠️ 解析仍按 calls 顺序收集, 且 `outcomes` 由 awaitAll 保序 ——
            //    tool_call_id 配对依赖这个顺序, 乱了上游会 400。
            val plan = ArrayList<AiToolRunner.Call>(calls.length())
            for (i in 0 until calls.length()) {
                val tc = calls.optJSONObject(i) ?: continue
                // id 缺失也要编一个: 后面的 tool 消息靠它和这一条配对, 没有 id 上游会拒。
                // ⚠️ 走 jsonStr —— `"id": null` 用 optString 会得到字符串 "null", 那个 id
                //    跟 tool 消息里写的 "null" 虽然字面相同, 但上游按真实 id 校验, 直接 400。
                val id = jsonStr(tc, "id").ifBlank { "call_$round$i" }
                val fn = tc.optJSONObject("function")
                plan.add(
                    AiToolRunner.Call(
                        name = jsonStr(fn, "name"),
                        query = parseToolArg(jsonStr(fn, "arguments"), "query"),
                        commands = parseToolArg(jsonStr(fn, "arguments"), "commands"),
                        id = id,
                    )
                )
                // 账本(给 UI 看的那几行)在**发起时**就记, 不等结果 ——
                // 用户要的是"现在在查什么", 等结果回来再记就晚了。
                val n = plan.last().name
                if (n == TOOL_SEARCH && plan.last().query.isNotBlank()) queries.add(plan.last().query)
                if (n == TOOL_WIKI && plan.last().query.isNotBlank()) wikiQueries.add(plan.last().query)
                if (n == TOOL_CHECK) checkCount++
            }
            // ★ 两条回调同时喂:
            //   · onToolEvent → 结构化事件(时间线用, 带状态/耗时/并发分组)
            //   · onEvent     → 一句话文本(旧接口, 让"没接时间线 UI"的路径也不至于空着)
            //   ⚠️ onEvent 只在**开始**那一次发 —— 结束那次再发一遍会让旧的纯文本列表出现重复行。
            val outcomes = AiToolRunner.runAll(
                plan,
                wikiMulti = true,
                onEvent = { ev ->
                    onToolEvent?.invoke(ev)
                    if (ev.isRunning) onEvent?.invoke(ev.title)
                },
            )
            // 结果按顺序塞回 —— 顺序与 plan 一一对应, 所以 tool_call_id 配对一定正确
            outcomes.forEach { o ->
                msgs.put(JSONObject().apply {
                    put("role", "tool")
                    put("tool_call_id", o.call.id)
                    put("content", o.text)
                })
            }
        }

        // 轮次用尽还没收敛 (极少见) —— 把已有的思考与搜索记录交出去, 让 UI 能显示发生过什么。
        // ★ 文案必须**告诉用户这个值可以调** —— 用户看到"请把问题拆小一点"时,
        //   第一反应是"我的问题哪里大了", 而不是"我去设置里调一下"。这句是补上的。
        ChatResult(
            content = "（已连续调用工具 $roundLimit 轮仍未得出结论。\n" +
                "可以在「AI 设置 → 模型行为 → 工具调用轮数上限」把上限调高（当前 $roundLimit），" +
                "或者把问题拆小一点再问。）",
            reasoning = thinkAll.toString(),
            note = note,
            searchQueries = queries,
            wikiQueries = wikiQueries,
            checkCount = checkCount,
        )
    }

    private fun post(url: String, key: String, json: String) = Request.Builder()
        .url(url)
        .post(json.toRequestBody(JSON))
        .apply { if (key.isNotBlank()) header("Authorization", "Bearer ${cleanApiKey(key)}") }
        .build()
        .let { client.newCall(it).execute() }

    /**
     * 往请求体里注入"思考"参数 —— 三家写法完全不同, 见 [ThinkStyle]。
     *
     * ## ★ AUTO 不是"什么都不加", 而是"显式要求关闭" (2026-10-02 修)
     *
     * 用户报「为啥没开深度思考还是思考了，不开就直接不用思考」。
     *
     * 原来 AUTO 直接 `return`, 一个字段都不发 —— 对**普通对话模型**这没问题(它们默认不思考),
     * 但对**推理模型**(deepseek-reasoner / qwen3 / glm-z1 这类)等于什么都没说:
     * 它们**默认就思考**, 于是用户明明把开关关着, 回答上面还是挂着「已思考（用时 6 秒）」。
     *
     * 现在显式下发"关闭":
     *  - `EFFORT`    → `reasoning_effort: "none"`
     *  - `DASHSCOPE` → `enable_thinking: false`
     *  - `ZHIPU`     → `thinking: {"type":"disabled"}`
     *
     * ⚠️ 不认这些字段的网关会 400 —— 走 [sendOnce] 的降级链摘掉重试(见那里)。
     *    降级之后模型多半还是会思考, 但那是**上游能力问题**, 至少不再是我们没说。
     *
     * @param level `null` = 降级路径: 一个思考字段都不发(上游刚才就是因为它 400 的)。
     *              注意这和 [ThinkLevel.AUTO] **不是一回事**。
     */
    private fun applyThink(o: JSONObject, style: ThinkStyle, level: ThinkLevel?) {
        if (level == null) return
        if (level == ThinkLevel.AUTO) {
            when (style) {
                ThinkStyle.EFFORT -> o.put("reasoning_effort", "none")
                ThinkStyle.DASHSCOPE -> o.put("enable_thinking", false)
                ThinkStyle.ZHIPU -> o.put("thinking", JSONObject().put("type", "disabled"))
            }
            return
        }
        when (style) {
            ThinkStyle.EFFORT -> o.put("reasoning_effort", when (level) {
                ThinkLevel.LOW -> "low"
                ThinkLevel.MEDIUM -> "medium"
                else -> "high"
            })
            ThinkStyle.DASHSCOPE -> {
                o.put("enable_thinking", true)
                o.put("thinking_budget", when (level) {
                    ThinkLevel.LOW -> 1024
                    ThinkLevel.MEDIUM -> 4096
                    else -> 16384
                })
            }
            ThinkStyle.ZHIPU -> o.put("thinking", JSONObject().put("type", "enabled"))
        }
    }

    /**
     * 从响应里抠思考过程。各家字段名不统一, 见过的形态:
     *  - `reasoning_content` : DeepSeek / 本机实测的 intern-ai 网关
     *  - `reasoning`         : 部分网关 (纯字符串)
     *  - `reasoning_details` : OpenRouter 风格, `[{"type":"reasoning.text","text":"..."}]`
     */
    private fun readReasoning(msg: JSONObject): String {
        // ⛔ 三个字段一律走 jsonStr。
        //    ★ 2026-10-01 用户截图报的 bug 就在前两行: 多轮工具调用时, 第一轮返回了
        //      reasoning_content, 第二轮该字段为 `null` → optString 给出字符串 "null"
        //      → `isNotBlank()` 为真 → 被拼进 thinkAll → 思考过程末尾凭空多出一个 " null"。
        jsonStr(msg, "reasoning_content").takeIf { it.isNotBlank() }?.let { return it }
        jsonStr(msg, "reasoning").takeIf { it.isNotBlank() }?.let { return it }
        msg.optJSONArray("reasoning_details")?.let { arr ->
            val sb = StringBuilder()
            for (i in 0 until arr.length()) {
                val t = jsonStr(arr.optJSONObject(i), "text")
                if (t.isNotBlank()) sb.append(t)
            }
            if (sb.isNotBlank()) return sb.toString()
        }
        return ""
    }

    /**
     * 逐行读 SSE 流, **边读边把正文增量交给 [onDelta]**, 最后拼出一个与非流式
     * `choices[0].message` **完全等价**的对象。
     *
     * ★ 为什么要拼成 `message` 形态: 上层那一整套逻辑(取 `content`、取 `reasoning_content`、
     *   取 `tool_calls`、执行工具、多轮循环、判空兜底)**一行都不用改** ——
     *   流式与非流式的差异被完全关在这一个函数里。
     *
     * 上游的报文形态 (OpenAI 兼容 SSE, 每行 `data: ` 前缀, 空行分隔, `[DONE]` 收尾):
     * ```
     * data: {"choices":[{"delta":{"content":"你"},"finish_reason":null}]}
     * data: {"choices":[{"delta":{"reasoning_content":"…"}}]}
     * data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_x",
     *          "function":{"name":"web_search","arguments":""}}]}}]}
     * data: {"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"{\"qu"}}]}}]}
     * data: [DONE]
     * ```
     *
     * ⚠️ **`tool_calls` 在流式里是分片到达的**: `id` / `name` 只在首片出现, `arguments` 要按
     *   `index` **逐片字符串拼接**。当成完整 JSON 读会拿到半截参数(如 `{"query":"我的世`),
     *   解析失败后模型会以为工具没被调用, 于是原地重试 —— 表现为"工具时灵时不灵"。
     * ⚠️ 有些实现**不带 `index`**, 用数组下标兜底。
     * ⚠️ 上游也可能**把错误走 SSE 吐出来**(HTTP 200 + `{"error":{…}}`), 必须显式判。
     */
    private fun readStreamMessage(
        body: ResponseBody,
        onDelta: ((String) -> Unit)?,
        onThinkDelta: ((String) -> Unit)? = null,
        shouldStop: (() -> Boolean)? = null,
    ): JSONObject {
        val content = StringBuilder()
        val reasoning = StringBuilder()
        // index -> {id, type, function:{name, arguments}}
        val calls = LinkedHashMap<Int, JSONObject>()

        body.charStream().buffered().use { br ->
            while (true) {
                // ★ 协作式停止检查点 (2026-10-05): 逐行读是**阻塞**的, 不插这个检查点
                //   就只能等整段读完才停 —— 用户会以为"停止"按钮没反应。
                if (shouldStop?.invoke() == true) throw CancellationException("已停止生成")
                val line = br.readLine() ?: break
                // 空行 = 事件分隔; `event:` / `id:` / `: 注释` 这些字段我们都不需要
                if (line.isEmpty() || !line.startsWith("data:")) continue
                val data = line.substring(5).trim()
                if (data.isEmpty()) continue
                if (data == "[DONE]") break

                val obj = runCatching { JSONObject(data) }.getOrNull() ?: continue
                obj.optJSONObject("error")?.let {
                    throw RuntimeException(
                        "AI 请求失败：" + jsonStr(it, "message").ifBlank { "上游返回错误" }
                    )
                }
                val d = obj.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("delta") ?: continue

                // ---- 正文增量: 边收边吐 ----
                val c = jsonStr(d, "content")
                if (c.isNotEmpty()) {
                    content.append(c)
                    onDelta?.invoke(c)
                }
                // ---- 思考增量 (字段名同非流式, 见 [readReasoning]) ----
                // ★ 2026-10-05: 边收边吐 —— 与正文增量同理。上层据此把"正在思考"的
                //   推理正文**实时**画出来(像 WorkBuddy/ima 那样), 而不是等整轮结束才显示。
                val rc = jsonStr(d, "reasoning_content").ifBlank { jsonStr(d, "reasoning") }
                if (rc.isNotEmpty()) {
                    reasoning.append(rc)
                    onThinkDelta?.invoke(rc)
                }
                // ---- 工具调用分片 ----
                val arr = d.optJSONArray("tool_calls") ?: continue
                for (i in 0 until arr.length()) {
                    val tc = arr.optJSONObject(i) ?: continue
                    val idx = tc.optInt("index", i)
                    val slot = calls.getOrPut(idx) {
                        JSONObject().apply {
                            put("id", "")
                            put("type", "function")
                            put("function", JSONObject().apply {
                                put("name", "")
                                put("arguments", "")
                            })
                        }
                    }
                    jsonStr(tc, "id").takeIf { it.isNotBlank() }?.let { slot.put("id", it) }
                    val fn = tc.optJSONObject("function") ?: continue
                    val sf = slot.getJSONObject("function")
                    jsonStr(fn, "name").takeIf { it.isNotBlank() }?.let { sf.put("name", it) }
                    // ★ arguments 是**追加**不是覆盖 —— 这是流式工具调用最容易写错的一行
                    val a = jsonStr(fn, "arguments")
                    if (a.isNotEmpty()) sf.put("arguments", jsonStr(sf, "arguments") + a)
                }
            }
        }

        return JSONObject().apply {
            // content 为空时显式写 null, 与非流式形态一致(上层 [contentOf] 会得到空串)
            put("content", content.toString().ifBlank { null } ?: JSONObject.NULL)
            if (reasoning.isNotEmpty()) put("reasoning_content", reasoning.toString())
            if (calls.isNotEmpty()) {
                put("tool_calls", JSONArray().apply {
                    calls.toSortedMap().values.forEach { put(it) }
                })
            }
        }
    }

    // =================================================================================
    //  获取模型列表
    // =================================================================================

    /**
     * `GET {base}/models` —— 拉这家服务商的可用模型。
     *
     * 解析要兼容两种形态:
     *  ① OpenAI 标准 `{"data":[{"id":"..."}]}` —— 只有 id, 思考能力靠 [guessReasoning] 猜
     *  ② 富元数据 (OpenRouter 系 / 本机实测的 intern-ai 网关) —— 能读到上游**声明的**能力:
     *     `output_modalities[].supported_parameters.reasoning` = 支不支持思考,
     *     `input_modalities[].type == "image"` = 支不支持图片,
     *     `input_modalities[].supported_inputs.max_context_length.value` = 上下文长度
     *  ③ 少数实现返回裸数组或 `{"models":[...]}`
     */
    suspend fun fetchModels(base: String, key: String): List<AiModelInfo> = withContext(Dispatchers.IO) {
        val url = normalizeAiBase(base) + "models"
        val req = Request.Builder()
            .url(url)
            .apply { if (key.isNotBlank()) header("Authorization", "Bearer ${cleanApiKey(key)}") }
            .build()
        client.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw RuntimeException("获取模型失败(${resp.code})：${extractErr(text)}")
            parseModels(text)
        }
    }

    /** 见 [fetchModels] 的形态说明。解析不出来就抛, 由调用方提示用户手填模型名。 */
    private fun parseModels(text: String): List<AiModelInfo> {
        val trimmed = text.trim()
        val arr: JSONArray = when {
            trimmed.startsWith("[") -> JSONArray(trimmed)
            else -> {
                val root = JSONObject(trimmed)
                root.optJSONArray("data")
                    ?: root.optJSONArray("models")
                    ?: throw RuntimeException("返回里没有模型列表")
            }
        }
        val out = ArrayList<AiModelInfo>(arr.length())
        for (i in 0 until arr.length()) {
            // 条目可能是 "gpt-4o-mini" 这样的裸字符串, 也可能是对象
            // ⚠️ 走 jsonStr: 数组元素/字段显式为 null 时, optString 会给出字符串 "null",
            //    于是模型列表里会真的多出一个名叫 "null" 的模型。
            val id = jsonStr(arr, i).takeIf { it.isNotBlank() && !it.startsWith("{") }
                ?: jsonStr(arr.optJSONObject(i), "id").takeIf { it.isNotBlank() }
                ?: continue
            val o = arr.optJSONObject(i)
            if (o == null) {
                out.add(AiModelInfo(id = id, reasoning = guessReasoning(id), guessed = true))
                continue
            }
            var declared = false
            var vision = false
            var ctx = 0L

            // ---- 上游声明的能力 ----
            for (mk in listOf("output_modalities", "input_modalities")) {
                val mods = o.optJSONArray(mk) ?: continue
                for (j in 0 until mods.length()) {
                    val mod = mods.optJSONObject(j) ?: continue
                    if (mod.optJSONObject("supported_parameters")?.has("reasoning") == true) declared = true
                    if (jsonStr(mod, "type") == "image") vision = true
                    val v = mod.optJSONObject("supported_inputs")
                        ?.optJSONObject("max_context_length")?.optLong("value") ?: 0L
                    if (v > ctx) ctx = v
                }
            }
            // 有的网关把能力平铺在顶层
            if (o.optJSONObject("capabilities")?.optBoolean("reasoning") == true) declared = true
            if (o.has("reasoning") && o.opt("reasoning") !is JSONObject) declared = true
            if (o.optJSONObject("architecture")?.optJSONArray("input_modalities")
                    ?.let { a -> (0 until a.length()).any { jsonStr(a, it) == "image" } } == true
            ) vision = true

            out.add(
                AiModelInfo(
                    id = id,
                    reasoning = declared || guessReasoning(id),
                    vision = vision,
                    contextLen = ctx,
                    guessed = !declared,
                )
            )
        }
        if (out.isEmpty()) throw RuntimeException("模型列表是空的")
        return out.distinctBy { it.id }.sortedBy { it.id }
    }

    // =================================================================================
    //  连通性自检
    // =================================================================================

    /** 自检结果 */
    data class TestResult(val ok: Boolean, val message: String)

    /**
     * 连通性自检。
     *
     * 原版**没有任何校验手段** —— 地址 / Key / 模型填错, 只有发出第一条消息时才知道。
     * 这里拿用户填的参数直接打一次最小请求 (max_tokens = 1, 花费可忽略),
     * 把 401 / 404 / 429 的真实原因摊开给用户。
     */
    suspend fun testConnection(base: String, key: String, model: String): TestResult =
        withContext(Dispatchers.IO) {
            val b = normalizeAiBase(base)
            val m = model.trim()
            if (key.isBlank()) return@withContext TestResult(false, "还没填 API Key")
            if (m.isBlank()) return@withContext TestResult(false, "还没填模型名")
            try {
                val body = JSONObject().apply {
                    put("model", m)
                    put("stream", false)
                    put("max_tokens", 1)
                    put("messages", JSONArray().put(
                        JSONObject().put("role", "user").put("content", "hi")))
                }
                val req = Request.Builder()
                    .url(b + "chat/completions")
                    .post(body.toString().toRequestBody(JSON))
                    .header("Authorization", "Bearer ${cleanApiKey(key)}")
                    .build()
                client.newCall(req).execute().use { resp ->
                    val text = resp.body?.string().orEmpty()
                    if (resp.isSuccessful) TestResult(true, "连接正常，模型 $m 可用")
                    else TestResult(false, "失败(${resp.code})：${extractErr(text)}")
                }
            } catch (e: Exception) {
                TestResult(false, "网络错误：${e.message ?: e.javaClass.simpleName}")
            }
        }

    /**
     * 从各家五花八门的错误响应里抠出人能看的那句话。
     * 形态: `{"error":{"message":"..."}}` / `{"error":"..."}` / `{"message":"..."}` / 纯文本。
     */
    private fun extractErr(text: String): String {
        val fallback = text.take(200).ifBlank { "无响应内容" }
        return try {
            val j = JSONObject(text)
            when (val e = j.opt("error")) {
                // ⚠️ `optString("message", fallback)` 的兜底**拦不住**显式的 null:
                //    `{"error":{"message":null}}` 会返回字符串 "null", 用户看到"失败(400)：null"。
                is JSONObject -> jsonStr(e, "message").ifBlank { fallback }
                is String -> e
                null -> jsonStr(j, "message").ifBlank { fallback }
                else -> fallback
            }
        } catch (e: Exception) {
            fallback
        }
    }

    // =================================================================================
    //  思考能力实测
    // =================================================================================

    /**
     * 「支不支持思考」的实测结论。
     *
     * 为什么不靠模型名猜: 同一个模型名在不同网关下能力可能不同 (自建代理阉割参数是常事),
     * 而 `/models` 的元数据也不是每家都给。**直接发一次带思考参数的请求看上游反应**最准。
     */
    enum class ThinkSupport {
        /** 实测通过: 参数被接受, 且响应里真的出现了思考内容 */
        YES,
        /** 上游明确拒绝思考参数 (400/422) */
        NO,
        /** 上游收下了参数, 但没产出思考内容 —— 多半是被静默忽略了 */
        MAYBE,
        /** 探测本身没跑成 (网络/鉴权/模型名不对), 得不出结论 */
        FAIL,
    }

    data class ThinkProbe(val support: ThinkSupport, val note: String)

    /** [probeThinking] 单次请求的原始结果 */
    private data class RawResp(val ok: Boolean, val code: Int, val err: String, val reasoning: String)

    /**
     * 实测「这个模型支不支持思考」。
     *
     * 最多 2 次请求, 常见情况 1 次就够:
     *  1. 发一个**带**思考参数的最小请求, 题目挑一道不动脑答不出来的:
     *     - 400/422          → [ThinkSupport.NO]   上游明确拒绝
     *     - 2xx 且有思考内容  → [ThinkSupport.YES]  实锤支持
     *     - 2xx 但无思考内容  → 下不了结论, 进第 2 步
     *  2. 再发一个**不带**参数的**同一道题**:
     *     - 有思考内容 → [ThinkSupport.YES] 模型本身就思考, 跟参数无关
     *     - 没思考内容 → [ThinkSupport.NO]  这个模型压根不产思考
     *
     * ★ 第 2 步不能省 —— 光看第 1 步无法区分「模型不思考」和「参数被静默忽略」,
     *   而那两种情况对用户的含义完全不同。
     */
    suspend fun probeThinking(
        base: String,
        key: String,
        model: String,
        level: ThinkLevel = ThinkLevel.MEDIUM,
    ): ThinkProbe = withContext(Dispatchers.IO) {
        val b = normalizeAiBase(base)
        val m = model.trim()
        if (key.isBlank()) return@withContext ThinkProbe(ThinkSupport.FAIL, "还没填 API Key")
        if (m.isBlank()) return@withContext ThinkProbe(ThinkSupport.FAIL, "还没填模型名")

        val style = thinkStyleOf(b)
        val ask = "笼子里有鸡和兔共 35 个头、94 只脚, 鸡和兔各几只? 只回一行最终答案。"

        fun once(withThink: Boolean): RawResp = try {
            val body = JSONObject().apply {
                put("model", m)
                put("stream", false)
                put("max_tokens", 1024)   // 得给推理模型留够产出思考内容的额度, 否则误判成"不支持"
                put("messages", JSONArray().put(
                    JSONObject().put("role", "user").put("content", ask)))
                if (withThink) applyThink(this, style, level)
            }
            val req = Request.Builder()
                .url(b + "chat/completions")
                .post(body.toString().toRequestBody(JSON))
                .header("Authorization", "Bearer ${cleanApiKey(key)}")
                .build()
            client.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    RawResp(false, resp.code, extractErr(text), "")
                } else {
                    val msg = JSONObject(text).optJSONArray("choices")
                        ?.optJSONObject(0)?.optJSONObject("message")
                    RawResp(true, resp.code, "", msg?.let { readReasoning(it) }.orEmpty())
                }
            }
        } catch (e: Exception) {
            RawResp(false, 0, "网络错误：${e.message ?: e.javaClass.simpleName}", "")
        }

        val withParam = once(true)
        if (!withParam.ok) {
            return@withContext if (withParam.code == 400 || withParam.code == 422)
                ThinkProbe(ThinkSupport.NO, "实测：上游拒绝思考参数(${withParam.code})，该模型不支持")
            else
                ThinkProbe(ThinkSupport.FAIL, "探测没跑成：${withParam.err}")
        }
        if (withParam.reasoning.isNotBlank()) {
            return@withContext ThinkProbe(
                ThinkSupport.YES,
                "实测：支持思考（返回了 ${withParam.reasoning.length} 字思考内容）",
            )
        }

        val plain = once(false)
        return@withContext when {
            !plain.ok -> ThinkProbe(
                ThinkSupport.MAYBE, "实测：上游收下了思考参数但没产出思考内容，可能被静默忽略")
            plain.reasoning.isNotBlank() -> ThinkProbe(
                ThinkSupport.YES, "实测：支持思考（模型本身就输出思考内容）")
            else -> ThinkProbe(ThinkSupport.NO, "实测：该模型不产出思考内容")
        }
    }

    /** 检查 API 是否已配置 */
    fun isConfigured(): Boolean = Session.aiApiKey.isNotBlank()

    private val JSON = "application/json; charset=utf-8".toMediaType()
}
