package com.canglan.cyancodex.ai

/**
 * 自定义 AI 提供商的预设表 + 地址规范化。
 *
 * 为什么要这张表:
 * 原版只给一个裸的「API 地址」输入框, 用户得自己知道 DeepSeek 是
 * `https://api.deepseek.com/v1/`、通义是 `.../compatible-mode/v1/` —— 填错一个字符就是 404,
 * 而且报错只会在发第一条消息时以「AI 请求失败(404)」的形式冒出来, 用户根本不知道错在哪。
 * 预设把「地址 + 常用模型 + Key 申请入口」打包成一条, 选中即填好, 用户只需要贴 Key。
 */
data class AiProvider(
    val id: String,
    val name: String,
    /** 已规范化 (以 / 结尾) */
    val baseUrl: String,
    val models: List<String>,
    /** Key 申请入口, 点一下直接开浏览器 */
    val consoleUrl: String = "",
)

/** 「自定义」预设的 id —— 用户自己填地址时用它 */
const val CUSTOM_PROVIDER_ID = "custom"

val AI_PROVIDERS: List<AiProvider> = listOf(
    AiProvider(
        id = "deepseek", name = "DeepSeek",
        baseUrl = "https://api.deepseek.com/v1/",
        models = listOf("deepseek-chat", "deepseek-reasoner"),
        consoleUrl = "https://platform.deepseek.com/api_keys",
    ),
    AiProvider(
        id = "openai", name = "OpenAI",
        baseUrl = "https://api.openai.com/v1/",
        models = listOf("gpt-4o-mini", "gpt-4o", "gpt-4.1-mini"),
        consoleUrl = "https://platform.openai.com/api-keys",
    ),
    AiProvider(
        id = "dashscope", name = "通义千问",
        baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1/",
        models = listOf("qwen-plus", "qwen-turbo", "qwen-max"),
        consoleUrl = "https://bailian.console.aliyun.com/",
    ),
    AiProvider(
        id = "moonshot", name = "Kimi",
        baseUrl = "https://api.moonshot.cn/v1/",
        models = listOf("moonshot-v1-8k", "moonshot-v1-32k", "moonshot-v1-128k"),
        consoleUrl = "https://platform.moonshot.cn/console/api-keys",
    ),
    AiProvider(
        id = "zhipu", name = "智谱 GLM",
        baseUrl = "https://open.bigmodel.cn/api/paas/v4/",
        models = listOf("glm-4-flash", "glm-4-air", "glm-4-plus"),
        consoleUrl = "https://open.bigmodel.cn/usercenter/apikeys",
    ),
    AiProvider(
        id = "siliconflow", name = "硅基流动",
        baseUrl = "https://api.siliconflow.cn/v1/",
        models = listOf("deepseek-ai/DeepSeek-V3", "Qwen/Qwen2.5-72B-Instruct"),
        consoleUrl = "https://cloud.siliconflow.cn/account/ak",
    ),
    AiProvider(
        id = "openrouter", name = "OpenRouter",
        baseUrl = "https://openrouter.ai/api/v1/",
        models = listOf("openai/gpt-4o-mini", "anthropic/claude-3.5-sonnet"),
        consoleUrl = "https://openrouter.ai/keys",
    ),
)

/**
 * 把用户填的地址规范成 `<base>/` 形式。
 *
 * 用户实际会填出这些形态, 全都得能吃下 —— 原版 setter 只补尾斜杠, 前三种都会拼出 404 的 URL:
 * ```
 *   api.deepseek.com                              → https://api.deepseek.com/v1/
 *   https://api.deepseek.com                      → https://api.deepseek.com/v1/
 *   https://api.deepseek.com/v1                   → https://api.deepseek.com/v1/
 *   https://api.deepseek.com/v1/                  → https://api.deepseek.com/v1/
 *   https://api.deepseek.com/v1/chat/completions  → https://api.deepseek.com/v1/
 *   https://dashscope.aliyuncs.com/compatible-mode/v1   → 原样 (路径已存在, 不补 /v1)
 * ```
 * 规则: ① 没协议就补 https:// ② 粘了完整端点就砍掉 ③ 只有域名(无路径)才补 /v1 ④ 末尾补 /
 */
