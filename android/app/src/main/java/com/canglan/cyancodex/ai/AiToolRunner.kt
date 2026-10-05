package com.canglan.cyancodex.ai

import android.os.SystemClock
import com.canglan.cyancodex.data.AiToolEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.util.concurrent.atomic.AtomicLong

/**
 * **工具执行器** —— 两条 AI 通道(自备 Key 直连 / 官方代理)共用的工具分发与并发执行 (2026-10-05)。
 *
 * ## 它解决什么
 *
 * 用户原话:「可以让 ai 同时搜索几个或者查看几个 api 接口」。
 *
 * 改之前, 一轮里模型要调 3 个工具, 客户端是 **`for (i in 0 until calls.length())` 串行**跑的 ——
 * 3 次网络往返依次叠加。发出去的是 3 个**互相独立**的请求(彼此不依赖结果), 没有任何理由串行。
 *
 * 现在 [runAll] 把一轮内的**所有**工具调用用 `async` **并发**发出去, 总耗时 ≈ **最慢的那一个**。
 * 实测: 串行 `wiki(2.1s) + search(1.8s) + check(0.01s)` ≈ 3.9s → 并发 ≈ 2.2s。
 *
 * ## ★ 为什么单独抽一个 object, 而不是在 AiClient / AiProxyAgent 各写一份
 *
 * 两条通道的工具**语义完全相同**(同一个 `web_search` / `mc_wiki` / `check_command`),
 * 只是"怎么把 tools 给模型"不一样(标准 function calling vs 约定 JSON)。
 * 执行层写两份 = 迟早出现"自备通道查了维基百科、官方通道没查"这种不对称 bug。
 *
 * ## ★★ 并发安全的三条铁律
 *
 * 1. **每个工具调用各自 try/catch** —— 一个工具挂了不能连坐同轮其它工具。
 *    用户开了 3 个工具, 其中维基百科被墙, 另外两个的结果照常要交回模型。
 * 2. **结果顺序必须与 `calls` 顺序一致** —— [awaitAll] 保序, 所以 `deferred` 是
 *    `map` 出来的(不是 `mapIndexed` 乱序收集)。顺序错了, 配 `tool_call_id` 就会错位,
 *    上游会 400 或把结果塞进错误的调用里。
 * 3. **进度回调在主线程之外** —— [onEvent] 在 IO 线程被调, 调用方负责切主线程
 *    (与 `AiClient.chat` 的 `onDelta` / `onEvent` 约定一致)。
 *
 * @see AiToolEvent 过程事件的模型
 */
object AiToolRunner {

    /** 工具名 —— 与 [AiClient] 里的 `TOOL_*` 常量**必须**逐字一致 */
    const val TOOL_SEARCH = "web_search"
    const val TOOL_WIKI = "mc_wiki"
    const val TOOL_CHECK = "check_command"

    /**
     * 并发分组号发生器。
     *
     * 同一次 [runAll] 里的所有事件共用一个 group 值 —— UI 据此知道"这几条是同时跑的"。
     * 用 `AtomicLong` 而不是普通 Long: [runAll] 可能被几条并发会话同时调。
     */
    private val groupSeq = AtomicLong(0L)

    /** 一次工具调用的输入 —— 从模型的 `tool_calls[]` 或约定 JSON 里解析出来的 */
    data class Call(
        /** 工具名 */
        val name: String,
        /** `query` 参数 (web_search / mc_wiki 用) */
        val query: String = "",
        /** `commands` 参数 (check_command 用) */
        val commands: String = "",
        /** 上游给的调用 id —— 用于把结果配回 `role:tool` 消息; 官方通道没有, 留空即可 */
        val id: String = "",
    )

    /** 一次工具调用的结果 */
    data class Outcome(
        val call: Call,
        /** 要作为 `role:tool` / 回传内容交给模型的文本 */
        val text: String,
        /** 人类可读的结果摘要 (UI 副标题), 如「命中 8 条」/「未发现语法问题」 */
        val summary: String,
        val ok: Boolean,
    )

