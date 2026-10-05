package com.canglan.cyancodex.ai

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.canglan.cyancodex.data.AiMode
import com.canglan.cyancodex.data.AiToolEvent
import com.canglan.cyancodex.data.ApiClient
import com.canglan.cyancodex.data.Session
import com.canglan.cyancodex.data.UiMsg
import com.canglan.cyancodex.ui.components.errText
import com.canglan.cyancodex.ui.components.safeApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * AI 回答任务的**托管器** (2026-10-02)。
 *
 * ## 它解决什么
 * 用户反馈「AI 任务一切后台就断」。
 *
 * 老写法是 `AiScreen` 里 `rememberCoroutineScope().launch { AiClient.chat(...) }`。
 * `rememberCoroutineScope()` 拿到的 scope **绑在 composition 上** —— Activity 一销毁
 * (系统内存回收、从最近任务划掉、配置变化)整棵协程树被 cancel, 请求当场断,
 * 而且 `busy` / `phase` 这些 `remember` 出来的状态也一起没了, 用户切回来只看到
 * 一个"什么都没发生过"的界面。
 *
 * 现在: 请求跑在**进程级作用域**([scope])上, 与界面生命周期彻底解耦;
 * 结果直接写进 [AiConvStore](本来就落盘), 界面只是**订阅者** ——
 * Activity 重建后重新读一次 store 就能看到完整结果。
 *
 * ## ★ 并发: 每个会话一条独立任务 (2026-10-02 第二批)
 *
 * 用户报「有消息在思考，切到新对话不给发送」。那不是 bug, 是**设计错了** ——
 * 原来 `busy` 是个全局布尔, `if (busy) return` 让整个 App 同一时刻只能有一个请求。
 * 但"会话"本来就是互相独立的上下文, 没有理由串行。
 *
 * 现在全部状态按 `convId` 分开:
 *
 * | 状态 | 原来 | 现在 |
 * |---|---|---|
 * | 在跑的任务 | `busy: Boolean` | [running]: `convId -> 开始时刻` |
 * | 气泡文案 | `phase: String` | [phases]: `convId -> String` |
 * | 流式正文 | `streaming: String` | [streams]: `convId -> String` |
 * | 过程步骤 | 无 | [events]: `convId -> List<String>` |
 * | 计时 | `elapsedMs: Long` | [clock] 全局心跳 + [elapsedFor] 现算 |
 *
 * 流式缓冲区更是**每个任务各自一份局部变量**(见 [send] 里的 `buf`) ——
 * 共享一份的话, 并发时 A 会话的字会吐到 B 会话的气泡里。
 *
 * 仍然保留 [busy](= 任意会话在跑): 前台服务与"要不要补完成通知"是**进程级**的事,
 * 不分会话。
 *
 * ## 为什么状态是 `mutableStateOf` / `mutableStateMapOf` 而不是 StateFlow
 * 它们要被 Compose **直接读**。把 `mutableStateOf` 放在普通 object 里是合法的,
 * Composable 读它照样会订阅重组 —— 这样界面侧一行 `AiTaskRunner.isBusyFor(id)` 就够了,
 * 不需要 collect + remember 那一套样板。
 *
 * ★ `mutableStateMapOf` 的读是**按 key 追踪**的: 读 `streams[id]` 只订阅 `id` 这个 key,
 *   别的会话吐字不会让这条会话的气泡重组。
 *
 * ## 与前台服务的分工
 * 本类管"**协程不被取消**"; [AiTaskService] 管"**进程不被冻结**"。
 * 两者缺一不可, 详见 [AiTaskService] 的类注释。
 */
object AiTaskRunner {

    /**
     * 进程级作用域。`SupervisorJob` —— 一个任务失败不该带崩整个 scope。
     *
     * `Dispatchers.Main.immediate` 而不是 IO: 真正的网络/解析在 [AiClient] /
     * [AiProxyAgent] 内部各自 `withContext(Dispatchers.IO)`, 这里用 Main 是为了
     * 让状态赋值与 UI 读取在同一线程语义下, 不用操心并发可见性。
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    // =================================================================================
    //  按会话分开的状态
    // =================================================================================

    /** 在跑的会话: `convId -> 开始时刻(uptimeMillis)`。**可以同时有多个** */
    private val running = mutableStateMapOf<String, Long>()

    /** 忙的时候气泡上显示的字, 按会话 */
    private val phases = mutableStateMapOf<String, String>()

