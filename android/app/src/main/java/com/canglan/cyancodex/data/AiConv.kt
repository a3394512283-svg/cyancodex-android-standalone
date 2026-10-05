package com.canglan.cyancodex.data

/**
 * 一条本地 AI 会话 (多对话功能)。
 *
 * 原版只有一个全局 `Session.aiMessages`, 顶栏「新对话」= 直接清空 —— 上一轮对话永久丢失。
 * 现在改成会话列表: 每条会话独立存自己的消息, 可新建 / 切换 / 重命名 / 删除。
 *
 * ⚠️ 必须放在 data 包: Gson 按字段名反射序列化, 而 R8 的 keep 规则只保住 data 包的字段名。
 */
data class AiConv(
    /** 时间戳 + 随机后缀, 保证唯一 */
    val id: String = "",
    /** 标题: 自动取第一条用户消息的前 20 字, 用户可改 */
    val title: String = "",
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
    val messages: List<UiMsg> = emptyList(),
    /**
     * ★ **会话模式** (2026-10-05) —— 新建对话时选定, 之后这条会话一直用它。
     *
     * 用户原话:「类似 WorkBuddy 一样有个编写代码和检查语法的选项在**新建对话的时候**选择」。
     * 模式是**按会话**存而不是全局 —— 同一份 AI 能力, 不同会话可以干不同的事
     * (一条纯聊天、一条写指令、一条专门核语法), 切回去时模式跟着会话走。
     *
     * 取值见 [AiMode]; 空串 = 老会话(升级前建的) → 按 [AiMode.DEFAULT] 处理。
     *
     * ⚠️ 必须放在 data 包: 与 [AiConv] 同族, R8 只保住 data 包的字段名。
     */
    val mode: String = "",
)