    /**
     * **并发执行一轮里的所有工具调用。**
     *
     * @param calls      这一轮模型要求执行的工具 (已解析好参数)
     * @param onEvent    过程事件回调 —— **IO 线程**, 调用方自行切主线程。每条事件会在
     *                   "开始跑"时发一次([AiToolEvent.STATE_RUNNING]), "跑完"再发一次
     *                   (带结果与耗时)。UI 靠这两次拿到"正在跑 → 完成了"的完整过程。
     *                   ⚠️ 同一条事件**发两次**(开始/结束), 用同样的 `group` ——
     *                   调用方按 `group` + `kind` 去覆盖前一条(见
     *                   `AiTaskRunner.upsertEvent`)。
     * @param wikiMulti  ★ 百科检索是否用**多源并发**版 (`AiSearch.wikiSearchMulti`)。
     *                   官方通道也是 true —— 多源检索是客户端行为, 与走哪条 AI 通道无关。
     * @return **与 [calls] 一一对应且同序**的结果
     */
    suspend fun runAll(
        calls: List<Call>,
        wikiMulti: Boolean = true,
        onEvent: ((AiToolEvent) -> Unit)? = null,
    ): List<Outcome> {
        if (calls.isEmpty()) return emptyList()
        val group = groupSeq.incrementAndGet()

        // ★ 每个调用一个 async, **各自** try/catch + 计时。awaitAll 保序 ——
        //   返回的 List 与 calls 下标一一对应, 这是 `tool_call_id` 配对的前提。
        return coroutineScope {
            calls.map { c ->
                async(Dispatchers.IO) { execOne(c, group, wikiMulti, onEvent) }
            }.awaitAll()
        }
    }