    /**
     * ★ **流式输出的实时正文**, 按会话。
     *
     * 自备 API 通道每收到一段增量就往这里追加, 界面上就是"字一个个蹦出来"。
     * 空串 = 这条会话当前没有流(官方通道、上游不支持流式、或还没吐第一个字)。
     *
     * ⚠️ 只在**当轮**有效: [AiClient.chat] 每开始一轮新请求都会回调 `onStreamStart` 清空它,
     *   否则"先吐了半截正文、又决定去调工具"的那半截会和最终答案粘在一起。
     */
    private val streams = mutableStateMapOf<String, String>()

    /**
     * ★ **过程步骤**, 按会话 —— 用户在等待气泡里看到的那几行字 (2026-10-02)。
     *
     * 用户原话: "在思考过程和调用工具的过程中应该让用户知道 AI 在动, 展示过程"。
     * 只转一个圈是不够的 —— 用户不知道它是在查百科、在核语法、还是在等上游超时。
     *
     * 内容形如 `["查阅 MC 百科 · 命令 execute", "核对指令语法 · 3 条", "复查通过 · 未发现 ID 问题"]`,
     * **最新的在最后**。只留最近 [EVENT_KEEP] 条 —— 这是给眼睛看的状态行, 不是日志。
     */
    private val events = mutableStateMapOf<String, List<String>>()

    /** [events] 每条会话最多留几条。超了丢最旧的 */
    private const val EVENT_KEEP = 8

    /**
     * ★★ **结构化工具时间线**, 按会话 (2026-10-05) —— 用户在全程时间线里看到的那几条。
     *
     * 与 [events] 的区别: [events] 是**一行字**, 没有状态/耗时/来源; 这里每条是
     * [AiToolEvent], 带 `state`(在跑 / 成功 / 失败)、`startAt`(算耗时)、
     * `group`(哪几条是**同时**跑的)。
     *
     * ## 为什么要"覆盖"而不是"追加"
     * 同一条工具调用在生命周期里会来**两次**事件: 开始(RUNNING) 与 结束(OK/FAILED),
     * 两次的 `group` + `kind` + `title` 相同。UI 上它应该是**一条会变色的行**,
     * 而不是"开始 + 结束"两行。所以 [upsertEvent] 用 `group|kind|title` 当键**就地替换**。
     *
     * ⛔ 键里**必须**含 `group`: 同一个会话连续两轮查同一个词(模型常干)时,
     *    `kind|title` 完全相同 —— 不含 group 就会把第二轮的开始状态覆盖掉第一轮的结束状态,
     *    表现为"明明查完了, 那行又转起来"。group 每轮递增, 天然区分。
     */
    private val toolEvents = mutableStateMapOf<String, List<AiToolEvent>>()

    /** [toolEvents] 每条会话最多留几条 —— 比 [events] 多, 因为它是"完整过程", 不是状态行 */
    private const val TOOL_EVENT_KEEP = 14

    /**
     * ★ **全局时钟心跳**(每 200ms 一次, 值就是 `SystemClock.uptimeMillis()`)。
     *
     * 计时不再按任务各起一个协程 —— 那是 N 个任务 N 个计时器。现在所有任务共用这一个心跳,
     * 要显示"已等多久"就 [elapsedFor] 现算一次。**任务越多越省。**
     *
     * ⚠️ 用 `uptimeMillis` 而不是 `currentTimeMillis`: 后者会被用户改系统时间影响。
     */
    var clock by mutableStateOf(0L)
        private set

    /** 官方通道最近一次返回的积分余额。**账户级, 不分会话** */
    var balance by mutableStateOf<Int?>(null)
        private set

    /**
     * 每次任务收尾自增。UI 用它当 `LaunchedEffect` 的 key ——
     * 任务在后台跑完时界面可能压根不存在, 重建后靠这个值触发"重读会话".
     */
    var tick by mutableStateOf(0)
        private set

    init {
        scope.launch {
            while (true) {
                clock = SystemClock.uptimeMillis()
                delay(200)
            }
        }
    }

    // =================================================================================
    //  界面读法
    // =================================================================================

    /** 有**任意**会话在跑。前台服务/完成通知这类进程级判断用它 */
    val busy: Boolean get() = running.isNotEmpty()

    /** **这条**会话是不是正在跑任务 */
    fun isBusyFor(id: String): Boolean = running.containsKey(id)

    /** 这条会话气泡上那行字 */
    fun phaseFor(id: String): String = phases[id] ?: "思考中…"