fun normalizeAiBase(raw: String): String {
    var s = raw.trim()
    if (s.isEmpty()) return "https://api.openai.com/v1/"
    if (!s.startsWith("http://") && !s.startsWith("https://")) s = "https://$s"
    s = s.removeSuffix("/chat/completions").removeSuffix("/completions")
    s = s.trimEnd('/')
    // 域名后面完全没有路径 → 补 /v1; 已经有路径(通义的 /compatible-mode、智谱的 /api/paas/v4)则不动
    val afterScheme = s.substringAfter("://")
    if (!afterScheme.contains('/')) s += "/v1"
    return "$s/"
}

/**
 * 把用户填的 Key 洗成**服务端真正想要的那串字符**。
 *
 * 这不是洁癖 —— 每一类脏东西都对应过一次真实的 401:
 *  - 首尾空白: 从聊天窗口复制时最常见的尾巴。
 *  - **中间**的空白: 有些客户端复制长 token 会硬折行, 换行符就这么进来了。
 *    `trim()` 只削两头, 中间那个换行会一路发到服务端, 回一句
 *    `Invalid or expired API key`, 用户盯着自己明明抄对的 Key 一头雾水。
 *  - 零宽字符 / BOM (U+200B~U+200D, U+FEFF): 网页和 Word 里复制时混进来的, **肉眼不可见**。
 *  - 误带 `Bearer ` 前缀: 从文档的 header 示例里整行抄下来时会带上。
 *
 * Key 是 token, 合法字符里不含任何空白, 所以"删掉全部空白"是安全的。
 */
fun cleanApiKey(raw: String): String {
    var s = raw
    s = s.removePrefix("Bearer ").removePrefix("bearer ").removePrefix("BEARER ")
    return s.filterNot { c ->
        c.isWhitespace() || c.isISOControl() ||
            c.code == 0x200B || c.code == 0x200C || c.code == 0x200D || c.code == 0xFEFF
    }
}

/** 按地址反查属于哪个预设; 匹配不上返回 [CUSTOM_PROVIDER_ID] */
fun matchAiProvider(baseUrl: String): String {
    val b = normalizeAiBase(baseUrl)
    return AI_PROVIDERS.firstOrNull { it.baseUrl == b }?.id ?: CUSTOM_PROVIDER_ID
}

/** 某个预设的推荐模型 (选中预设后填进模型框的默认值) */
fun defaultModelOf(providerId: String): String =
    AI_PROVIDERS.firstOrNull { it.id == providerId }?.models?.firstOrNull() ?: ""

// =====================================================================================
//  模型清单 (GET /models) 与「思考」控制
// =====================================================================================

/**
 * 从 `GET {base}/models` 解析出的单个模型。
 *
 * 各家返回的信息量差别极大, 所以这里**全部字段都有默认值**, 缺什么就当不支持:
 *  - OpenAI 标准: `{"data":[{"id":"gpt-4o-mini"}]}` —— 只有 id
 *  - 富元数据网关 (实测 intern-ai / OpenRouter 系): 额外带
 *    `output_modalities[].supported_parameters.reasoning` / `input_modalities[].type=image` /
 *    `supported_inputs.max_context_length.value`
 * 拿不到元数据时, [reasoning] 由 [guessReasoning] 按模型名兜底。
 */
data class AiModelInfo(
    val id: String,
    /** 上游声明支持 `reasoning` 参数 —— 「检测支不支持思考」的第一手依据 */
    val reasoning: Boolean = false,
    /** 支持图片输入 */
    val vision: Boolean = false,
    /** 上下文长度 (token); 0 = 未知 */
    val contextLen: Long = 0L,
    /** 该条目是名字猜的, 不是上游声明的 */
    val guessed: Boolean = false,
    /**
     * [reasoning] 是**实测**出来的 —— 真的发了一次带思考参数的请求, 看上游是拒绝、
     * 接受并产出思考内容、还是接受但静默忽略 (见 [AiClient.probeThinking])。
     *
     * 实测 > 上游声明 > 按名猜, 三者优先级从左到右递减。
     */
    val probed: Boolean = false,
)

