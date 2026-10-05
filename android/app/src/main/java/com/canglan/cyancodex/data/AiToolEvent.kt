package com.canglan.cyancodex.data

/**
 * **工具调用过程事件** —— 给「全程实时时间线」用的一条记录 (2026-10-05)。
 *
 * ## 为什么要单开一个模型, 而不是继续用 `List<String>`
 *
 * 原来 `AiTaskRunner.events` 是 `List<String>`, 一行一句话, 够用但不表达**状态** ——
 * 用户原话是「要把 AI 在中间使用工具、然后输出的内容什么的完整过程也输出出来」。
 * "完整过程"意味着用户要看到的不只是"它做了什么", 还有:
 *  - **做完了没有** (还在跑 / 成功 / 失败);
 *  - **花了多久** (0.4 秒查到, 还是等了 12 秒超时);
 *  - **从哪来的** (MC 百科? mcmod? 维基百科? —— 多源并发时必须区分);
 *  - **查到了什么** (命中了几个条目 —— 0 条和 8 条的观感完全不同)。
 *
 * 一个字符串塞不下这四件事(硬塞就是一坨拼接)。所以升级成结构化模型。
 *
 * ## ★ 硬约束: 字段名靠 Gson + R8 保命, 所以**必须放在 `data` 包**
 *
 * 本工程 Gson 的 R8 规则只对 `data` 包的类保留字段名(见 `proguard-rules.pro` 的
 * `-keep class com.canglan.cyancodex.data.** { *; }`)。放进别的包, 字段名会被混淆成
 * `a` / `b`, 落盘再读出来就是一堆 null —— 而且**不报错**, 静默数据丢失。
 *
 * ⚠️ 但本类**不落盘**(只活在内存里, 见 `AiTaskRunner.events`)。仍然放 `data` 包是
 *    为了统一 —— 同族的 [UiMsg] / [AiConv] 都在这儿, 混着放迟早有人挪错。
 */
data class AiToolEvent(
    /** 事件种类 —— 决定 UI 上挂什么图标、用什么颜色 */
    val kind: String,
    /** 一行给人的话, 如「查阅 MC 百科 · 命令 execute」 */
    val title: String,
    /**
     * 结果摘要 —— 副标题那行小字。
     * 空串 = 还没有结果(还在跑)。
     */
    val detail: String = "",
    /**
     * 进度状态。取值见本类伴生常量, **不要**自己拼字符串。
     */
    val state: String = STATE_RUNNING,
    /** 这条开始的时刻 (`SystemClock.uptimeMillis`), 用来算耗时 */
    val startAt: Long = 0L,
    /** 这条结束的时刻。0 = 还没结束(配合 [state] 判断"卡住了"才有意义) */
    val endAt: Long = 0L,
    /**
     * **并发分组** —— 同一次工具调用里并行打出去的那几个源, 用同一个值。
     *
     * 为什么需要: 用户在需求里明确要「可以让 AI 同时搜索几个或者查看几个 api 接口」。
     * UI 上必须能表达"这几条是**同时**在跑的", 而不是排成一行行看起来像串行。
     * 同 group 的事件在时间线上并排(或缩进)展示。
     */
    val group: Long = 0L,
) {
    /** 这条花了多少毫秒。还在跑的话传 `now` 进来算到当前 */
    fun costMs(now: Long = 0L): Long {
        if (startAt <= 0L) return 0L
        val end = if (endAt > 0L) endAt else now
        return (end - startAt).coerceAtLeast(0L)
    }

    /** 耗时文本: `320 毫秒` / `1.4 秒` */
    fun costText(now: Long = 0L): String {
        val ms = costMs(now)
        return if (ms < 1000) "$ms 毫秒" else String.format(java.util.Locale.US, "%.1f 秒", ms / 1000.0)
    }

    val isRunning: Boolean get() = state == STATE_RUNNING
    val isFailed: Boolean get() = state == STATE_FAILED

    companion object {
        /** 正在跑 */
        const val STATE_RUNNING = "running"

        /** 成功(有结果) */
        const val STATE_OK = "ok"

        /** 这次调用失败 / 没拿到东西 —— UI 上标红 */
        const val STATE_FAILED = "failed"

        // ---- kind ----
        /** 联网搜索 (`web_search`) */
        const val KIND_SEARCH = "search"

        /** 百科 / 知识源检索 (`mc_wiki`), 含多源并发 */
        const val KIND_WIKI = "wiki"

        /** 语法核对 (`check_command`) */
        const val KIND_CHECK = "check"

        /** 客户端自己的复查 (不是模型调的工具, 是本机跑 [com.canglan.cyancodex.ai.CommandCheck]) */
        const val KIND_RECHECK = "recheck"

        /** 通用/未知 —— 兜底, 不要主动用 */
        const val KIND_OTHER = "other"
    }
}