    /** 这条会话的流式正文, 没在流就是空串 */
    fun streamFor(id: String): String = streams[id].orEmpty()

    /** 这条会话**已经走过的步骤**(最新的在最后)。没在跑就是空列表 */
    fun eventsFor(id: String): List<String> = events[id].orEmpty()

    /**
     * 这条会话的**结构化工具时间线**(最新的在最后)。
     *
     * 这是「全程实时时间线」的数据源 —— 比 [eventsFor] 多带状态 / 耗时 / 并发分组。
     * 没在跑就是空列表。
     */
    fun toolEventsFor(id: String): List<AiToolEvent> = toolEvents[id].orEmpty()

    /**
     * 写入一条结构化工具事件 —— **IO 线程**调用(工具执行器就在 IO 上)。
     *
     * 同 `(group, kind, title)` 的已有事件会被**就地替换**(见 [toolEvents] 的注释):
     * 于是"开始跑"那条会自然变成"跑完了 + 耗时"。
     */
    fun upsertEvent(id: String, ev: AiToolEvent) {
        ui.post {
            val cur = toolEvents[id].orEmpty()
            val key = "${ev.group}|${ev.kind}|${ev.title}"
            val idx = cur.indexOfLast { "${it.group}|${it.kind}|${it.title}" == key }
            val next = if (idx >= 0) {
                cur.toMutableList().also { it[idx] = ev }
            } else {
                (cur + ev).takeLast(TOOL_EVENT_KEEP)
            }
            toolEvents[id] = next
        }
    }

    /** 这条会话**已经等了多久**(毫秒)。没在跑就是 0 */
    fun elapsedFor(id: String): Long = running[id]?.let { clock - it } ?: 0L

    /** [elapsedFor] 的可读形态: `12 秒` / `1 分 05 秒` */
    fun elapsedText(id: String): String {
        val s = elapsedFor(id) / 1000
        return if (s < 60) "$s 秒" else "${s / 60} 分 ${(s % 60).toString().padStart(2, '0')} 秒"
    }

    // =================================================================================
    //  流式增量的**跨线程**投递
    // =================================================================================

    /**
     * 主线程 Handler。原因: [AiClient.chat] 整个跑在 `Dispatchers.IO` 上, 它的
     * `onDelta` 回调**也在 IO 线程**, 而 Compose 状态必须在主线程改。
     */
    private val ui = Handler(Looper.getMainLooper())