/**
 * 思考强度 —— **只在输入框那个「深度思考」开关打开时才发**。
 *
 * 「自动」= 一个思考参数都不发, 完全跟随服务端默认 —— 开关关闭时就用它。
 * 没有单独的"关闭"档: 强推理模型 (deepseek-reasoner / o1 系) 本来就关不掉,
 * 给一个点了没反应的开关只会让用户困惑, 不如老实不发参数。
 */
enum class ThinkLevel(val id: String, val label: String) {
    AUTO("auto", "自动"),
    LOW("low", "低"),
    MEDIUM("medium", "中"),
    HIGH("high", "高");

    companion object {
        fun of(id: String?): ThinkLevel = values().firstOrNull { it.id == id } ?: AUTO
    }
}

/**
 * 可在配置面板里选的强度档位 (不含 AUTO —— AUTO 是"开关关着"的隐含状态, 不该出现在强度选项里)
 */
val THINK_LEVELS: List<ThinkLevel> = listOf(ThinkLevel.LOW, ThinkLevel.MEDIUM, ThinkLevel.HIGH)

/**
 * 上游「思考」参数的风格 —— 三家写法完全不同, 不能一套打天下:
 *  - [EFFORT]    OpenAI / DeepSeek / OpenRouter / 大多数网关: `reasoning_effort: low|medium|high`
 *  - [DASHSCOPE] 阿里百炼: `enable_thinking: bool` + `thinking_budget: int`
 *  - [ZHIPU]     智谱 GLM: `thinking: {"type": "enabled"|"disabled"}`
 */
enum class ThinkStyle { EFFORT, DASHSCOPE, ZHIPU }

fun thinkStyleOf(baseUrl: String): ThinkStyle = when (matchAiProvider(baseUrl)) {
    "dashscope" -> ThinkStyle.DASHSCOPE
    "zhipu" -> ThinkStyle.ZHIPU
    else -> ThinkStyle.EFFORT
}

/**
 * 拿不到 `/models` 元数据时的**最后兜底**: 按模型名猜是不是"思考型"。
 *
 * ⛔ 这只是猜测, 优先级最低 —— 有实测 ([AiClient.probeThinking]) 用实测,
 * 有上游声明用声明, 两者都没有才轮到这里。猜错的代价只是多发一个参数,
 * 而 [AiClient] 有 400 自动降级兜底, 不会把消息发挂。
 */
fun guessReasoning(modelId: String): Boolean {
    val m = modelId.lowercase()
    return listOf(
        "reasoner", "-r1", "r1-", "thinking", "-think", "qwq",
        "o1", "o3", "o4", "glm-4.5", "glm-5", "kimi-k2", "magistral",
    ).any { m.contains(it) }
}

/**
 * 模型清单的落盘编解码。
 *
 * ⛔ **不能用 Gson** —— [AiModelInfo] 在 `ai` 包, 而 R8 只对 `data` 包整包 keep 字段名
 * (见 app/proguard-rules.pro 的说明), 走 Gson 反射会被混淆成 `a`/`b`/`c` 读不回来。
 * 这里手工编成 `id|reasoning|vision|ctx|probed` 每行一条 —— 模型 id 里不会出现 `|` 和换行,
 * 且**不依赖任何字段名**, 混淆前后行为完全一致。
 * 第 5 段 (probed) 是后加的: 读的时候用 `getOrNull`, 所以**老数据 (4 段) 照样能读**。
 */
fun encodeModels(list: List<AiModelInfo>): String =
    list.joinToString("\n") {
        "${it.id}|${if (it.reasoning) 1 else 0}|${if (it.vision) 1 else 0}|${it.contextLen}" +
            "|${if (it.probed) 1 else 0}"
    }

fun decodeModels(s: String): List<AiModelInfo> {
    if (s.isBlank()) return emptyList()
    return s.split('\n').mapNotNull { line ->
        val p = line.split('|')
        if (p.size < 4 || p[0].isBlank()) return@mapNotNull null
        AiModelInfo(
            id = p[0],
            reasoning = p[1] == "1",
            vision = p[2] == "1",
            contextLen = p[3].toLongOrNull() ?: 0L,
            guessed = false,
            probed = p.getOrNull(4) == "1",
        )
    }
}