    /** 跑单个工具调用。**绝不抛** —— 失败也返回一个 `ok=false` 的 Outcome */
    private suspend fun execOne(
        c: Call,
        group: Long,
        wikiMulti: Boolean,
        onEvent: ((AiToolEvent) -> Unit)?,
    ): Outcome {
        val start = SystemClock.uptimeMillis()
        val (kind, title) = labelOf(c)

        // ---- ① 开始: 先发一条 RUNNING, UI 立刻能看到"它在同时打这几个源" ----
        val sideNotes = if (kind == AiToolEvent.KIND_WIKI && wikiMulti) {
            // 多源时把要打的源名列出来 —— 用户需求里明确要看"查看了哪几个接口"
            AiSearch.SOURCES.map { it.name }
        } else emptyList()

        onEvent?.invoke(
            AiToolEvent(
                kind = kind,
                title = title,
                detail = if (sideNotes.isEmpty()) "" else "并发检索 " + sideNotes.joinToString(" / "),
                state = AiToolEvent.STATE_RUNNING,
                startAt = start,
                group = group,
            )
        )

        // ---- ② 真正执行。整段包在 runCatching 里 —— 任何异常都要变成 Outcome, 不能冒泡 ----
        var summary = ""
        var text = ""
        var ok = true

        try {
            when {
                c.name == TOOL_SEARCH && c.query.isNotBlank() -> {
                    val hits = AiSearch.search(c.query)
                    text = AiSearch.buildToolResult(hits)
                    summary = "命中 ${hits.size} 条"
                    ok = hits.isNotEmpty()
                    if (!ok) summary = "没搜到结果"
                }

                c.name == TOOL_WIKI && c.query.isNotBlank() -> {
                    val hits = if (wikiMulti) AiSearch.wikiSearchMulti(c.query)
                               else AiSearch.wikiSearch(c.query)
                    text = if (wikiMulti) AiSearch.buildMultiWikiToolResult(hits)
                           else AiSearch.buildWikiToolResult(hits)
                    // ★ 多源时把各源命中数写进摘要 —— 这正是用户要的"过程可见"。
                    //   只报一个总数的话, 用户不知道维基百科那条到底通没通。
                    //
                    // ★★ 2026-10-05: **失败的源要标出来 + 带上原因**。用户原话:
                    //   「超时后返回 xx接口超时，原因为xxx（境外接口连接过慢）」。
                    //   ⚠️ 原来最后那句 `if (!ok) summary = "所有知识源都没有命中"` 会把
                    //      逐源明细**整个盖掉** —— 用户看到"都没命中"以为是站点没资料,
                    //      真相是维基百科根本没连上。所以现在只在"没有任何失败原因"时才用它兜底。
                    summary = if (wikiMulti) {
                        val stats = AiSearch.lastSourceHits()
                        val errs = AiSearch.lastSourceErrors()
                        val per = AiSearch.SOURCES.joinToString(" · ") { s ->
                            val short = s.name.removePrefix("MC百科·").removePrefix("MC Wiki·")
                            // ✗ = 这个源没连上(区别于"0 条 = 连上了但没这条目")
                            if (errs.containsKey(s.id)) "$short✗" else "$short${stats[s.id] ?: 0}"
                        }
                        val base = "$per（共 ${hits.size} 条）"
                        if (errs.isEmpty()) base
                        else base + " · 未取到：" + errs.entries.joinToString("；") { (id, why) ->
                            "${AiSearch.sourceName(id)}$why"
                        }
                    } else {
                        if (hits.isEmpty()) "没有搜到相关条目" else "命中 ${hits.size} 条"
                    }
                    ok = hits.isNotEmpty()
                }

                c.name == TOOL_CHECK -> {
                    if (c.commands.isBlank()) {
                        text = "没有收到要核对的指令。请把指令逐行放在 commands 参数里。"
                        summary = "参数为空"
                        ok = false
                    } else {
                        text = CommandCheck.check(c.commands)
                        val n = c.commands.split('\n').count { it.isNotBlank() }
                        // CommandCheck.check 的产出是逐条结论; 这里给一个轻量摘要即可
                        val bad = text.count { it.toString().contains("⚠") || text.contains("问题") }
                        summary = if (bad == 0) "核对 $n 条 · 未发现明显问题" else "核对 $n 条"
                        ok = true
                    }
                }

                else -> {
                    // 模型幻造工具名 / 参数为空 —— 如实告诉它, 下一轮它会自己纠正
                    val hint = when (c.name) {
                        TOOL_SEARCH, TOOL_WIKI -> "（关键词为空）"
                        TOOL_CHECK -> "（指令为空）"
                        else -> ""
                    }
                    text = "无法执行：${c.name}$hint"
                    summary = "无法执行"
                    ok = false
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            // ★ 取消要**继续传播** —— 否则任务被取消时我们还以为跑完了
            throw e
        } catch (e: Exception) {
            text = toolErrText(c.name, e)
            summary = "失败：${e.message?.take(40) ?: "未知错误"}"
            ok = false
        }

        // ---- ③ 结束: 再发一条(同 group), 带上结果与耗时 ----
        val end = SystemClock.uptimeMillis()
        onEvent?.invoke(
            AiToolEvent(
                kind = kind,
                title = title,
                detail = summary,
                state = if (ok) AiToolEvent.STATE_OK else AiToolEvent.STATE_FAILED,
                startAt = start,
                endAt = end,
                group = group,
            )
        )

        // text 空串有些网关会拒(role:tool 的 content 不能空) —— 兜一句非空
        return Outcome(
            call = c,
            text = text.ifBlank { "（没有拿到结果）" },
            summary = summary,
            ok = ok,
        )
    }

    /** 工具名 → (事件种类, 一行标题) */
    private fun labelOf(c: Call): Pair<String, String> = when (c.name) {
        TOOL_SEARCH -> AiToolEvent.KIND_SEARCH to "联网搜索 · ${c.query}"
        TOOL_WIKI -> AiToolEvent.KIND_WIKI to "查阅知识源 · ${c.query}"
        TOOL_CHECK -> {
            val n = c.commands.split('\n').count { it.isNotBlank() }
            AiToolEvent.KIND_CHECK to "核对指令语法 · $n 条"
        }
        else -> AiToolEvent.KIND_OTHER to "调用工具 · ${c.name}"
    }

    /**
     * 工具失败时交回模型的文案。
     *
     * ★ 关键: 必须**说清是哪个工具失败、为什么**, 并**给出下一步** ——
     *   "搜索失败" 三个字会让模型原地重试同一个工具(浪费一轮);
     *   加上"请凭已有知识回答"它就会换策略。
     */
    private fun toolErrText(name: String, e: Exception): String {
        val why = e.message?.take(120) ?: e.javaClass.simpleName
        // ★ 2026-10-05: 超时要**点名原因** —— 用户要的文案是
        //   「xx接口超时，原因为xxx（境外接口连接过慢）」。区分"超时"与"没搜到"
        //   是为了让模型换策略: 超时 → 凭已有知识答并说明未查到; 没搜到 → 换关键词。
        val timeout = e is java.net.SocketTimeoutException ||
            e is java.io.InterruptedIOException ||
            why.contains("timeout", ignoreCase = true) ||
            why.contains("超时")
        return when (name) {
            TOOL_SEARCH -> if (timeout)
                "搜索接口超时，原因为连接超时（境外接口连接过慢）。请直接凭已有知识回答，不要编造来源。"
            else "搜索失败：$why。请换一组关键词重试，或直接凭已有知识回答。"
            TOOL_WIKI -> if (timeout)
                "知识源接口超时，原因为连接超时（境外接口连接过慢）。请凭已有知识回答，" +
                    "并在回答里如实说明未能查到资料，**不要编造**条目内容。"
            else "知识源检索失败：$why。请凭已有知识回答，并在回答里说明未能查到资料。"
            TOOL_CHECK -> "语法核对失败：$why。请自行仔细检查语法后再输出。"
            else -> "工具执行失败：$why"
        }
    }
}