    /**
     * 发起一轮问答。**非阻塞**: 立即返回, 结果异步落盘。
     *
     * @param history  含本轮 user 消息的完整历史。收尾时把它 + assistant 消息整体写回 store,
     *                 所以调用方**不需要**再自己拼结果。
     * @param searchOn 智能搜索是否可用(已配引擎且用户开了开关)
     * @param mode ★ **会话模式** (2026-10-05) —— 见 [com.canglan.cyancodex.data.AiMode]。
     *             它**收口**了原来散落的 `cmdExpert` / `syntaxCheck` 两个闸:
     *               · [AiMode.CHAT] → 都不挂, 纯聊天
     *               · [AiMode.CMD]  → 挂 MC 百科 + 语法核对
     *               · [AiMode.LINT] → 只挂语法核对, 不挂百科
     *             由调用方按**该会话**的模式传进来 (模式存在 [com.canglan.cyancodex.data.AiConv.mode])。
     */
    fun send(
        convId: String,
        history: List<UiMsg>,
        searchOn: Boolean,
        mode: String,
    ) {
        // ★ 模式 → 两个工具闸。在这里**一次性**算出来, 下游只认 boolean ——
        //   免得 AiClient / AiProxyAgent 也要 import AiMode (那样两条通道又多一层耦合)。
        val cmdExpert = AiMode.cmdExpert(mode)
        val syntaxCheck = AiMode.syntaxCheck(mode)
        // 客户端复查是"指令专家 + 语法核对"的产物 —— 没有语法核对该功能就没意义
        val recheck = syntaxCheck

        // ★ 只挡"同一条会话重复发"。别的会话在跑**不影响**这条 ——
        //   原来是全局 `if (busy) return`, 表现为"有消息在思考时, 切到新对话发送按钮是灰的"。
        if (running.containsKey(convId)) return

        val ctx = Session.appCtx
        val start = SystemClock.uptimeMillis()
        running[convId] = start
        phases[convId] = "思考中…"
        streams[convId] = ""
        events[convId] = emptyList()
        toolEvents[convId] = emptyList()
        clock = start

        // ---- 每个任务**各自一份**流式缓冲 ----
        // 用局部变量而不是共享字段: 并发时共享一份会把 A 会话的字吐到 B 会话的气泡里。
        val buf = StringBuilder()
        /** 上次刷 UI 的时刻 —— 节流用。只在 IO 线程读写, 不需要加锁 */
        var lastFlush = 0L
        /** 任务是否还活着。收尾时置 false, 让**还在路上**的 post 全部失效 */
        val alive = AtomicBoolean(true)

        /** 把缓冲里的增量并进 [streams] —— **主线程**调用 */
        // ⚠️ 必须声明在 [pushDelta] **之前**: Kotlin 的局部函数不能前向引用
        //    (写成成员函数就没这个限制, 这里为了"每个任务一份 buf"只能用局部函数)。
        fun flushStream() {
            val s = synchronized(buf) {
                val t = buf.toString()
                buf.setLength(0)
                t
            }
            if (s.isNotEmpty()) streams[convId] = streams[convId].orEmpty() + s
        }

        /** 收到一段增量 —— **IO 线程**调用 */
        fun pushDelta(d: String) {
            val len = synchronized(buf) { buf.append(d); buf.length }
            val now = SystemClock.uptimeMillis()
            // ★ 节流: 上游可能几十毫秒吐一个 token, 每个都刷一次会把主线程刷满,
            //   而且 Compose 一帧最多也就渲染一次 —— 攒够一段再刷, 观感完全一样。
            //
            // ⚠️ 间隔**随已收到的字数放大** (2026-10-02 十一批)。原因: 流式气泡现在也走
            //   Markdown 渲染(见 AiScreen), 而"重解析 + 重排"的成本与正文长度成正比 ——
            //   短回答 80ms 一刷很顺, 长回答还按 80ms 刷就是拿主线程硬扛。
            //   用 `buf.length` 而不是"已经渲染了多少字": 后者要跨线程读状态, 不值当。
            val interval = when {
                len > 3000 -> 200L
                len > 1200 -> 130L
                else -> 80L
            }
            if (now - lastFlush < interval) return
            lastFlush = now
            ui.post { if (alive.get()) flushStream() }
        }

        /**
         * 收到一条**过程事件** —— **IO 线程**调用。
         *
         * 和 [pushDelta] 一样必须切主线程: `events` 是 Compose 状态。
         * ⚠️ 连续重复的事件**丢掉** —— 模型连着两轮查同一个词是常事,
         *   列成两行一模一样的字只会让用户以为卡住了。
         */
        fun pushEvent(e: String) {
            ui.post {
                if (!alive.get()) return@post
                val cur = events[convId].orEmpty()
                if (cur.lastOrNull() == e) return@post
                events[convId] = (cur + e).takeLast(EVENT_KEEP)
            }
        }

        // 先起前台服务再发请求: 保活的窗口要覆盖整个请求期, 晚起就漏掉了开头那段
        AiTaskService.start(ctx)

        scope.launch {
            // ---- 6 秒还没回来 → 明确告诉用户可以切出去 ----
            // 这是本次改造的**核心卖点**, 但用户不知道就等于没有。放在这里比写在更新日志里有效。
            val hintJob = launch {
                delay(6000)
                if (running.containsKey(convId)) {
                    phases[convId] = "还在思考…可以先切到别的应用，好了会通知你"
                }
            }
            var ok = false
            try {
                // ★ 每轮都重新读 Session: 用户在等待期间可能改了模型/轮数, 以"发出去那一刻"为准更符合直觉
                val useKey = Session.hasAiKey() && !Session.aiUseOfficial
                // ★ 2026-10-05: 工具闸由**模式**决定, 在 send() 入口就算好了 (见那里的注释) ——
                //   这里是闭包捕获, 等待期间用户改设置不会影响已经发出去的这一轮。
                if (useKey) {
                    // ---- 自备 API: 本机直连 ----
                    val r = AiClient.chat(
                        history = history,
                        base = Session.aiApiBase,
                        key = Session.aiApiKey,
                        model = Session.aiModel,
                        think = Session.effectiveThink(),
                        searchTool = searchOn,
                        cmdExpert = cmdExpert,
                        syntaxCheck = syntaxCheck,
                        recheck = recheck,
                        maxToolRounds = Session.aiToolRounds,
                        // ★ 流式: 增量走 pushDelta 节流后刷 UI。回调在 IO 线程, 见那里的注释。
                        onStreamStart = {
                            synchronized(buf) { buf.setLength(0) }
                            ui.post { if (alive.get()) streams[convId] = "" }
                        },
                        onDelta = { d -> pushDelta(d) },
                        onEvent = { e -> pushEvent(e) },
                        // ★ 结构化工具事件 → 时间线。upsertEvent 内部自己切主线程(工具执行器在 IO 上)
                        onToolEvent = { ev -> upsertEvent(convId, ev) },
                    )
                    // 耗时按**整轮任务**算(含工具调用), 和原来一致
                    val spent = SystemClock.uptimeMillis() - start
                    val body = buildString {
                        append(r.content)
                        if (r.note.isNotBlank()) append("\n\n（").append(r.note).append("）")
                    }
                    AiConvStore.setMessages(
                        convId,
                        history + UiMsg(
                            "assistant", body,
                            think = r.reasoning,
                            thinkMs = if (r.reasoning.isNotBlank()) spent else 0L,
                            searchQueries = r.searchQueries,
                            wikiQueries = r.wikiQueries,
                            checkCount = r.checkCount,
                            rechecked = r.rechecked,
                            recheckIssueCount = r.recheckIssueCount,
                            recheckIssues = r.recheckIssues,
                        ),
                    )
                    ok = true
                } else {
                    // ---- 官方 AI (消耗积分) ----
                    val r = AiProxyAgent.run(
                        messages = history,
                        searchEnabled = searchOn,
                        cmdExpert = cmdExpert,
                        syntaxCheck = syntaxCheck,
                        recheck = recheck,
                        maxRounds = Session.aiToolRounds,
                        onEvent = { e -> pushEvent(e) },
                        onToolEvent = { ev -> upsertEvent(convId, ev) },
                    ) { json ->
                        val resp = safeApi { ApiClient.get().aiProxy(mapOf("messages" to json)) }
                        if (resp.ok && resp.data?.content != null) {
                            AiProxyAgent.Turn(
                                ok = true, content = resp.data.content,
                                cost = resp.data.cost, balance = resp.data.balance,
                                turnId = resp.data.turnId,
                            )
                        } else {
                            AiProxyAgent.Turn(ok = false, content = "", error = resp.error ?: "AI 请求失败")
                        }
                    }
                    if (r.balance != null) balance = r.balance
                    if (r.ok) {
                        val reply = buildString {
                            append(r.content)
                            if (r.cost > 0) {
                                append("\n\n— 本次消耗 ").append(r.cost).append(" 积分(基础5+按Tokens")
                                // 多轮时点明"这是几轮之和", 否则用户只看到数字变大, 不知道钱花在哪
                                if (r.rounds > 1) append("，共 ").append(r.rounds).append(" 轮")
                                append(")，剩余 ").append(r.balance).append(" 积分")
                            }
                        }
                        AiConvStore.setMessages(
                            convId,
                            history + UiMsg(
                                "assistant", reply,
                                turnId = r.turnId,
                                searchQueries = r.searchQueries,
                                wikiQueries = r.wikiQueries,
                                checkCount = r.checkCount,
                                rechecked = r.rechecked,
                                recheckIssueCount = r.recheckIssueCount,
                                recheckIssues = r.recheckIssues,
                            ),
                        )
                        ok = true
                    } else {
                        AiConvStore.setMessages(
                            convId, history + UiMsg("assistant", r.content, err = true)
                        )
                    }
                }
            } catch (e: CancellationException) {
                // 取消必须继续传播(safeApi 也是这个约定), 但收尾照做 —— 见 finally
                throw e
            } catch (e: Exception) {
                AiConvStore.setMessages(convId, history + UiMsg("assistant", errText(e), err = true))
            } finally {
                hintJob.cancel()
                // ★ 顺序: 先让**还在路上**的增量投递失效, 再清状态。
                //   反过来的话, 那几帧延迟到达的 post 会把刚清空的气泡又写脏,
                //   表现为"回答完了气泡里还挂着半句正文"。
                alive.set(false)
                synchronized(buf) { buf.setLength(0) }
                running.remove(convId)
                phases.remove(convId)
                streams.remove(convId)
                events.remove(convId)
                toolEvents.remove(convId)
                tick++
                // ⚠️ 还有别的会话在跑就**不能停服务** —— 停了那个任务的保活就没了。
                //   完成通知照补: 用户可能已经切出去了, 就是想知道哪条好了。
                AiTaskService.finish(ctx, ok, running.isNotEmpty())
            }
        }
    }
}
