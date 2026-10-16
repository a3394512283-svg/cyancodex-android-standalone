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
import com.canglan.cyancodex.data.DebugStore
import com.canglan.cyancodex.data.FreeAi
import com.canglan.cyancodex.data.Session
import com.canglan.cyancodex.data.UiMsg
import com.canglan.cyancodex.ui.components.errText
import com.canglan.cyancodex.ui.components.safeApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
 * | 流式思考 | 无 | [thinks]: `convId -> String` |
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

    /**
     * ★ **每个在跑任务的协程 Job**, 按会话 —— 给「停止生成」用 (2026-10-05)。
     *
     * 用户原话:「那个进行中的任务发送键不要始终灰色, 改成像 workbuddy 等 agent 工具一样
     * 有个停止生成」。点停止时 [stop] 会 `cancel()` 它。
     *
     * ⚠️ 用普通 `HashMap` 而不是 `mutableStateMapOf`: 它**不参与 UI 重组**, 只是内部账本,
     *   而且读写全在 Main 线程([send] 与 [finally] 都在 `Dispatchers.Main.immediate` 上)。
     */
    private val jobs = HashMap<String, Job>()

    /**
     * ★ **协作式停止旗标**, 按会话 (2026-10-05)。见 [AiClient.chat] 的 `shouldStop` 参数 ——
     * `job.cancel()` 对**阻塞的** OkHttp 读流不生效, 必须再插一个旗标让它在读循环里自查。
     * 两者一起用: 旗标负责"立刻停", cancel 负责"兜住一切其它挂起点的取消"。
     */
    private val stops = HashMap<String, AtomicBoolean>()

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
     * ★★ **流式思考的实时正文**(推理内容), 按会话 (2026-10-05)。
     *
     * 用户原话: "我的过程指的是像 workbuddy 或者 ima 一样**展示中间思考的内容**和调用的工具"。
     * [streams] 装的是**答案正文**, 这里装的是模型的 `reasoning_content` —— 它"在想什么"。
     * 两者在界面上是**两块不同的东西**: 思考块(可折叠, 灰底斜体)与答案气泡。
     *
     * 空串 = 这条会话当前没有思考流(上游没返回思考内容、官方通道、或还没吐第一个字)。
     * 上游不返回思考的模型(很多非推理模型)这里会**一直是空串**, UI 侧要能优雅地不显示这一块。
     *
     * ⚠️ 与 [streams] 同样的规矩: [AiClient.chat] 每开始一轮都会回调 `onStreamStart`,
     *   调用方在那里**两个缓冲都要清** —— 上一轮的半截思考不能粘到这一轮。
     */
    private val thinks = mutableStateMapOf<String, String>()

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

    /**
     * ★★ **停止生成** (2026-10-05) —— 用户点输入框右边那个"停止"键时调。
     *
     * 用户原话:「那个进行中的任务发送键不要始终灰色, 改成像 workbuddy 等 agent 工具一样
     * 有个停止生成」。做两件事, **顺序不能反**:
     *   ① 先翻协作旗标 —— 阻塞在读流循环里的 [AiClient.chat] 下一次检查点就会自己退出;
     *   ② 再 `cancel()` 协程 —— 兜住"不在读流循环里"的挂起点(如官方通道的 Retrofit 请求)。
     *
     * 已经流出来的那半截回答**不会丢**: 收尾时 [send] 的 `catch (CancellationException)`
     * 会把它存进会话 —— DeepSeek / WorkBuddy 都是这个行为, 直接丢掉等于让用户白等。
     *
     * ⚠️ 没在跑的会话点了是**空操作**, 不会抛。
     */
    fun stop(id: String) {
        stops[id]?.set(true)
        jobs[id]?.cancel()
    }

    /**
     * ★ **停止所有正在跑的会话** (2026-10-05) —— 给**通知栏那个「停止生成」按钮**用。
     *
     * 为什么需要它: 用户切到别的应用后想放弃这一轮, 通知栏点一下就完事,
     * 不用切回 App 找那条会话。通知是**进程级**的(不区分会话), 所以这里也只能是
     * "全停" —— 但这恰恰是对的: 通知上写的就是"AI 正在回答", 点它的语义就是"别答了"。
     *
     * ⚠️ 必须先 `toList()` 取快照再遍历: [stop] 不改 `stops`(移除发生在各任务的 `finally`),
     *   但遍历 `HashMap.keys` 的同时由别的线程改它是未定义行为, 快照最省心。
     */
    fun stopAll() {
        stops.keys.toList().forEach { stop(it) }
    }

    /** 这条会话气泡上那行字 */
    fun phaseFor(id: String): String = phases[id] ?: "思考中…"

    /** 这条会话的流式正文, 没在流就是空串 */
    fun streamFor(id: String): String = streams[id].orEmpty()

    /**
     * 这条会话的**流式思考正文**(推理内容), 没在思考就是空串。
     *
     * 这是「像 WorkBuddy / ima 一样展示中间思考」的数据源 —— 与 [streamFor] 并列:
     * [streamFor] 是最终答案, 这里是模型"想"的过程。
     */
    fun thinkFor(id: String): String = thinks[id].orEmpty()

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
     * @param prevAssistant ★ 被本次重发**替换掉**的旧回答 (2026-10-05)。「重新生成」与
     *             「修改后重发」时非空 —— 旧回答不会被丢, 而是作为**第 1 版**并进
     *             [com.canglan.cyancodex.data.UiMsg.variants], 界面上就是 DeepSeek 那个
     *             `‹ 1/2 ›` 切换器。普通发送传 null。
     * @param prevUserText ★ 旧回答对应的**用户提问原文**。为什么要传: 「修改」改的正是
     *             用户那句提问, 每一版必须各自记住"当时问的是什么", 切回第 1 版时上面那条
     *             user 气泡要跟着换回去。
     */
    fun send(
        convId: String,
        history: List<UiMsg>,
        searchOn: Boolean,
        mode: String,
        prevAssistant: UiMsg? = null,
        prevUserText: String = "",
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
        thinks[convId] = ""
        events[convId] = emptyList()
        toolEvents[convId] = emptyList()
        clock = start

        // ---- 每个任务**各自一份**流式缓冲 ----
        // 用局部变量而不是共享字段: 并发时共享一份会把 A 会话的字吐到 B 会话的气泡里。
        val buf = StringBuilder()
        /** 思考流的独立缓冲 —— 与 [buf] 分开, 否则答案和推理会串到同一块 UI */
        val thinkBuf = StringBuilder()
        /** 上次刷 UI 的时刻 —— 节流用。只在 IO 线程读写, 不需要加锁 */
        var lastFlush = 0L
        /** 思考流上次刷 UI 的时刻 —— 独立节流, 互不干扰 */
        var lastThinkFlush = 0L
        /** 任务是否还活着。收尾时置 false, 让**还在路上**的 post 全部失效 */
        val alive = AtomicBoolean(true)

        /** ★ 协作式停止旗标 —— 见 [stops] 与 [AiClient.chat] 的 `shouldStop` */
        val stopFlag = AtomicBoolean(false)
        stops[convId] = stopFlag

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

        /** 把思考缓冲并进 [thinks] —— **主线程**调用 */
        fun flushThink() {
            val s = synchronized(thinkBuf) {
                val t = thinkBuf.toString()
                thinkBuf.setLength(0)
                t
            }
            if (s.isNotEmpty()) thinks[convId] = thinks[convId].orEmpty() + s
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
         * 收到一段**思考**增量 —— **IO 线程**调用 (2026-10-05)。
         *
         * 与 [pushDelta] 同构, 但写进独立的 [thinks] 缓冲。节流阈值比正文**更宽松**
         * (思考内容通常比答案长得多, 而且不需要像答案那样"逐字有反馈感"),
         * 但同样随长度放大, 免得长推理把主线程刷满。
         */
        fun pushThink(d: String) {
            val len = synchronized(thinkBuf) { thinkBuf.append(d); thinkBuf.length }
            val now = SystemClock.uptimeMillis()
            val interval = when {
                len > 3000 -> 240L
                len > 1200 -> 150L
                else -> 90L
            }
            if (now - lastThinkFlush < interval) return
            lastThinkFlush = now
            ui.post { if (alive.get()) flushThink() }
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

        // ★ LAZY 启动 + **先登记 Job 再 start**: 保证 [stop] 一定能拿到 Job。
        //   直接 `scope.launch { }` 的话, 在 `Main.immediate` 上可能**立刻开跑**,
        //   万一同步跑到 finally, 我们还没赋值 `jobs[convId]` 就留下了脏条目。
        val job = scope.launch(start = CoroutineStart.LAZY) {
            // ---- 6 秒还没回来 → 明确告诉用户可以切出去 ----
            // 这是本次改造的**核心卖点**, 但用户不知道就等于没有。放在这里比写在更新日志里有效。
            // ★ 2026-10-05: 措辞要**看通知开没开** —— 通知被关了还承诺"好了会通知你"
            //   就是骗人; 这种情况如实告诉他"切出去要回来才看得到", 并提示去哪开。
            val canNotify = AiTaskService.notificationsEnabled(ctx)
            val hintJob = launch {
                delay(6000)
                if (running.containsKey(convId)) {
                    phases[convId] = if (canNotify)
                        "还在思考…可以先切到别的应用，好了会通知你"
                    else
                        "还在思考…（通知已关闭，切出去后要回来才看得到结果）"
                }
            }
            var ok = false
            try {
                // ★ 每轮都重新读 Session: 用户在等待期间可能改了模型/轮数, 以"发出去那一刻"为准更符合直觉
                // ★ 免费通道优先判定: 它不需要本机 Key, 只要有账号令牌。
                //   三条通道共用下面同一套 OpenAI 兼容调用, 只有 base/key/model 不同 ——
                //   免费通道的 base 指向中转服务, key 换成账号令牌 (见 FreeAi 类注释)。
                val useFree = Session.aiUseFree && FreeAi.canUse()
                val useKey = Session.hasAiKey() && !Session.aiUseOfficial && !useFree
                val chanBase = if (useFree) FreeAi.baseUrl(ctx) else Session.aiApiBase
                val chanKey = if (useFree) (FreeAi.token() ?: "") else Session.aiApiKey
                val chanModel = if (useFree) "CyanFree" else Session.aiModel
                // ★ 埋点 (2026-10-07): AI 任务是最常被追问"为什么没反应"的地方,
                //   开始 / 结束 / 异常三段都要留痕, 才能判断是"没发出去"还是"发出去了但失败"。
                DebugStore.logI(
                    "Ai",
                    "任务开始 · ${if (useFree) "限时免费" else if (useKey) "自备Key" else "官方通道"} · " +
                        "模型=${if (useFree) chanModel else if (useKey) Session.aiModel else "官方默认"} · " +
                        "联网=$searchOn · 专家=$cmdExpert · 轮数=${Session.aiToolRounds}",
                )
                // ★ 2026-10-05: 工具闸由**模式**决定, 在 send() 入口就算好了 (见那里的注释) ——
                //   这里是闭包捕获, 等待期间用户改设置不会影响已经发出去的这一轮。
                if (useKey || useFree) {
                    // ---- 自备 API 直连 / 限时免费中转: 都是 OpenAI 兼容, 共用这套调用 ----
                    val r = AiClient.chat(
                        history = history,
                        base = chanBase,
                        key = chanKey,
                        model = chanModel,
                        think = if (useFree) ThinkLevel.AUTO else Session.effectiveThink(),
                        searchTool = searchOn,
                        cmdExpert = cmdExpert,
                        syntaxCheck = syntaxCheck,
                        recheck = recheck,
                        maxToolRounds = Session.aiToolRounds,
                        // ★ 流式: 增量走 pushDelta 节流后刷 UI。回调在 IO 线程, 见那里的注释。
                        onStreamStart = {
                            // ★ 一轮开始, 正文与**思考**两个缓冲都要清 —— 见 [thinks] 的注释
                            synchronized(buf) { buf.setLength(0) }
                            synchronized(thinkBuf) { thinkBuf.setLength(0) }
                            ui.post {
                                if (alive.get()) {
                                    streams[convId] = ""
                                    thinks[convId] = ""
                                }
                            }
                        },
                        onDelta = { d -> pushDelta(d) },
                        // ★ 思考增量 → 独立缓冲, 界面上是"正在思考"那块实时在长
                        onThinkDelta = { d -> pushThink(d) },
                        onEvent = { e -> pushEvent(e) },
                        // ★ 结构化工具事件 → 时间线。upsertEvent 内部自己切主线程(工具执行器在 IO 上)
                        onToolEvent = { ev -> upsertEvent(convId, ev) },
                        // ★ 协作式停止: 用户点「停止生成」→ stopFlag 翻真 → 读流循环立刻退出
                        shouldStop = { stopFlag.get() },
                    )
                    // 耗时按**整轮任务**算(含工具调用), 和原来一致
                    val spent = SystemClock.uptimeMillis() - start
                    val body = buildString {
                        append(r.content)
                        if (r.note.isNotBlank()) append("\n\n（").append(r.note).append("）")
                    }
                    AiConvStore.setMessages(
                        convId,
                        history + mergeVariants(
                            prevAssistant,
                            UiMsg(
                                "assistant", body,
                                think = r.reasoning,
                                thinkMs = if (r.reasoning.isNotBlank()) spent else 0L,
                                searchQueries = r.searchQueries,
                                wikiQueries = r.wikiQueries,
                                checkCount = r.checkCount,
                                rechecked = r.rechecked,
                                recheckIssueCount = r.recheckIssueCount,
                                recheckIssues = r.recheckIssues,
                                // ★ 把这一轮的工具时间线**随消息存下来** (2026-10-05) ——
                                //   内存里的 [toolEvents] 马上会在 finally 里被清掉, 不存的话
                                //   回答落地后"刚才查了什么"就再也看不到了(见 UiMsg.toolTimeline)。
                                toolTimeline = toolEvents[convId].orEmpty(),
                            ),
                            prevUserText = prevUserText,
                            newUserText = history.lastOrNull { it.role == "user" }?.content.orEmpty(),
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
                            history + mergeVariants(
                                prevAssistant,
                                UiMsg(
                                    "assistant", reply,
                                    turnId = r.turnId,
                                    searchQueries = r.searchQueries,
                                    wikiQueries = r.wikiQueries,
                                    checkCount = r.checkCount,
                                    rechecked = r.rechecked,
                                    recheckIssueCount = r.recheckIssueCount,
                                    recheckIssues = r.recheckIssues,
                                    // ★ 同上: 官方通道的过程也随消息存下来
                                    toolTimeline = toolEvents[convId].orEmpty(),
                                ),
                                prevUserText = prevUserText,
                                newUserText = history.lastOrNull { it.role == "user" }?.content.orEmpty(),
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
                // ★★ 用户点了「停止生成」→ 把**已经流出来的那半截**存下来 (2026-10-05)。
                //
                // 为什么要存: DeepSeek / WorkBuddy 点停止都会保留已生成的内容, 直接丢掉
                // 等于让用户白等一场, 而且下一轮对话的历史里也看不到 AI 刚才说了什么。
                //
                // ⚠️ 先 `alive.set(false)`: 让**还没执行**的那些增量 post 失效, 否则它们
                //   会在我们读完 `streams[convId]` 之后才把内容补进去, 那截就漏掉了。
                alive.set(false)
                val partial = (streams[convId].orEmpty() + synchronized(buf) { buf.toString() }).trim()
                AiConvStore.setMessages(
                    convId,
                    history + mergeVariants(
                        prevAssistant,
                        UiMsg(
                            "assistant",
                            partial.ifBlank { "（已停止生成）" },
                            // 思考也一并留下 —— 用户很可能正是看到思考跑偏才按的停止
                            think = thinks[convId].orEmpty(),
                            toolTimeline = toolEvents[convId].orEmpty(),
                        ),
                        prevUserText = prevUserText,
                        newUserText = history.lastOrNull { it.role == "user" }?.content.orEmpty(),
                    ),
                )
                // 取消必须继续传播(safeApi 也是这个约定), 但收尾照做 —— 见 finally
                DebugStore.logI("Ai", "生成被用户停止, 已保留已生成的 ${partial.length} 字符")
                throw e
            } catch (e: Exception) {
                // ★ E 级: 走到这里说明这一轮**彻底失败**, 用户看到的是"回答报错"
                DebugStore.logE("Ai", "任务异常: ${e.javaClass.simpleName}: ${e.message ?: ""}")
                AiConvStore.setMessages(convId, history + UiMsg("assistant", errText(e), err = true))
            } finally {
                hintJob.cancel()
                // ★ 顺序: 先让**还在路上**的增量投递失效, 再清状态。
                //   反过来的话, 那几帧延迟到达的 post 会把刚清空的气泡又写脏,
                //   表现为"回答完了气泡里还挂着半句正文"。
                alive.set(false)
                synchronized(buf) { buf.setLength(0) }
                synchronized(thinkBuf) { thinkBuf.setLength(0) }
                running.remove(convId)
                phases.remove(convId)
                streams.remove(convId)
                thinks.remove(convId)
                events.remove(convId)
                toolEvents.remove(convId)
                // ★ 停止相关账本也要清 —— 不清的话下一条消息的 `stop()` 会去 cancel
                //   一个**已经结束**的 Job(无害), 但 `stops` 会一直泄漏条目。
                stops.remove(convId)
                jobs.remove(convId)
                tick++
                // ⚠️ 还有别的会话在跑就**不能停服务** —— 停了那个任务的保活就没了。
                //   完成通知照补: 用户可能已经切出去了, 就是想知道哪条好了。
                DebugStore.logI("Ai", "任务收尾 · 成功=$ok · 仍有 ${running.size} 个会话在跑")
                AiTaskService.finish(ctx, ok, running.isNotEmpty())
            }
        }
        // ★ 登记 Job 之后再 start —— 见上面 LAZY 那段的注释
        jobs[convId] = job
        job.start()
    }

    /**
     * ★★ 把「本次新答案」并进旧回答的**版本表** (2026-10-05) —— 见 [UiMsg.variants]。
     *
     * 用户原话:「修改的那个功能，像 deepseek 一样有个 1/2，2/2 一样表示修改的次数还有每次的内容」。
     *
     * 行为:
     *  - [prev] 为 null (普通发送) → 原样返回 [fresh], 不产生版本表;
     *  - [prev] 已有版本表 → 追加新版本, 并把**当前版**指向新的那一版;
     *  - [prev] 还没有版本表 (第一次重新生成/修改) → 先把它自己抽成"第 1 版", 再追加新版本。
     *
     * ⚠️ `variantIndex` 必须指向**新的那一版**: 用户刚重发完, 要看到的是新答案而不是旧答案。
     */
    private fun mergeVariants(
        prev: UiMsg?,
        fresh: UiMsg,
        prevUserText: String,
        newUserText: String,
    ): UiMsg {
        if (prev == null || prev.role != "assistant") return fresh
        val old = prev.variants.orEmpty().ifEmpty { listOf(prev.toVariant(prevUserText)) }
        val all = old + fresh.toVariant(newUserText)
        return fresh.copy(variants = all, variantIndex = all.lastIndex)
    }
}
