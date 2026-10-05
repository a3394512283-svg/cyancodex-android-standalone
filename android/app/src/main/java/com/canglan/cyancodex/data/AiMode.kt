package com.canglan.cyancodex.data

/**
 * **会话模式** —— 新建对话时选定, 决定这条会话挂哪些工具 (2026-10-05)。
 *
 * ## 用户原话
 *
 * 「那个 ai 横条(深度思考那一条)不要换行，目前会把指令核对放到第二行，但是我的意思是
 *  类似 WorkBuddy 一样有个**编写代码和检查语法**的选项**在新建对话的时候选择**」
 *
 * 也就是: 「指令专家 / 语法核对」这类**改变 AI 能力边界**的开关, 不该挤在输入框上方的
 * 胶囊横条里 —— 那里应该是"随手可切的会话级小开关"(深度思考 / 智能搜索)。
 * 能力边界属于**这条会话是什么**，天然该在**建会话时**定, 和 WorkBuddy 新建会话选
 * "写代码 / 查资料 / 常规"是同一个交互。
 *
 * ## 三个模式
 *
 * | 模式 | 挂的工具 | 适用 |
 * |---|---|---|
 * | [CHAT] 通用对话 | 联网搜索(若开) | 纯问答/闲聊/查资料 |
 * | [CMD] 指令编写 | + MC 百科 + 语法核对 + 客户端复查 | 写 MC 指令(默认) |
 * | [LINT] 语法核对 | + 语法核对 + 客户端复查, **不**挂百科 | 只核语法/查 ID 错 |
 *
 * ## ⚠️ 为什么要落盘在 [AiConv.mode] 而不是全局 Session
 *
 * 模式是**会话的属性**, 不是 App 的属性。全局存的话, 用户在"纯聊天"会话里开过
 * 语法核对, 切回写指令的会话时状态就乱了 —— 而模式本来就决定了那条会话里 AI 的行为边界,
 * 混用会让"AI 为什么这次没查百科"变得不可解释。
 *
 * ## ⚠️ 与旧开关的关系 (向后兼容)
 *
 * 上游 2.0.0 有全局 `Session.aiCmdExpertOn` / `aiSyntaxCheckOn`。本模式是它们上层的**收口**:
 * 建会话时按模式**一次性**写进那条会话, 之后不再读全局那两个值 ——
 * 老会话([AiConv.mode] 为空)按 [DEFAULT] 处理, 行为与升级前一致。
 */
object AiMode {

    /** 通用对话 —— 不挂 MC 百科, 不核语法。最轻。 */
    const val CHAT = "chat"

    /** 指令编写 —— 查 MC 百科 + 核语法 + 客户端复查。防"写出来的指令不能用"。 */
    const val CMD = "cmd"

    /** 语法核对 —— 只核语法与方块/物品 ID, 不查百科。用来**检查已有指令**。 */
    const val LINT = "lint"

    /**
     * 默认模式。
     *
     * ★ 选 [CMD] 而不是 [CHAT]: 这个 App 的主场景就是写 MC 指令(YAML 里叫 CyanCodex
     *   "一站式我的世界指令创作"), 用户点进 AI 大概率就是要写指令。
     *   新建会话直接给"能用"的那个模式, 而不是让他每次手动切。
     */
    const val DEFAULT = CMD

    /** 全部模式, 按展示顺序 */
    val ALL = listOf(CHAT, CMD, LINT)

    /** 规范化: 空串/未知值一律落到 [DEFAULT] (老会话兼容) */
    fun of(v: String?): String = if (v != null && v in ALL) v else DEFAULT

    /** 模式中文名 (给 UI / 对话标题前缀用) */
    fun label(mode: String): String = when (of(mode)) {
        CHAT -> "通用对话"
        LINT -> "语法核对"
        else -> "指令编写"
    }

    /** 一句话说明 (新建会话面板里那行小字) */
    fun desc(mode: String): String = when (of(mode)) {
        CHAT -> "只聊天、查资料，不查百科也不核语法"
        LINT -> "检查已有指令的语法与方块/物品 ID，不查百科"
        else -> "查 MC 百科 + 写完自动核对语法与方块/物品 ID"
    }

    // ---- 模式 → 工具闸 ----

    /**
     * 该模式要不要挂「MC 百科 + 指令专家」工具。
     *
     * [LINT] **不**挂百科: 它的职责是"核已有指令", 百科查资料会让它跑去改写指令,
     * 偏离"只检查"的语义。
     */
    fun cmdExpert(mode: String): Boolean = of(mode) != CHAT && of(mode) != LINT

    /**
     * 该模式要不要跑「语法核对」(挂 check_command 工具 + 客户端复查)。
     *
     * [CMD] 与 [LINT] 都要; [CHAT] 不要。
     */
    fun syntaxCheck(mode: String): Boolean = of(mode) != CHAT
}
