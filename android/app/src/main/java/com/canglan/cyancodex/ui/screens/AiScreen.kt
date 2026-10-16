package com.canglan.cyancodex.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.ThumbDown
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material.icons.filled.TravelExplore
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.canglan.cyancodex.ai.AiAttach
import com.canglan.cyancodex.ai.AiConvStore
import com.canglan.cyancodex.ai.AiSearch
import com.canglan.cyancodex.ai.AiTaskRunner
import com.canglan.cyancodex.ai.ThinkLevel
import com.canglan.cyancodex.data.AiMode
import com.canglan.cyancodex.data.AiToolEvent
import com.canglan.cyancodex.data.Attachment
import com.canglan.cyancodex.data.FeedbackReq
import com.canglan.cyancodex.data.FreeAi
import com.canglan.cyancodex.data.UiMsg
import com.canglan.cyancodex.data.ApiClient
import com.canglan.cyancodex.data.Session
import com.canglan.cyancodex.ui.LocalGlassBottomInset
import com.canglan.cyancodex.ui.AppState
import com.canglan.cyancodex.ui.Routes
import com.canglan.cyancodex.ui.components.AppTopBar
import com.canglan.cyancodex.ui.components.GlassActionButton
import com.canglan.cyancodex.ui.components.GlassIconButton
import com.canglan.cyancodex.ui.components.GlassSurface
import com.canglan.cyancodex.ui.components.MarkdownBlocks
import com.canglan.cyancodex.ui.components.MarkdownText
import com.canglan.cyancodex.ui.components.TopBarAction
import com.canglan.cyancodex.ui.components.copyToClipboard
import com.canglan.cyancodex.ui.components.safeApi
import com.canglan.cyancodex.ui.liquidGlassActive
import com.canglan.cyancodex.ui.liquidglass.LiquidButton
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun AiScreen(nav: NavHostController) {
    val scope = rememberCoroutineScope()
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val listState = rememberLazyListState()

    // ---- 多对话 (2026-09-27) ----
    // 会话列表 + 当前会话, 落盘在 ai.AiConvStore。
    // 老版本只有一个全局 ai_messages; 首次访问 AiConvStore 时会把它整体包成一条会话,
    // 所以升级后**历史不丢**。ensureCurrent() 必须在 list() 之前调用 ——
    // 会话被删光时它会先补一条, 否则后面 cur 会是 null。
    // ⚠️ 原来这里有个 `val gson = Gson()` 给官方通道手拼 messages 用 —— 2026-10-01 起
    //    官方通道的循环搬进 AiProxyAgent, 这里不再需要。
    var curId by remember { mutableStateOf(AiConvStore.ensureCurrent().id) }
    var convs by remember { mutableStateOf(AiConvStore.list()) }
    val cur = convs.firstOrNull { it.id == curId } ?: convs.firstOrNull()
    val messages = cur?.messages ?: emptyList()
    // 对话记录 —— 2026-10-01 用户要求改成**侧边栏**(原来是居中 AlertDialog)。
    // 直接用 drawerState 当唯一状态, 不再另设一个 showConvs 布尔:
    // 两份状态迟早不同步(手势滑关抽屉时布尔还是 true)。
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    fun setMessages(new: List<UiMsg>) {
        val id = cur?.id ?: return
        AiConvStore.setMessages(id, new)
        convs = AiConvStore.list()
    }

    var input by remember { mutableStateOf("") }
    // ★★ 待发送的附件 (2026-10-05) —— 用户原话:「对了，顺便给AI加一个上传文件功能」。
    //
    // ⛔ 这是一份**草稿态**: 发送成功后才随消息落盘, 中途切换会话要清空(见下面 LaunchedEffect)。
    //    不清的话会把 A 会话选的附件带到 B 会话, 一发出去就是错的文件。
    var attachDraft by remember { mutableStateOf<List<Attachment>>(emptyList()) }
    // ★★ 「修改」待重发时暂存的**旧回答 + 旧提问** (2026-10-05) —— 见 [UiMsg.variants]。
    //
    // 为什么不在点「修改」时就把旧的一轮丢掉: 用户原话「像 deepseek 一样有个 1/2、2/2
    // 一样表示修改的次数还有每次的内容」—— 旧的那一版要能翻回去看。
    // 所以点「修改」只把**界面**截断到那条提问之前(和原来一样), 旧回答暂存在这里,
    // 重发成功时由 AiTaskRunner 作为第 1 版并回去。
    var pendingPrev by remember { mutableStateOf<UiMsg?>(null) }
    var pendingPrevUser by remember { mutableStateOf("") }
    // 文件选择器 —— SAF 的 OpenDocument: 不需要任何存储权限, 拿到的是临时可读 Uri。
    // ★ 用 OpenDocument 而不是 GetContent: 前者能在"最近文件/网盘"里稳定取到
    //   display name 与 size(OpenableColumns), 这正是要在气泡上显示的东西。
    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult      // 用户取消
        if (attachDraft.size >= AiAttach.MAX_FILES) {
            android.widget.Toast.makeText(
                ctx, "最多一次上传 ${AiAttach.MAX_FILES} 个文件", android.widget.Toast.LENGTH_SHORT
            ).show()
            return@rememberLauncherForActivityResult
        }
        // ⛔ 读文件是 IO —— 但这里读的是小文件(≤60KB)且是用户刚点完的交互路径,
        //   几十毫秒内完成; 放主线程比"切线程再回主线程"少一层竞态(比如用户连着点两次)。
        val r = AiAttach.read(ctx.contentResolver, uri)
        val err = r.error
        if (err != null) {
            android.widget.Toast.makeText(ctx, err, android.widget.Toast.LENGTH_LONG).show()
        } else {
            r.ok?.let { attachDraft = attachDraft + it }
        }
    }
    // ★ 切会话要清空输入框 (2026-10-02 用户报的 bug: "切对话底部输入框的文字不会清空")。
    //   不清的话, 在上一条对话里打了一半的字会跟着你进下一条 —— 一按发送就发错地方了,
    //   而且输入框里明明有字, 用户会以为"这条对话里我说过这句话"。
    //   ★ 2026-10-05: 附件草稿同理 —— 必须一起清, 否则会把上条会话选的文件发到下条去。
    LaunchedEffect(curId) { input = ""; attachDraft = emptyList() }
    // ⚠️ 「是否正在回答」与「气泡上那行字」**不在这里** —— 2026-10-02 起搬到了
    //    [AiTaskRunner]。原因: 它们是 `remember` 的, Activity 一销毁就没了,
    //    而请求现在**跨 Activity 生命周期**继续跑 —— 状态必须比界面活得久。
    //    读法: `AiTaskRunner.isBusyFor(id)` / `AiTaskRunner.phaseFor(id)`。
    //    ★ 而且是**按会话**的 —— 可以同时跑好几条对话, 见 AiTaskRunner 的类注释。
    var unlocked by remember { mutableStateOf(true) }
    var points by remember { mutableStateOf(0) }
    var pointsNeeded by remember { mutableStateOf(200) }
    var statusMsg by remember { mutableStateOf("") }
    var showConfig by remember { mutableStateOf(false) }
    /** ★ 新建对话的模式选择弹层 (顶栏 + / 侧边栏「新对话」共用) */
    var showNewConv by remember { mutableStateOf(false) }
    /**
     * ★ 2026-10-05: **改当前会话模式**的弹层。
     *
     * 新建时选了模式, 但用户往往开着开着才发现选错了(比如在「通用对话」里问语法,
     * AI 不查百科也不核语法)。没有入口的话只能新建一条会话 —— 之前的上下文全丢。
     * 所以顶栏副标题上的模式徽标做成**可点**的, 点了重选, **原地改** 当前会话的 mode。
     */
    var showModeChange by remember { mutableStateOf(false) }
    /** 长按弹出的消息菜单挂在哪一条上 (-1 = 没开) */
    var menuIdx by remember { mutableStateOf(-1) }
    /** 「选择文本」模式挂在哪一条上 (-1 = 没开)。开了这条才套 SelectionContainer */
    var selectIdx by remember { mutableStateOf(-1) }

    suspend fun refreshStatus() {
        if (AppState.token == null) return
        val r = safeApi { ApiClient.get().aiStatus() }
        if (r.ok) {
            unlocked = r.data?.unlocked ?: true
            points = r.data?.points ?: 0
            pointsNeeded = r.data?.pointsNeeded ?: 200
        }
    }

    LaunchedEffect(Unit) { refreshStatus() }

    // ---- 托管任务收尾 (2026-10-02) ----
    // 请求现在跑在 AiTaskRunner 上、跨 Activity 生命周期 —— 完成时界面可能压根不存在,
    // 所以**不能**再像以前那样在协程里直接改 UI 状态。改成订阅 tick:
    //   tick 变化 → 重读会话列表(拿到刚落盘的回答) → 滚到底。
    // ⚠️ tick == 0 表示"一次都还没跑过": 此时只重读列表、不滚动 ——
    //    否则第一次进页面会被强行拽到底部, 看不到历史。
    /**
     * 视口里**最后一项的底边**超出视口底边多少像素。> 0 = 内容还在屏幕下方。
     *
     * ⛔⛔ **这里就是 2026-10-05 那个"输出时锁在最上面、滑不下去"的根因。**
     *
     * 原来 [scrollToBottom] 用的是 `listState.scrollToItem(最后一项)`。而
     * `scrollToItem(i)` 的语义是"把第 i 项的**顶边**对齐视口顶边"——
     * 流式气泡常常比屏幕还高, 于是它把气泡的**开头**钉在屏幕顶部, 新吐出来的字
     * 全部落在屏幕下方。用户看到的就是"一直锁在最上面, 滑不下去";
     * 更要命的是流式每 80ms 触发一次, 手动往上翻也会立刻被拽回去。
     *
     * 正解是**按差值滚动**: 算出"内容底边超出视口底边多少", 就往下滚多少 ——
     * 这样无论气泡多高, 最新吐出的字始终贴在屏幕底部。
     */
    val bottomOvershoot: () -> Int = {
        val info = listState.layoutInfo
        val last = info.visibleItemsInfo.lastOrNull()
        if (last == null) 0
        else (last.offset + last.size) - (info.viewportEndOffset - info.afterContentPadding)
    }

    /**
     * 用户是不是**贴着底部**在看。差 ≤ 24px 就算贴底(手指抖动 / 亚像素误差)。
     *
     * 用来回答"要不要自动跟随" —— 用户手动往上翻看前面的内容时, 不该再把他拽回底部。
     */
    val isAtBottom: () -> Boolean = { bottomOvershoot() <= 24 }

    /**
     * 滚到底。**suspend** —— 内部要等一帧 + 调 suspend 的 `scrollBy`。
     *
     * ⚠️ 先 `withFrameNanos` 等布局跑完再读 `layoutInfo`: 否则刚发消息那一帧列表
     *    还没测量完, 读到的是旧值, 滚不到位。
     * ⛔ **不要**改成 `scrollToItem(Int.MAX_VALUE)` —— 实测会 ANR, 见下面的长注释。
     * ⛔ **也不要**退回 `scrollToItem(最后一项)` —— 那正是上面 [bottomOvershoot] 说的病根。
     */
    val scrollToBottom: suspend () -> Unit = {
        withFrameNanos { }
        val d = bottomOvershoot()
        if (d > 0) runCatching { listState.scrollBy(d.toFloat()) }
    }

    /**
     * 「回到底部」按钮的显隐。与 [isAtBottom] 同源, 但要一个**可观察的布尔量**
     * 才能驱动重组 —— 函数每次调用返回新值是不会触发重组的。
     */
    var atBottom by remember { mutableStateOf(true) }
    LaunchedEffect(listState) {
        snapshotFlow { isAtBottom() }.collect { atBottom = it }
    }

    LaunchedEffect(AiTaskRunner.tick) {
        convs = AiConvStore.list()
    }

    // =================================================================================
    //  「钉在底部」—— 2026-10-02 重写
    // =================================================================================
    // ⚠️⚠️ **必须放在页面级, 不能挂在气泡 item 里。**
    //   LazyColumn **只组合视口内的 item** —— 气泡一旦在视口外就永远不会组合,
    //   挂在气泡里的 `LaunchedEffect` 也就永远不会执行。而"让气泡进入视口"正是它要做的事,
    //   于是永远做不到。典型的"用结果去触发产生结果的原因"。
    //   (第一版就是这么写的, 实测: 消息发出去了、回答也落盘了, 但屏幕上什么都没有。)
    //
    // ⛔⛔ **绝对不要用 `scrollToItem(Int.MAX_VALUE)` 当"滚到底"的偷懒写法** ——
    //   实测**直接把主线程卡死 5 秒以上, 触发 ANR**
    //   (`Input dispatching timed out … Waited 5001ms for MotionEvent … pointers=[0: (788.0, 2240.0)]`)。
    //   那个 index 会被当成"第 21 亿项"去做测量, 不是想象中"自动夹到最后一页"。
    //   正解: 先等一帧让列表完成布局, 再用 `layoutInfo.totalItemsCount - 1` —— 一定有效。
    //
    // ⚠️ 用 `snapshotFlow` 而不是 `LaunchedEffect(streaming)` 当 key:
    //   前者只在 snapshot 变化时**执行副作用**, 不会让整个 AiScreen 每 80ms 重组一次。
    LaunchedEffect(curId) {
        snapshotFlow {
            AiTaskRunner.isBusyFor(curId) to AiTaskRunner.streamFor(curId)
        }.collect { (busy, _) ->
            // 任务一开始就滚一次(把"思考中"气泡带进视口), 之后流式每 80ms 变一次就再滚一次,
            // 于是"字一直在蹦, 视图一直在底" ✓
            //
            // ★★ 2026-10-05: 加 `isAtBottom()` 守卫 —— 用户**手动往上翻**看前面内容时,
            //   不能再每 80ms 把他拽回底部。这是所有成熟对话客户端的标准行为
            //   (WorkBuddy / DeepSeek 都是: 你一往上滑, 自动跟随立刻停)。
            //
            // ⚠️ 这里**不能**用上面那个 `atBottom` 状态变量: `LaunchedEffect(curId)` 不会
            //   因为 `atBottom` 变化而重启, 闭包捕获到的是启动那一刻的旧值 ——
            //   表现为"守卫恒为启动时的值", 等于白加。所以每次**现读** [isAtBottom]。
            if (busy && isAtBottom()) scrollToBottom()
        }
    }

    // 消息条数变化 → 滚到底。收尾后正式气泡落盘、以及用户自己发消息, 都走这条。
    LaunchedEffect(curId, messages.size) {
        if (messages.isEmpty()) return@LaunchedEffect
        scrollToBottom()
    }

    // 官方通道的余额由 AiTaskRunner 持有(它比界面活得久), 这里同步回本地状态给顶栏用
    LaunchedEffect(AiTaskRunner.balance) {
        AiTaskRunner.balance?.let { points = it }
    }

    // ⚠️ 用 hasAiKey() 而不是 Session.aiApiKey.isNotBlank(): 后者要过 Keystore 解密,
    //    而这一行在组合期执行, 每帧解密一次会卡。真正要用 Key 是在发送时。
    //
    // ★ 还要看 aiUseOfficial: 用户可以在设置里显式选「用官方 AI(消耗积分)」——
    //   配了自备 Key 也能切回去。以前判断条件只有"有没有 Key", 一旦配了就再也回不到官方。
    //
    // ★★ 2026-10-16 再加「限时免费」通道: 它同样不走积分、不用本机 Key(用的是账号令牌),
    //    所以对**发送门槛**而言和自备 Key 是一回事 —— 下面统一用 hasKey 表示
    //    "走非官方通道, 可以直接发"。真正的通道选择在 AiTaskRunner 里再判一次。
    val useFree = Session.aiUseFree && FreeAi.canUse()
    val hasKey = (Session.hasAiKey() && !Session.aiUseOfficial) || useFree
    // 「深度思考」只在自备 Key 时才有意义: 免费通道走服务端中转, 思考参数不透传(见服务端白名单)。
    val thinkReady = hasKey && !useFree

    // 「深度思考」开关 —— 放在输入框里 (对齐 DeepSeek 客户端: 开关随手可切, 不埋在设置里)。
    // 只在配了自备 API 时才有意义: 平台 AI 走服务器代理, 发不了思考参数。
    var thinkOn by remember { mutableStateOf(Session.aiThinkOn) }

    // 「智能搜索」开关 —— 同样在输入框里。**没配搜索引擎时整个 chip 不显示**:
    // 摆一个点了只会报错的按钮不如不摆。
    var searchOn by remember { mutableStateOf(Session.aiSearchOn) }
    val searchReady = AiSearch.isConfigured()

    // ★★ 「会话模式」(2026-10-05) —— 用户要求「类似 WorkBuddy 一样有个编写代码和检查语法的
    //   选项在**新建对话的时候**选择」。
    //
    //   原来「指令专家 / 语法核对」是输入框上方的两个胶囊开关, 且存在**全局** Session 里。
    //   问题: ① 4 个胶囊在窄屏折行(用户明确不要); ② 能力边界是**这条会话是什么**,
    //   不该是会话进行中随手切的开关 —— 所以挪到新建对话时选, 并**按会话**存。
    //
    //   现在这个值由**当前会话的 mode** 决定(cur.mode), 不再读全局 Session。
    //   见 data/AiMode.kt。
    val convMode = AiMode.of(cur?.mode)
    // 「指令专家」= 挂 MC 百科 + 语法核对两个工具。模式说了算。
    val cmdExpertOn = AiMode.cmdExpert(convMode)

    // 对话记录侧边栏 (2026-10-01 用户要求: "要改的是侧边栏, 就是历史聊天记录")。
    // ★ 包在 Scaffold **外面** —— 放里面会被顶栏/底栏压住, 滑不出全高。
    // ★ gesturesEnabled 只在抽屉**已打开**时开: 常开的话会跟消息长按、列表横滑抢同一个手势,
    //   用户滑一下列表就把抽屉拖出来了。
    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = drawerState.isOpen,
        drawerContent = {
            AiConvDrawer(
                convs = convs,
                currentId = cur?.id.orEmpty(),
                onPick = { id ->
                    AiConvStore.setCurrent(id)
                    curId = id
                    scope.launch { drawerState.close() }
                },
                onNew = {
                    // ★ 2026-10-05 起也先选模式 (与顶栏 + 一致)
                    showNewConv = true
                },
                onRename = { id, title ->
                    AiConvStore.rename(id, title)
                    convs = AiConvStore.list()
                },
                onDelete = { id ->
                    val next = AiConvStore.delete(id)
                    convs = AiConvStore.list()
                    curId = if (next.isBlank()) AiConvStore.ensureCurrent().id else next
                    convs = AiConvStore.list()
                },
                onServerHistory = {
                    scope.launch { drawerState.close() }
                    if (AppState.token == null) nav.navigate(Routes.LOGIN) else nav.navigate(Routes.AI_HISTORY)
                },
                onClose = { scope.launch { drawerState.close() } },
            )
        },
    ) {
    Scaffold(
        topBar = {
            // 主标题固定「AI Agent」—— 这是模块名, 不该被对话名顶掉。
            // 2026-10-01 用户反馈: "aiagent界面顶部应该显示 AI Agent 的, 现在变成对话标题了"。
            // 上一版把标题换成对话名(想对齐 DeepSeek 客户端), 但那一页的标题位属于**产品/模块**,
            // 不是会话 —— 换掉之后用户就找不到"我现在在哪个功能里"了。
            // 对话名降为副标题压在主标题下面: 信息不丢, 主标题也稳。
            // 占位标题(「新对话」)不出副标题 —— 顶栏挂一行"新对话"没有信息量。
            //
            // ★★ 2026-10-05 补充(用户要「新建对话时选做什么」) —— 选了模式之后**必须看得见**。
            //   否则用户在「指令编写」会话里问"为什么不查百科", 无从判断自己选的是哪个模式。
            //   做法: 复用副标题槽位拼一个模式徽标 —— 会话名(如果有) + 「· 指令编写」。
            //   默认模式(指令编写 = DEFAULT)在**新建的空会话**上不标, 免得每个新会话都顶一行
            //   同款标签; 但一旦用户在别的模式的会话里, 或者会话已有名字, 就标出来。
            //   ⛔ 不新造顶栏组件、不动 AppTopBar 签名 —— 41 个页面共用它, 动它风险不成比例。
            val convTitle = cur?.title?.takeIf {
                it.isNotBlank() && it != AiConvStore.DEFAULT_TITLE
            }
            // 模式徽标: 只要不是默认模式就标; 默认模式仅在**已有会话名**时顺便带上, 让用户确认。
            val modeLabel = AiMode.label(AiMode.of(cur?.mode))
            val modeBadge = when {
                AiMode.of(cur?.mode) != AiMode.DEFAULT -> modeLabel
                convTitle != null -> modeLabel
                else -> null
            }
            val subtitle = when {
                convTitle != null && modeBadge != null -> "$convTitle · $modeBadge"
                convTitle != null -> convTitle
                else -> modeBadge
            }
            AppTopBar(
                title = "AI Agent",
                subtitle = subtitle,
                icon = Icons.Default.AutoAwesome,
                // 副标题(会话名 / 模式徽标)可点 —— 点开就是「改这条会话的模式」。
                // 只有真的有会话可选时才让它可以点(否则点了弹个"改谁的"很怪)。
                onSubtitleClick = if (cur != null) ({ showModeChange = true }) else null,
                // 对话记录侧边栏的入口 —— 2026-10-01 用户要求: "那个聊天记录应该改成一个在
                // 左边的三条横杠的侧边栏, 而不是继续像这个按钮一样上"。
                // 之前它挂在右侧 actions 里(第 2 个圆钮, Icons.Default.Forum), 用户觉得歪;
                // 而且开侧栏本来就该在左边。现在走顶栏 navigationIcon 槽(与返回键同一位置)。
                navIcon = Icons.Default.Menu,
                onNav = { scope.launch { drawerState.open() } },
                actions = {
                // 新对话: ★ 2026-10-05 起先弹**模式选择**(通用对话/指令编写/语法核对),
                //   不再直接建一条空会话 —— 用户要求"类似 WorkBuddy 在新建对话时选择"。
                //   (原版是**清空**当前会话 —— 上一轮永久丢失)
                TopBarAction(onClick = {
                    showNewConv = true
                }) { Icon(Icons.Default.Add, "新对话") }
                TopBarAction(onClick = { showConfig = true }) { Icon(Icons.Default.Settings, "AI 设置") }
            })
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            // 状态栏
            //
            // ⛔⛔ **不要用 `Surface(color = surfaceVariant)`**（2026-10-05 用户报
            //   「在液态玻璃下有一行空白的」）—— 那是一个**不透明**的实心色条。
            //   液态玻璃模式下顶栏是通透的模糊玻璃，正下方却贴一条平涂的实心灰白，
            //   文字又小又淡（labelMedium + onSurfaceVariant），远看就是"一条空白"。
            //   这是全 App 唯一一处直接挂在顶栏下的不透明条。
            //
            //   改法：**去掉底色**，文字直接浮在页面背景上；玻璃模式下再给它一点
            //   顶部内边距，让它与玻璃顶栏之间留出呼吸感（而不是紧贴成第二条"栏"）。
            //   判据：只要 `liquidGlassActive` 就不画底 —— 与顶栏的玻璃判据保持一致。
            val statusStripBg = if (liquidGlassActive) Color.Transparent
                                else MaterialTheme.colorScheme.surfaceVariant
            Surface(color = statusStripBg, modifier = Modifier.fillMaxWidth()) {
                Row(
                    Modifier
                        .padding(horizontal = 16.dp)
                        // 玻璃模式下顶栏自带高度与圆角，这里补 6dp 让状态文字不贴顶栏边
                        .padding(top = if (liquidGlassActive) 6.dp else 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val ok = hasKey || (unlocked && points >= 5)
                    val (statusIcon, statusText) = when {
                        hasKey -> {
                            // 2.0.0：状态栏显示「生效模型 + 域名」（官方更新日志）
                            val host = Session.aiApiBase.substringAfter("//").substringBefore("/")
                            val extra = if (thinkOn) " · 深度思考${ThinkLevel.of(Session.aiThinkLevel).label}" else ""
                            Icons.Default.CheckCircle to "自备 API · ${Session.aiModel} @ $host$extra"
                        }
                        unlocked -> Icons.Default.CheckCircle to "平台 AI · 基础5积分+按Tokens计费(余额 $points 分)"
                        AppState.token != null -> Icons.Default.Lock to "AI 未开放 (当前 $points 分)"
                        else -> Icons.Default.Lock to "登录后可用 AI"
                    }
                    Icon(statusIcon, null, tint = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        statusText,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            if (statusMsg.isNotBlank()) {
                Text(statusMsg, color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp))
            }

            if (AppState.token == null && !hasKey) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.Lock, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("登录后可用积分解锁 AI, 或直接配置自备 API", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(10.dp))
                        GlassActionButton(
                            filled = true, onClick = { nav.navigate(Routes.LOGIN) }) { Text("去登录") }                    }
                }
            } else {
                // 输入条玻璃化: 它要**浮在**聊天列表之上, 所以列表必须一路铺到它下面。
                // 玻璃采的是它下面的真实像素; 若挂在 Scaffold.bottomBar 里, Scaffold 会把 body 顶上去,
                // 玻璃正下方只剩 Scaffold 的纯色底 —— 纯色模糊后还是纯色, 折射也无从产生位移,
                // 玻璃就退化成"一块半透明矩形"。与底部导航栏当初踩的是同一个坑。
                val glassInput = liquidGlassActive
                val inputBackdrop = rememberLayerBackdrop()
                var inputBarH by remember { mutableStateOf(0.dp) }
                val density = LocalDensity.current

                Box(Modifier.weight(1f).fillMaxWidth()) {

                // ★ 按会话判断 —— 别的会话在跑不该把这条的发送按钮变灰 (2026-10-02)
                // ★ 2026-10-05: 只挂附件(没打字)时也**可以发** —— 用户拖个文件进来就是要
                //   问"这文件啥意思", 逼他再打一行字是多余的门槛。所以条件改成
                //   「输入框有字 **或** 有附件」。
                //
                // ★★ 2026-10-05 再改: 用户原话「那个进行中的任务发送键不要始终灰色, 改成像
                //   workbuddy 等 agent 工具一样有个停止生成」。所以把"在跑"从 sendEnabled 里
                //   **摘出来单独判** —— 在跑时那个位置渲染的是**停止键**(不再是灰掉的发送键)。
                val busyNow = AiTaskRunner.isBusyFor(cur?.id ?: "")
                val canSend = (input.isNotBlank() || attachDraft.isNotEmpty()) && (hasKey || unlocked)
                val sendEnabled = !busyNow && canSend
                // ⚠️ `onPrimaryAction` 的赋值**必须放在 [doSend] 之后** ——
                //   Kotlin 的局部 val/lambda 不支持前向引用, 写在这里会 "Unresolved reference 'doSend'"。

                // 发送逻辑只写一份 —— 玻璃/非玻璃/重新生成三处共用, 免得改一处漏一处。
                //
                // ★ base 必须**显式传进来**: 「重新生成」要在截断后的历史上重发, 而
                //   setMessages 之后本轮组合期的 `messages` 还是旧快照 —— 直接读它会多出一条。
                //
                // ⚠️ 2026-10-02 起这里**只做两件事**: ① 把用户这条消息落盘(界面立刻能看到);
                //   ② 把任务交给 [AiTaskRunner]。请求本身不再跑在这个 Composable 的作用域里 ——
                //   那正是"一切后台就断"的根因, 详见 AiTaskRunner 的类注释。
                //
                // ★ 2026-10-05 附件: 附件的正文**拼进消息 content**(见 [AiAttach.composePrompt]),
                //   同时把附件元信息存进 UiMsg.attachments 供气泡渲染。为什么两条都做:
                //   · 拼进 content → 两条 AI 通道(自备直连 / 官方代理)不用改一行就都能看到文件;
                //   · 存 attachments → 气泡上能画出「📎 文件名 · 12.3 KB」, 用户看得见自己发了什么。
                fun send(
                    base: List<UiMsg>,
                    raw: String,
                    atts: List<Attachment> = emptyList(),
                    // ★★ 被本次重发替换掉的旧回答 / 旧提问 (2026-10-05) —— 「重新生成」与
                    //    「修改后重发」传, 普通发送传 null。见 [UiMsg.variants] 与
                    //    `AiTaskRunner.mergeVariants`。
                    prev: UiMsg? = null,
                    prevUser: String = "",
                ) {
                    val q = raw.trim()
                    // 没字也没附件 → 不发。有附件时允许空文本(会补一句默认引导语)。
                    if (q.isBlank() && atts.isEmpty()) return
                    val id = cur?.id ?: return
                    // ★ 只挡**这条会话**重复发。别的会话在跑照发不误 ——
                    //   原来是全局 `AiTaskRunner.busy`, 表现为"切到新对话发不出去"。
                    if (AiTaskRunner.isBusyFor(id)) return
                    // ★ 落盘的是**拼好的正文** —— 这样历史里带着文件内容, 后续轮次模型仍看得见;
                    //   附件的元信息单独留一份给气泡用。
                    val prompt = AiAttach.composePrompt(q, atts)
                    val withUser = base + UiMsg("user", prompt, attachments = atts)
                    setMessages(withUser)
                    // ---- 交给托管器 (2026-10-02) ----
                    // 原来这里是 `scope.launch { ... }` —— 请求跑在 composition 的 scope 上,
                    // Activity 一销毁(系统回收 / 从最近任务划掉)协程就被 cancel, 请求当场断。
                    // 现在跑在**进程级作用域**里: 界面没了它照跑, 结果直接写回 AiConvStore,
                    // 界面靠下面那个 `LaunchedEffect(AiTaskRunner.tick)` 收。
                    //
                    // 两个开关在这里就定下来传过去 —— 不要让它去读 Session:
                    // 用户等待期间改设置, 不该影响**已经发出去**的这一轮。
                    AiTaskRunner.send(
                        convId = id,
                        history = withUser,
                        // ★ 让 AI 自己搜。没配搜索引擎时这个开关也开不了
                        //   (输入框里那个胶囊压根不显示), 所以这里再判一次就够。
                        searchOn = searchOn && searchReady,
                        // ★ 会话模式: 它决定挂不挂 MC 百科 / 语法核对 (见 AiMode)。
                        //   传的是**这条会话**的模式, 不是全局开关。
                        mode = convMode,
                        prevAssistant = prev,
                        prevUserText = prevUser,
                    )
                }

                val doSend: () -> Unit = {
                    val q = input
                    val atts = attachDraft
                    // ★ 取走待重发的旧版本 (点过「修改」才会有) —— 取完必须清, 否则
                    //   下一条**无关**的新消息也会莫名其妙多出一个版本表。
                    val prev = pendingPrev
                    val prevUser = pendingPrevUser
                    pendingPrev = null
                    pendingPrevUser = ""
                    input = ""
                    attachDraft = emptyList()
                    send(messages, q, atts, prev, prevUser)
                }

                /**
                 * 点输入框右边那个键 —— **在跑 = 停止生成, 否则 = 发送**。
                 *
                 * 声明位置在 [doSend] **之后**不是随意的: 局部 lambda 不能前向引用。
                 * 这也顺带说明为什么它不是 `Button(onClick = ...)` 里内联的 ——
                 * 玻璃与非玻璃两条分支要共用同一个动作, 否则改了停止逻辑会漏掉一条通道。
                 */
                val onPrimaryAction: () -> Unit = {
                    if (busyNow) AiTaskRunner.stop(cur?.id ?: "") else if (sendEnabled) doSend()
                }

                // 「重新生成」= 同一句提问让 AI 再答一遍。★ 2026-10-05: **不再丢掉旧回答** ——
                //   它变成第 1 版, 新答案成为第 2 版, 气泡下方出现 `‹ 1/2 ›` 可来回翻。
                val doRegenerate: () -> Unit = {
                    val i = messages.indexOfLast { it.role == "user" }
                    if (i >= 0) {
                        val prev = messages.getOrNull(i + 1)?.takeIf { it.role == "assistant" }
                        send(messages.take(i), messages[i].content, emptyList(), prev, messages[i].content)
                    }
                }

                /**
                 * ★★ **切换回答版本** (2026-10-05) —— 点气泡下方 `‹ 1/2 ›` 时调。
                 *
                 * 用户原话:「像 deepseek 一样有个 1/2，2/2 一样表示修改的次数还有每次的内容」。
                 *
                 * ⚠️ 两件事要**一起**做, 少一件就不对:
                 *   ① 换掉这条 assistant 的正文/思考/工具过程 (见 [UiMsg.switchTo]);
                 *   ② 换掉**上面那条 user 气泡的文本** —— 「修改」产生的每一版提问可能不同,
                 *      只换答案不换提问, 用户会看到"问 A 却答了 B 的内容"。
                 */
                val doSwitchVariant: (Int, Int) -> Unit = { idx, target ->
                    val m = messages.getOrNull(idx)
                    if (m != null && m.variants.orEmpty().size > 1) {
                        val next = messages.toMutableList()
                        next[idx] = m.switchTo(target)
                        val vs = m.variants.orEmpty()
                        val uv = vs[target.coerceIn(0, vs.lastIndex)].userText
                        val ui = idx - 1
                        if (uv.isNotBlank() && ui >= 0 && messages[ui].role == "user") {
                            next[ui] = messages[ui].copy(content = uv)
                        }
                        setMessages(next)
                    }
                }
                // 聊天列表
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        // 玻璃开启时, 列表就是输入条的采样源。
                        // ⚠️ 采样源里绝不能含输入条自己 —— layerBackdrop 录的是节点全部绘制内容,
                        //    drawBackdrop 又取上一帧的纹理, 含了自己就会采到上一帧的自己(滚动时拖影)。
                        .then(if (glassInput) Modifier.layerBackdrop(inputBackdrop) else Modifier),
                    contentPadding = PaddingValues(
                        start = 12.dp, top = 12.dp, end = 12.dp,
                        // 输入行现在是浮在列表之上的, 末尾必须补出它的实测高度,
                        // 否则最后一条消息会被输入行盖住。两种外观都靠 inputBarH
                        // (玻璃分支的 inputBarH 里已含 LocalGlassBottomInset, 非玻璃分支不含, 但那里它本来就是 0)。
                        bottom = 12.dp + inputBarH + 8.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (messages.isEmpty()) {
                        // 空态对齐 DeepSeek 客户端: 居中大图标 + 一句问候, 而不是左上角一块
                        // 塞满说明文字的卡片。原来那版把"我是谁/能干什么/怎么解锁"全堆在一张卡里,
                        // 一进页面就是一大坨字, 像说明书不像对话框。
                        // fillParentMaxHeight 让这一项撑满视口, 居中才是**真居中**(不是列表顶部)。
                        item {
                            Column(
                                modifier = Modifier.fillParentMaxHeight(),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center,
                            ) {
                                Icon(
                                    Icons.Default.AutoAwesome, null,
                                    tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.9f),
                                    modifier = Modifier.size(56.dp),
                                )
                                Spacer(Modifier.height(14.dp))
                                Text("你好，我能帮什么忙吗？", style = MaterialTheme.typography.titleMedium)
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    "精通我的世界指令 · 点下面的例子直接开问",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Spacer(Modifier.height(20.dp))
                                // 示例直接可点 —— 点一下就是发一条, 省得用户自己组织语言。
                                // 竖排而不是横排: 每条长短不一, 横排要么挤成一团要么换行参差。
                                listOf(
                                    "生成一个无限 TNT 大炮的指令",
                                    "怎么做自动刷怪塔(命令方块方案)",
                                    "帮我写一个传送门切换系统",
                                    "解释 execute 指令",
                                ).forEach { ex ->
                                    Surface(
                                        onClick = { send(messages, ex) },
                                        shape = RoundedCornerShape(50),
                                        color = MaterialTheme.colorScheme.surfaceVariant,
                                        modifier = Modifier.padding(bottom = 8.dp),
                                    ) {
                                        Text(
                                            ex,
                                            style = MaterialTheme.typography.bodySmall,
                                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }
                    itemsIndexed(messages) { idx, m ->
                        val mine = m.role == "user"
                        // 「重新生成」只挂最后一条 AI 回复 —— 中间那条重生成没有意义,
                        // 而且会让后面几轮的上下文对不上。
                        val lastAi = !mine && idx == messages.lastIndex
                        Column(Modifier.fillMaxWidth(), horizontalAlignment = if (mine) Alignment.End else Alignment.Start) {
                            Box {
                                Card(
                                    shape = RoundedCornerShape(12.dp),
                                    colors = CardDefaults.cardColors(
                                        containerColor = if (mine) MaterialTheme.colorScheme.primaryContainer
                                        else MaterialTheme.colorScheme.surface),
                                    // 长按弹菜单 (复制 / 选择文本 / 修改)。
                                    // ⚠️ 已经在「选择文本」模式的那一条不挂长按 —— SelectionContainer
                                    //    自己也靠长按起选, 两个抢同一个手势只会互相打断。
                                    modifier = Modifier.combinedClickable(
                                        onClick = { if (selectIdx == idx) selectIdx = -1 },
                                        onLongClick = { if (selectIdx != idx) menuIdx = idx },
                                    ),
                                ) {
                                    Column(Modifier.padding(12.dp).widthIn(max = 280.dp)) {
                                        // ★★ 这条消息带的附件 (2026-10-05) —— 只读展示, 不可移除。
                                        //   露出来是必要的: 用户发完还想确认"我到底发了哪个文件",
                                        //   尤其文件名相似(help.mcfunction / help2.mcfunction)时。
                                        //   ⚠️ 一律 `.orEmpty()`: 老会话 JSON 里没这个键, Gson 反射成 null。
                                        val atts = m.attachments.orEmpty()
                                        if (mine && atts.isNotEmpty()) {
                                            Column(
                                                verticalArrangement = Arrangement.spacedBy(4.dp),
                                                modifier = Modifier.padding(bottom = 8.dp),
                                            ) {
                                                atts.forEach { a ->
                                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                                        Icon(Icons.Default.AttachFile, null,
                                                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                                            modifier = Modifier.size(13.dp))
                                                        Spacer(Modifier.width(5.dp))
                                                        Text(
                                                            a.name + AiAttach.humanSize(a.size).let { if (it.isBlank()) "" else " · $it" } +
                                                                if (a.truncated) " ·已截断" else "",
                                                            style = MaterialTheme.typography.labelSmall,
                                                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                                                            maxLines = 1,
                                                            overflow = TextOverflow.Ellipsis,
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                        // 思考过程: 折叠展示。直连自备 API 时上游会返回 reasoning_content,
                                        // 塞进正文会把答案淹掉, 所以单独一条可折叠的灰条。
                                        // ⚠️ 一律 `.orEmpty()`: 老会话 JSON 里没有这个键, Gson 反射出来是 null。
                                        val think = m.think.orEmpty()
                                        if (!mine && think.isNotBlank()) {
                                            var open by remember(think) { mutableStateOf(false) }
                                            Surface(
                                                color = MaterialTheme.colorScheme.surfaceVariant,
                                                shape = RoundedCornerShape(8.dp),
                                                modifier = Modifier.fillMaxWidth()
                                                    .clickable { open = !open },
                                            ) {
                                                Column(Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
                                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                                        Icon(
                                                            if (open) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                                            null,
                                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                            modifier = Modifier.size(14.dp),
                                                        )
                                                        Spacer(Modifier.width(4.dp))
                                                        Text(
                                                            // 有耗时就说耗时 —— 用户对"等了多久"比对"多少字"敏感
                                                            if (m.thinkMs > 0)
                                                                "已思考（用时 ${(m.thinkMs + 500) / 1000} 秒）"
                                                            else "思考过程 · ${think.length} 字",
                                                            style = MaterialTheme.typography.labelSmall,
                                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                        )
                                                    }
                                                    MarkdownText(
                                                        think,
                                                        style = MaterialTheme.typography.bodySmall,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                        maxLines = if (open) Int.MAX_VALUE else 3,
                                                        overflow = TextOverflow.Ellipsis,
                                                    )
                                                }
                                            }
                                            Spacer(Modifier.height(8.dp))
                                        }
                                        // ★★ 本轮**工具调用过程**回放 (2026-10-05) —— 用户原话:
                                        //   「我的过程指的是像 workbuddy 或者 ima 一样展示中间思考的内容
                                        //   **和调用的工具**」。流式期间那份实时时间线收尾就清了, 这里把
                                        //   随消息落盘的那份(`m.toolTimeline`)折叠展开, 回头看也能查。
                                        //   ⚠️ 默认**收起**: 过程是"想细看才看"的东西, 摊开会喧宾夺主;
                                        //      而下面那几行汇总(智能搜索/查阅百科/核对语法)已经给了概览。
                                        //   ⚠️ 老会话没有这个键 → `.orEmpty()`。
                                        val timeline = m.toolTimeline.orEmpty()
                                        if (!mine && timeline.isNotEmpty()) {
                                            var open by remember(timeline) { mutableStateOf(false) }
                                            Surface(
                                                color = MaterialTheme.colorScheme.surfaceVariant,
                                                shape = RoundedCornerShape(8.dp),
                                                modifier = Modifier.fillMaxWidth()
                                                    .clickable { open = !open },
                                            ) {
                                                Column(Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
                                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                                        Icon(
                                                            if (open) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                                            null,
                                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                            modifier = Modifier.size(14.dp),
                                                        )
                                                        Spacer(Modifier.width(4.dp))
                                                        Icon(
                                                            Icons.Default.Terminal, null,
                                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                            modifier = Modifier.size(14.dp),
                                                        )
                                                        Spacer(Modifier.width(4.dp))
                                                        Text(
                                                            "调用过程 · ${timeline.size} 步",
                                                            style = MaterialTheme.typography.labelSmall,
                                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                        )
                                                    }
                                                    if (open) {
                                                        Spacer(Modifier.height(6.dp))
                                                        // cid 只用于"还在跑的条目"算耗时; 这些都已结束, 传空无妨
                                                        AiTimeline("", timeline)
                                                    }
                                                }
                                            }
                                            Spacer(Modifier.height(8.dp))
                                        }
                                        // 「智能搜索」—— AI 自己发起的那几次搜索 (工具调用)。
                                        // 对齐用户给的参考图: 中间过程要露出来, 否则用户只看到"等很久",
                                        // 不知道它在干什么。多轮搜索就都列出来。
                                        // ⚠️ 文案就叫「智能搜索」, 别写「使用搜索能力」—— 后者是给开发看的说法,
                                        //    用户看了不知道是什么 (用户原话: "使用搜索能力用户不理解, 就写智能搜索就行了")。
                                        //    而且「智能搜索」正好是输入框里那个胶囊的名字, 两边对得上。
                                        val queries = m.searchQueries.orEmpty()
                                        if (!mine && queries.isNotEmpty()) {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                modifier = Modifier.padding(bottom = 8.dp),
                                            ) {
                                                Icon(
                                                    Icons.Default.TravelExplore, null,
                                                    tint = MaterialTheme.colorScheme.primary,
                                                    modifier = Modifier.size(14.dp),
                                                )
                                                Spacer(Modifier.width(5.dp))
                                                Text(
                                                    "智能搜索 · " + queries.joinToString("；"),
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.primary,
                                                    maxLines = 2,
                                                    overflow = TextOverflow.Ellipsis,
                                                )
                                            }
                                        }
                                        // 「指令专家」留下的两条痕迹: 查过 MC 百科 / 核对过语法。
                                        // 露出来是有意义的 —— 用户看到"它去查了百科、还自查过语法",
                                        // 对这份指令的信任度和只看到一句"已思考"完全不同。
                                        val wikiQ = m.wikiQueries.orEmpty()
                                        if (!mine && wikiQ.isNotEmpty()) {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                modifier = Modifier.padding(bottom = 8.dp),
                                            ) {
                                                Icon(
                                                    Icons.Default.Book, null,
                                                    tint = MaterialTheme.colorScheme.primary,
                                                    modifier = Modifier.size(14.dp),
                                                )
                                                Spacer(Modifier.width(5.dp))
                                                Text(
                                                    "查阅 MC 百科 · " + wikiQ.joinToString("；"),
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.primary,
                                                    maxLines = 2,
                                                    overflow = TextOverflow.Ellipsis,
                                                )
                                            }
                                        }
                                        if (!mine && m.checkCount > 0) {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                modifier = Modifier.padding(bottom = 8.dp),
                                            ) {
                                                Icon(
                                                    Icons.Default.CheckCircle, null,
                                                    tint = MaterialTheme.colorScheme.primary,
                                                    modifier = Modifier.size(14.dp),
                                                )
                                                Spacer(Modifier.width(5.dp))
                                                Text(
                                                    "已核对指令语法",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.primary,
                                                )
                                            }
                                        }
                                        // ★ **客户端自己的复查** (2026-10-02)。
                                        //   和上面那行「已核对指令语法」的区别是关键: 那行只说明
                                        //   **模型说**它查过了 —— 它可以不查, 也可以查完再改。这一行是
                                        //   客户端拿**最终正文**又验了一遍的结论。
                                        //   用户报「这个审核不对啊，石头 ID 都不对」就是因为原来只有前者。
                                        if (!mine && m.rechecked) {
                                            val bad = m.recheckIssueCount
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                modifier = Modifier.padding(bottom = if (bad > 0) 4.dp else 8.dp),
                                            ) {
                                                Icon(
                                                    if (bad == 0) Icons.Default.CheckCircle else Icons.Default.Warning,
                                                    null,
                                                    tint = if (bad == 0) MaterialTheme.colorScheme.primary
                                                    else MaterialTheme.colorScheme.error,
                                                    modifier = Modifier.size(14.dp),
                                                )
                                                Spacer(Modifier.width(5.dp))
                                                Text(
                                                    if (bad == 0) "已复查方块 / 物品 ID"
                                                    else "复查发现 $bad 处问题",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = if (bad == 0) MaterialTheme.colorScheme.primary
                                                    else MaterialTheme.colorScheme.error,
                                                )
                                            }
                                            // 有问题就把问题**列出来** —— 用户要的正是"到底哪里不对"。
                                            // 只报个数字等于把问题又丢回给用户。
                                            if (bad > 0) {
                                                m.recheckIssues.orEmpty().take(4).forEach { issue ->
                                                    Text(
                                                        "· $issue",
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                        modifier = Modifier.padding(bottom = 8.dp),
                                                    )
                                                }
                                            }
                                        }
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            if (!mine && m.err) {
                                                Icon(Icons.Default.Warning, null, tint = MaterialTheme.colorScheme.error,
                                                    modifier = Modifier.size(16.dp))
                                                Spacer(Modifier.width(6.dp))
                                            }
                                            // 默认**不套** SelectionContainer: 它靠长按起选, 会和长按菜单抢手势。
                                            // 走菜单里的「选择文本」才切进来。
                                            // ★ 例外: **自己的消息** (2026-10-05, 用户要求"我的消息"可选) ——
                                            //   自己的提问基本是纯文本, 没有代码块要横滚, 也没有"上传"这类
                                            //   要精心保护的交互, 所以**默认就开选择**, 不必再点一次菜单。
                                            //   ⚠️ 长按菜单仍然可用: SelectionContainer 只在**文字区域**起选,
                                            //      卡片留白处(Column 的 padding)的长按照旧冒泡到 combinedClickable。
                                            // ★ 正文走 Markdown 渲染 —— 模型常输出 `**加粗**`,
                                            //   之前直接 Text 会显示成字面的星号 (用户截图反馈过)。
                                            // ★ 2026-10-01 起换成分块版: ``` 代码块渲染成带标题栏的独立卡片,
                                            //   指令卡片上直接有「复制」和「上传到指令库」两个按钮。
                                            val selectableHere = mine || selectIdx == idx
                                            // ★ 有附件时只显示用户自己那句话 —— 附件正文是**发给模型**的,
                                            //   原样铺在气泡里会把排版冲垮(见 AiAttach.displayText)。
                                            val aiDisplay = if (mine) AiAttach.displayText(m.content) else m.content
                                            val body: @Composable () -> Unit = {
                                                MarkdownBlocks(
                                                    aiDisplay,
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    // ★ 选择模式 (2026-10-05): 正文段落可拖选, 代码块关掉横向滚动
                                                    //   改自动换行(两者抢同一手势, 见 MarkdownBlocks 的 ⚠️)。
                                                    selectable = selectableHere,
                                                    onUpload = { cmd, title, mc ->
                                                        if (AppState.token == null) {
                                                            android.widget.Toast.makeText(
                                                                ctx, "已填入编辑器；登录后才能上传到指令库",
                                                                android.widget.Toast.LENGTH_SHORT).show()
                                                        }
                                                        nav.navigate(
                                                            Routes.editor(
                                                                cmd = cmd, mc = mc, title = title,
                                                                // 带着当前会话的标题当分类线索不靠谱, 留空走默认 other
                                                                cat = "",
                                                            )
                                                        )
                                                    },
                                                )
                                            }
                                            if (selectableHere) {
                                                // ⚠️ 这层 SelectionContainer 让"正文段落 + 代码块"能**跨块**
                                                //   连续选中(选一大段带代码的内容), 代码块内部那层管块内横选。
                                                SelectionContainer { body() }
                                            } else {
                                                body()
                                            }
                                        }
                                    }
                                }

                                // 长按菜单。「分享」按需求不做。
                                DropdownMenu(expanded = menuIdx == idx, onDismissRequest = { menuIdx = -1 }) {
                                    DropdownMenuItem(
                                        text = { Text("复制") },
                                        leadingIcon = {
                                            Icon(Icons.Default.ContentCopy, null, modifier = Modifier.size(18.dp))
                                        },
                                        onClick = {
                                            copyToClipboard(ctx, m.content, if (mine) "已复制" else "已复制 AI 回复")
                                            menuIdx = -1
                                        },
                                    )
                                    DropdownMenuItem(
                                        text = { Text("选择文本") },
                                        leadingIcon = {
                                            Icon(Icons.Default.SelectAll, null, modifier = Modifier.size(18.dp))
                                        },
                                        onClick = { selectIdx = idx; menuIdx = -1 },
                                    )
                                    // 「修改」只给自己的提问 —— 改 AI 的回答没有对应的重新生成语义
                                    if (mine) {
                                        DropdownMenuItem(
                                            text = { Text("修改") },
                                            leadingIcon = {
                                                Icon(Icons.Default.Edit, null, modifier = Modifier.size(18.dp))
                                            },
                                            onClick = {
                                                menuIdx = -1
                                                selectIdx = -1
                                                // 把这条放回输入框, 并砍掉它以及之后的所有消息 ——
                                                // 改完重发就是一条新的分支, 与客户端的「修改」行为一致。
                                                // ★ 有附件时只回填用户那句话, 附件另行回填到草稿 ——
                                                //   否则会把整份文件正文灌进输入框(几万字), 没法改。
                                                input = AiAttach.displayText(m.content)
                                                attachDraft = m.attachments.orEmpty()
                                                // ★★ 2026-10-05: 旧的一轮**暂存**起来, 不再直接丢 ——
                                                //   重发成功后它会作为「第 1 版」并回去, 气泡下方出现
                                                //   `‹ 1/2 ›` 可以翻回来看 (用户要的 DeepSeek 行为)。
                                                pendingPrev = messages.getOrNull(idx + 1)
                                                    ?.takeIf { it.role == "assistant" }
                                                pendingPrevUser = m.content
                                                setMessages(messages.take(idx))
                                                android.widget.Toast.makeText(
                                                    ctx, "已放回输入框，改完重新发送",
                                                    android.widget.Toast.LENGTH_SHORT).show()
                                            },
                                        )
                                    }
                                }
                            }

                            if (!mine) {
                                // 免责声明 (对齐 DeepSeek 客户端)。
                                // 报错条不盖这个章 —— 它不是 AI 的回答, 盖了反而误导。
                                if (!m.err) {
                                    Text(
                                        "本回答由 AI 生成，内容仅供参考，请仔细甄别",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(top = 4.dp, start = 2.dp),
                                    )
                                }
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(top = 2.dp),
                                ) {
                                    // 玻璃化: 这些长在页面底色上(不在卡片里), 采样源用默认的 background
                                    GlassIconButton(size = 30.dp,
                                        onClick = { copyToClipboard(ctx, m.content, "已复制 AI 回复") }) {
                                        Icon(Icons.Default.ContentCopy, "复制",
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(15.dp))
                                    }
                                    // 有用 / 没用 —— 帮 AI 越用越准 (自我进化)。只有服务端给了轮次 id 才发得出去
                                    if (!m.err && m.turnId > 0) {
                                        Spacer(Modifier.width(4.dp))
                                        GlassIconButton(enabled = m.vote == 0, size = 30.dp,
                                            onClick = {
                                                scope.launch {
                                                    runCatching { ApiClient.get().aiFeedback(FeedbackReq(m.turnId, 1)) }
                                                    setMessages(messages.map {
                                                        if (it.turnId == m.turnId) it.copy(vote = 1) else it
                                                    })
                                                }
                                            }) {
                                            Icon(Icons.Default.ThumbUp, "有用",
                                                tint = if (m.vote == 1) MaterialTheme.colorScheme.primary
                                                else MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.size(15.dp))
                                        }
                                        GlassIconButton(enabled = m.vote == 0, size = 30.dp,
                                            onClick = {
                                                scope.launch {
                                                    runCatching { ApiClient.get().aiFeedback(FeedbackReq(m.turnId, -1, "App 反馈")) }
                                                    setMessages(messages.map {
                                                        if (it.turnId == m.turnId) it.copy(vote = -1) else it
                                                    })
                                                }
                                            }) {
                                            Icon(Icons.Default.ThumbDown, "没用",
                                                tint = if (m.vote == -1) MaterialTheme.colorScheme.error
                                                else MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.size(15.dp))
                                        }
                                        // 「有帮助吗？」那行字删掉了 (2026-10-01) —— 参考图里
                                        // 操作行只有图标, 没有提示文案; 而且点赞后图标自己会变色,
                                        // 状态已经表达清楚了, 再挂一行字纯属占地方。
                                    }
                                    // ★ 按会话判断 (2026-10-02): 别的对话在跑, 不影响这条能不能重新生成
                                    if (lastAi && !AiTaskRunner.isBusyFor(cur?.id ?: "")) {
                                        Spacer(Modifier.width(4.dp))
                                        GlassIconButton(size = 30.dp, onClick = { doRegenerate() }) {
                                            Icon(Icons.Default.Refresh, "重新生成",
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.size(15.dp))
                                        }
                                    }
                                    // ★★ 回答版本切换 `‹ 1/2 ›` (2026-10-05) —— 用户原话:
                                    //   「像 deepseek 一样有个 1/2，2/2 一样表示修改的次数还有每次的内容」。
                                    //   「重新生成」/「修改后重发」产生的每一版都留在这里, 可左右翻。
                                    if (m.versionCount > 1) {
                                        Spacer(Modifier.width(8.dp))
                                        VersionSwitcher(
                                            index = m.variantIndex,
                                            total = m.versionCount,
                                            onPrev = { doSwitchVariant(idx, m.variantIndex - 1) },
                                            onNext = { doSwitchVariant(idx, m.variantIndex + 1) },
                                        )
                                    }
                                }
                            }
                        }
                    }
                    // 「思考中」气泡只挂在**发起它的那条会话**上 —— 用户切到别的会话时
                    // 不该在那里显示"思考中"(那条会话根本没在跑)。
                    // ★ 2026-10-02 起是**按会话**判断的: 几条对话可以同时在跑。
                    //
                    // ★ 流式输出 (2026-10-02): 已经收到正文就直接显示正文, 不再转圈 ——
                    //   用户要的是"看得到它在写", 而不是"看得到它在转"。
                    if (AiTaskRunner.isBusyFor(cur?.id ?: "")) {
                        item {
                            val cid = cur?.id ?: ""
                            val stream = AiTaskRunner.streamFor(cid)
                            // ★★ 实时思考正文 (2026-10-05) —— 用户原话: "我的过程指的是像 workbuddy
                            //   或者 ima 一样**展示中间思考的内容**和调用的工具"。
                            //   与 stream(最终答案) 并列, 这是模型"在想什么"的实时推理内容。
                            //   上游不返回思考的模型这里一直是空串, 下面的块自然不显示。
                            val think = AiTaskRunner.thinkFor(cid)
                            // ★ 结构化工具时间线 —— 生成期间**也保持可见**(原来是"有正文就塌成一行")。
                            val timeline = AiTaskRunner.toolEventsFor(cid)
                            // ★ 过程步骤 (2026-10-02): 用户原话"在思考过程和调用工具的过程中应该
                            //   让用户知道 AI 在动, 展示过程"。只转圈是不够的 —— 用户不知道它是在
                            //   查百科、在核语法、还是在干等。最新的在最后, 只露最近 5 条。
                            val steps = AiTaskRunner.eventsFor(cid).takeLast(5)
                            // ⚠️ 这里**没有**自动滚动 —— 滚动在页面级的 snapshotFlow 里做。
                            //    挂在这儿的话, 气泡一旦在视口外就不会组合, 永远不会执行。
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
                                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                                    // ★★ 统一成一条 Column (2026-10-05): 顺序与 WorkBuddy / ima 一致 ——
                                    //   「状态 → 思考 → 工具 → 正文」。原来正文与过程是**互斥**的两块
                                    //   (`if (stream.isNotBlank())` 一开, 时间线整块就没了), 用户看到的是
                                    //   "一吐字, 刚才查过什么全消失", 这正是他要修的地方。
                                    Column(Modifier.padding(12.dp)) {
                                        // ---- ① 还没吐正文 → 转圈 + 阶段 + 已等时长 ----
                                        if (stream.isBlank()) {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                                                Spacer(Modifier.width(8.dp))
                                                // 带上已等时长 —— 用户原话是"不然一直让用户等",
                                                // 知道等了多久, 等待感会明显下降。
                                                Text(
                                                    "${AiTaskRunner.phaseFor(cid)} · ${AiTaskRunner.elapsedText(cid)}",
                                                    style = MaterialTheme.typography.bodySmall,
                                                )
                                            }
                                        }

                                        // ---- ② 实时思考块 (推理正文, 边想边长) ----
                                        if (think.isNotBlank()) {
                                            Spacer(Modifier.height(8.dp))
                                            LiveThinkBlock(think, streaming = stream.isBlank())
                                        }

                                        // ---- ③ 工具调用时间线 (全程可见) ----
                                        if (timeline.isNotEmpty()) {
                                            Spacer(Modifier.height(8.dp))
                                            AiTimeline(cid, timeline)
                                        } else if (steps.isNotEmpty() && stream.isBlank()) {
                                            // 旧路径兜底 —— 只有一行字可用时(理论上不会走到, 防御性保留)
                                            Spacer(Modifier.height(6.dp))
                                            steps.forEachIndexed { i, s ->
                                                Row(verticalAlignment = Alignment.CenterVertically) {
                                                    Text(
                                                        if (i == steps.lastIndex) "▸ " else "· ",
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color = MaterialTheme.colorScheme.primary,
                                                    )
                                                    Text(
                                                        s,
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color = if (i == steps.lastIndex)
                                                            MaterialTheme.colorScheme.primary
                                                        else MaterialTheme.colorScheme.onSurfaceVariant,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis,
                                                    )
                                                }
                                            }
                                        }

                                        // ---- ④ 答案正文 (已开始吐字时) ----
                                        if (stream.isNotBlank()) {
                                            // ★★ 流式期间**也走 Markdown 渲染** (2026-10-02 十一批)。
                                            //   用户原话:「在流式输出时 markdown 不渲染，输出完整后才会渲染，修」。
                                            //
                                            //   原来这里刻意用**纯 Text**, 理由是"每 80ms 重解析一次 Markdown 会掉帧"。
                                            //   那个顾虑是错的 —— 代价是流式那几秒里 `**粗体**`、` ``` ` 围栏、
                                            //   `# 标题` 全是**字面符号**, 用户盯着满屏星号和反引号看十几秒,
                                            //   比偶尔掉一帧难受得多。而且解析本身是纯字符串处理 + `remember(text)`
                                            //   缓存(见 MarkdownText/MarkdownBlocks), 一次几毫秒, 真正的开销在布局上,
                                            //   那是无论用什么渲染都躲不掉的。
                                            //
                                            //   ⚠️ 流式期间**不给上传回调** —— 指令可能还没写完, 「复制 / 上传」
                                            //      等落盘后的正式气泡再出现。复制按钮仍在, 但内容是当时的快照。
                                            //   ⚠️ 光标 `▌` 挪到下面那行状态文字上, **不要**拼进正文 ——
                                            //      正文结尾若是个未闭合的 ``` 围栏, `▌` 会被当成代码内容渲染进去。
                                            Spacer(Modifier.height(8.dp))
                                            MarkdownBlocks(stream, style = MaterialTheme.typography.bodyMedium)
                                            Spacer(Modifier.height(6.dp))
                                            Text(
                                                "▌ 生成中 · 已 ${AiTaskRunner.elapsedText(cid)}",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // ★★ 「回到底部」(2026-10-05) —— 配合上面的"手动上翻就停止自动跟随"。
                //
                // 自动跟随停下来之后, 用户总得有条**一键回去**的路; 否则长回答里要自己
                // 一路滑到底, 非常难受。所有成熟客户端都有这个按钮, 位置也都在输入框正上方。
                // ⚠️ 用 inputBarH 抬起, 免得被浮在列表之上的输入条盖住。
                if (!atBottom) {
                    Surface(
                        onClick = { scope.launch { scrollToBottom() } },
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        shadowElevation = 4.dp,
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(end = 14.dp, bottom = inputBarH + 14.dp),
                    ) {
                        Icon(
                            Icons.Default.KeyboardArrowDown, "回到底部",
                            modifier = Modifier.padding(8.dp).size(20.dp),
                        )
                    }
                }

                // 输入行
                // 玻璃开启时底栏浮在内容之上, 输入行要一起抬起来, 否则会被压住

                if (glassInput) {
                    // 玻璃输入条: 浮在聊天列表之上, 底部让开底部导航栏
                    GlassSurface(
                        backdrop = inputBackdrop,
                        shape = RoundedCornerShape(28.dp),
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            // 实测本条高度, 回填给列表末尾的 contentPadding
                            .onSizeChanged { inputBarH = with(density) { it.height.toDp() } }
                            .padding(horizontal = 8.dp)
                            .padding(bottom = 8.dp + LocalGlassBottomInset.current),
                    ) {
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .padding(start = 12.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                        ) {
                            // ⚠️ 2026-10-01 用户退回：这里一度改成"输入框占满整宽在上、开关+发送在下行"
                            //   (想对齐 DeepSeek), 用户要求**改回旧的** —— 旧版是开关在上、输入框与发送同排。
                            //   别再加回来了。
                            // ★★ 2026-10-05 起这一行**只剩**两个随手可切的小开关(深度思考/智能搜索)。
                            //   「指令专家 / 语法核对」已挪到新建对话时选(见 AiModePicker / AiMode) ——
                            //   用户要求"不要换行" + "类似 WorkBuddy 在新建对话时选"。
                            //   ⚠️ 判据也放宽: 只要**可能**有开关可显示就渲染(不然这行会塌掉)。
                            if (thinkReady || searchReady) {
                                AiToolChips(
                                    thinkReady = thinkReady,
                                    thinkOn = thinkOn,
                                    onThink = { thinkOn = !thinkOn; Session.aiThinkOn = thinkOn },
                                    searchOn = searchOn,
                                    searchReady = searchReady,
                                    onSearch = { searchOn = !searchOn; Session.aiSearchOn = searchOn },
                                )
                                Spacer(Modifier.height(6.dp))
                            }
                            // ★ 待发送附件的小胶囊行 (2026-10-05) —— 有附件才出现, 点 × 可移除。
                            //   放在输入框**上方**: 与"输入框里那行字"是同一层语义(都是待发内容),
                            //   挤在输入框里会把输入区压窄。
                            if (attachDraft.isNotEmpty()) {
                                AttachChipsRow(attachDraft, onRemove = { i ->
                                    attachDraft = attachDraft.toMutableList().also { it.removeAt(i) }
                                })
                                Spacer(Modifier.height(6.dp))
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                // ★ 上传文件按钮 (2026-10-05) —— 回形针。
                                //   ⛔ 放在输入框**左侧**: 与微信/Telegram 一致, 用户不用找。
                                //   ★ 没配 Key/没解锁时禁用 —— 传了也没人看, 不如直接灰掉。
                                IconButton(
                                    onClick = {
                                        filePicker.launch(
                                            arrayOf(
                                                "text/*",
                                                "application/json",
                                                "application/xml",
                                                "application/octet-stream",   // .mcfunction 常被识别成这个
                                            )
                                        )
                                    },
                                    enabled = hasKey || unlocked,
                                ) {
                                    Icon(
                                        Icons.Default.AttachFile, "上传文件",
                                        tint = if (hasKey || unlocked) MaterialTheme.colorScheme.onSurfaceVariant
                                        else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                                    )
                                }
                                OutlinedTextField(
                                    value = input, onValueChange = { input = it },
                                    placeholder = { Text(
                                        when {
                                            hasKey || unlocked -> "输入你的 MC 指令需求…"
                                            else -> "登录后可用 AI"
                                        }
                                    ) },
                                    modifier = Modifier.weight(1f),
                                    maxLines = 3,
                                    // 输入框自身全透明, 边框与底色交给外面的玻璃胶囊
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedBorderColor = Color.Transparent,
                                        unfocusedBorderColor = Color.Transparent,
                                        disabledBorderColor = Color.Transparent,
                                        focusedContainerColor = Color.Transparent,
                                        unfocusedContainerColor = Color.Transparent,
                                        disabledContainerColor = Color.Transparent,
                                    ),
                                )
                                // ★ 官方 LiquidButton (逐字搬运见 ui/liquidglass/, Apache-2.0)。
                                //   等宽高 + Capsule() 就是正圆; 强调色照官方 demo 的写法: tint + 白色内容。
                                //   官方没有 enabled 参数, 禁用态靠 isInteractive=false + 自己拦 onClick。
                                //
                                // ★ 2026-10-05: 在跑时这个键变「停止生成」—— 用 error 色(红)区分,
                                //   图标换成实心方块。仍然 isInteractive, 所以**点得动**(不再是灰的)。
                                val primaryOn = busyNow || sendEnabled
                                LiquidButton(
                                    onClick = onPrimaryAction,
                                    backdrop = inputBackdrop,
                                    modifier = Modifier.size(44.dp),
                                    isInteractive = primaryOn,
                                    height = 44.dp,
                                    contentPadding = 0.dp,
                                    tint = when {
                                        busyNow -> MaterialTheme.colorScheme.error
                                        sendEnabled -> MaterialTheme.colorScheme.primary
                                        else -> MaterialTheme.colorScheme.surfaceVariant
                                    },
                                ) {
                                    Icon(
                                        if (busyNow) Icons.Default.Stop else Icons.Default.Send,
                                        if (busyNow) "停止生成" else "发送",
                                        tint = if (primaryOn) Color.White
                                        else MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                } else {
                    // ★ 非玻璃输入行 —— 2026-09-25 修掉的一个真 bug
                    //
                    // 上一版这里**漏了 `.align(Alignment.BottomCenter)`**(玻璃分支有)。
                    // 输入行现在和聊天列表同处一个 Box 里, 没有 align 就默认贴 TopStart,
                    // 于是输入框跑到了页面**顶部**, 直接压在欢迎语上(实测截图确认)。
                    // 根因是"把输入条从 Scaffold.bottomBar 挪进 Box 当浮层"这次重构:
                    // 玻璃分支加了 align, 非玻璃分支忘了 —— 只有关掉液态玻璃才会暴露。
                    //
                    // 另外两处必须一起补, 否则底部会出别的毛病:
                    //   · fillMaxWidth(): 与上游一致, 输入行铺满整宽
                    //   · background(): 列表在 Box 里是 fillMaxSize, 会从输入行**下面**穿过去。
                    //     上游的输入行是 Column 里列表的下一个兄弟, 天然不重叠; 现在改成浮层,
                    //     就必须自己铺一层不透明底, 否则聊天内容会从输入框底下透出来。
                    //   · onSizeChanged(): 实测高度回填给列表末尾的 contentPadding, 免得
                    //     最后一条消息被输入行盖住。
                    Column(
                        Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.background)
                            .onSizeChanged { inputBarH = with(density) { it.height.toDp() } }
                            .padding(8.dp)
                            .padding(bottom = LocalGlassBottomInset.current),
                    ) {
                        // 开关 —— 两个外观分支都要有, 免得只有开玻璃才看得见
                        // ★ 2026-10-05: 这一行**只剩**深度思考/智能搜索 (与玻璃分支逐字对齐)。
                        //   指令专家/语法核对已挪到新建对话时选。
                        if (thinkReady || searchReady) {
                            AiToolChips(
                                thinkReady = thinkReady,
                                thinkOn = thinkOn,
                                onThink = { thinkOn = !thinkOn; Session.aiThinkOn = thinkOn },
                                searchOn = searchOn,
                                searchReady = searchReady,
                                onSearch = { searchOn = !searchOn; Session.aiSearchOn = searchOn },
                            )
                            Spacer(Modifier.height(6.dp))
                        }
                        // ★ 待发送附件 (2026-10-05) —— 与玻璃分支逐字对齐
                        if (attachDraft.isNotEmpty()) {
                            AttachChipsRow(attachDraft, onRemove = { i ->
                                attachDraft = attachDraft.toMutableList().also { it.removeAt(i) }
                            })
                            Spacer(Modifier.height(6.dp))
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(
                                onClick = {
                                    filePicker.launch(
                                        arrayOf(
                                            "text/*",
                                            "application/json",
                                            "application/xml",
                                            "application/octet-stream",
                                        )
                                    )
                                },
                                enabled = hasKey || unlocked,
                            ) { Icon(Icons.Default.AttachFile, "上传文件") }
                            OutlinedTextField(
                                value = input, onValueChange = { input = it },
                                placeholder = { Text(if (hasKey || unlocked) "输入你的 MC 指令需求…" else "登录后可用 AI") },
                                modifier = Modifier.weight(1f),
                                maxLines = 3,
                            )
                            // ★ 2026-10-05: 在跑时变成「停止生成」(与玻璃分支逐字对齐)
                            IconButton(
                                onClick = onPrimaryAction,
                                enabled = busyNow || sendEnabled,
                            ) {
                                Icon(
                                    if (busyNow) Icons.Default.Stop else Icons.Default.Send,
                                    if (busyNow) "停止生成" else "发送",
                                    tint = if (busyNow) MaterialTheme.colorScheme.error
                                    else androidx.compose.ui.graphics.Color.Unspecified,
                                )
                            }
                        }
                    }
                }
                }
            }
        }
    }
    }   // ← 闭合 ModalNavigationDrawer (对话记录侧边栏)

    // ---- 弹层 ----
    if (showConfig) {
        AiConfigDialog(onDismiss = { showConfig = false })
    }

    // ★★ 新建对话的模式选择 (2026-10-05) —— 用户要的"类似 WorkBuddy 在新建对话时选"。
    //   顶栏 + 和侧边栏「新对话」都走这里: 先选模式, 再建会话。
    //   ⚠️ 取消选择 = 什么都不做 (不建会话) —— 免得用户点了 + 又反悔, 凭空多一条空会话。
    if (showNewConv) {
        AiModePicker(
            onPick = { mode ->
                AiConvStore.create(mode = mode)
                convs = AiConvStore.list()
                curId = AiConvStore.currentId()
                showNewConv = false
                scope.launch { drawerState.close() }
            },
            onDismiss = { showNewConv = false },
        )
    }

    // ★ 改当前会话模式 (2026-10-05) —— 点顶栏副标题的模式徽标触发。
    //   与新建的**区别只有一处**: 不 create 新会话, 而是 setMode 到当前这条, 上下文全保留。
    if (showModeChange) {
        AiModePicker(
            onPick = { mode ->
                val id = cur?.id.orEmpty()
                if (id.isNotBlank()) {
                    AiConvStore.setMode(id, mode)
                    convs = AiConvStore.list()
                    // 触发重组让顶栏徽标立刻变 (cur 是派生值, 靠 convs 变化带动)
                    curId = id
                }
                showModeChange = false
            },
            onDismiss = { showModeChange = false },
            title = "切换会话模式",
            hint = "换一个模式，这条对话的历史与上下文都保留，只是 AI 之后按新方式来帮你。",
            current = cur?.mode,
        )
    }
}

/**
 * **待发送附件的小胶囊行** (2026-10-05)。
 *
 * 每个文件一个胶囊: 「📎 名字 · 12.3 KB [×]」, 点 × 移除。
 *
 * ★ 横向可滑 —— 文件名可能很长(尤其中文名), 不裁不折, 让用户自己滑。
 * ★ 走 `Row` 而不是 `FlowRow`: 与「AI 横条不要换行」同一条纪律 —— 这一行不折。
 */
@Composable
private fun AttachChipsRow(atts: List<Attachment>, onRemove: (Int) -> Unit) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.horizontalScroll(rememberScrollState()),
    ) {
        atts.forEachIndexed { i, a ->
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.secondaryContainer,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(start = 8.dp, end = 2.dp, top = 3.dp, bottom = 3.dp),
                ) {
                    Icon(Icons.Default.AttachFile, null, Modifier.size(13.dp),
                        tint = MaterialTheme.colorScheme.onSecondaryContainer)
                    Spacer(Modifier.width(5.dp))
                    Text(
                        a.name + AiAttach.humanSize(a.size).let { if (it.isBlank()) "" else " · $it" } +
                            if (a.truncated) " ·已截断" else "",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    IconButton(onClick = { onRemove(i) }, modifier = Modifier.size(22.dp)) {
                        Icon(Icons.Default.Close, "移除", Modifier.size(13.dp),
                            tint = MaterialTheme.colorScheme.onSecondaryContainer)
                    }
                }
            }
        }
    }
}

/**
 * 输入条里的能力开关 —— 「深度思考」+「智能搜索」。
 *
 * 位置与形态对齐 DeepSeek 客户端: 那边就是把「深度思考 / 智能搜索」两个胶囊直接摆在输入框里,
 * 点一下即时生效。之前把思考设置埋在「AI 接口配置」弹层里, 用户要先点齿轮、找到区块、选档位、
 * 再保存, 四步才能开一次思考, 等于没有。
 *
 * **智能搜索只在配了搜索引擎时才出现** —— 没配就摆一个点了报错的按钮, 不如不摆。
 *
 * [thinkReady] = 只有直连自备 API 时才给「深度思考」: 官方代理只认 messages 一个字段,
 * 思考参数发不过去, 摆个开关点了没反应比不摆更糟。搜索不受这条限制 —— 它在客户端跑。
 *
 * ⚠️ 原来胶囊下面还有一行说明文案(「用 xx 搜实时资料后再作答」/「强度 高 · 在设置里调」…),
 *    2026-10-01 按用户要求**整行删掉** —— 胶囊开着上色就已经说明状态了, 那行纯占地方。
 *
 * ★★ 2026-10-05: 这里**只剩**「深度思考 / 智能搜索」两个**随手可切**的小开关。
 *   原来的「指令专家 / 语法核对」是**能力边界**, 已挪到**新建对话**时选
 *   (见 [AiMode] / [AiModePicker]) —— 用户原话:「不要换行」「类似 WorkBuddy 在新建对话时选」。
 */
@Composable
private fun AiToolChips(
    thinkReady: Boolean,
    thinkOn: Boolean,
    onThink: () -> Unit,
    searchOn: Boolean,
    searchReady: Boolean,
    onSearch: () -> Unit,
) {
    // ★★ 2026-10-05 用户要求「那个 ai 横条不要换行」——
    //   原来用 FlowRow 把 4 个胶囊自动折行(窄屏下「语法核对」被挤到第二行), 用户明确不要。
    //
    //   现在这一行**只放"随手可切的会话级小开关"**(深度思考 / 智能搜索), 最多 2 个 ——
    //   窄屏一行绝对放得下, 所以直接 Row + 横向可滑兜底(极窄屏也不裁不折)。
    //   ⚠️ 「指令专家 / 语法核对」**已从这里移走** —— 它们是**能力边界**, 属于
    //   "这条会话是什么", 挪到**新建对话**时选(见 AiModePicker)。用户原话:
    //   「类似 WorkBuddy 一样有个编写代码和检查语法的选项在新建对话的时候选择」。
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.horizontalScroll(rememberScrollState()),
    ) {
        if (thinkReady) ToolChip("深度思考", Icons.Default.Psychology, thinkOn, onThink)
        if (searchReady) ToolChip("智能搜索", Icons.Default.TravelExplore, searchOn, onSearch)
    }
}

/**
 * ★★ **实时思考块** —— 把模型的推理正文(`reasoning_content`)**边想边**画出来 (2026-10-05)。
 *
 * 用户原话:「我的过程指的是像 workbuddy 或者 ima 一样**展示中间思考的内容**和调用的工具」。
 * 与"工具时间线"([AiTimeline]) 是**两块不同的东西**, 一起构成"完整过程":
 *   · 本块 = 模型**在想什么**(推理正文, 会一段段长出来)
 *   · [AiTimeline] = 模型**做了什么**(调了哪些工具、查得怎么样)
 *
 * ## 为什么默认**展开**
 * 用户要的就是"看得到它在想什么"。默认收起来等于没做 —— 他得再点一下才能看到,
 * 而流式输出本来就是"盯着看它动"。所以默认 `open = true`。
 *
 * ## ⚠️ 为什么这里用**纯 Text** 而不是 [MarkdownText]
 * 思考正文每 ~90ms 就长一截, 而 [MarkdownText] 的 `remember(text)` 会在 text 每次变化时
 * **把整段重新解析一遍**(单次 O(n), 整段流下来 O(n²))。推理动辄几千字, 长推理下会明显掉帧。
 * 而推理正文本身基本是纯文字, 不需要 Markdown 渲染 —— 纯 Text 又快又不会误吃 `**` 这类符号。
 * (最终落盘后的 `m.think` 那条折叠块才用 [MarkdownText] —— 那时只解析一次, 无所谓。)
 *
 * @param text      已经收到的思考正文(增量累加的结果)
 * @param streaming 是否还在流 —— 决定标题写「正在思考…」还是「思考过程 · N 字」
 */
@Composable
private fun LiveThinkBlock(text: String, streaming: Boolean) {
    // ⚠️ 不把 text 作为 key: 用户手动收起后, 新的增量不该把块又弹开。
    var open by remember { mutableStateOf(true) }
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { open = !open },
    ) {
        Column(Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (open) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(14.dp),
                )
                Spacer(Modifier.width(4.dp))
                Icon(
                    Icons.Default.Psychology, null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(14.dp),
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    if (streaming) "正在思考…" else "思考过程 · ${text.length} 字",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (open) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * **全程实时时间线** —— 把 AI 这一轮里每一次工具调用 / 每一次复查完整摊开 (2026-10-05)。
 *
 * 用户原话:「要把 AI 在中间使用工具、然后输出的内容什么的完整过程也输出出来，
 * 且可以让 ai 同时搜索几个或者查看几个 api 接口」。
 *
 * ## 它比旧的「一串短句」多表达了什么
 * | 维度 | 旧 (List<String>) | 现在 (AiToolEvent) |
 * |---|---|---|
 * | 状态 | 无 | 转圈 / ✓ / ⚠ —— 一眼看出哪条卡住、哪条失败 |
 * | 耗时 | 无 | `320 毫秒` / `1.4 秒` —— 用户知道钱(时间)花在哪 |
 * | 并发 | 看不出来 | 同 `group` 的几条**并排**, 用"⇉ 并行"标出 |
 * | 来源 | 一句话里 | wiki 类会把各源命中数摊开(MC百科4 · 维基0 …) |
 *
 * ## ⚠️ 为什么"同 group 并排"要单独处理
 * 用户要的就是"**同时**查几个接口"这件事**看得见**。如果并发跑出来的几条渲染成上下两行,
 * 观感和串行完全一样 —— 用户会以为"还是一条条来的"。所以同 group 的第一条带"⇉ 并行"前缀,
 * 后续同 group 的条目都缩进对齐。
 *
 * @param cid      会话 id —— 只用来算"还在跑的那条等了多久"([AiToolEvent.costText] 要实时 now)
 * @param events   已经发生的事件, 最新的在最后
 */
@Composable
private fun AiTimeline(cid: String, events: List<AiToolEvent>) {
    // 用全局心跳当"现在" —— AiTaskRunner.clock 每 200ms 变一次, 所以"进行中"的条目
    // 耗时数字会自己往上走, 不需要这里再起一个计时器。
    val now = AiTaskRunner.clock

    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        events.forEachIndexed { i, ev ->
            // 同 group 的**第二条及以后**缩进 —— 视觉上表达"这几条是一起跑的"
            val prev = events.getOrNull(i - 1)
            val sameGroupAsPrev = prev != null && prev.group == ev.group && ev.group != 0L
            val isGroupHead = ev.group != 0L && !sameGroupAsPrev &&
                events.drop(i + 1).any { it.group == ev.group }

            Row(
                verticalAlignment = Alignment.Top,
                modifier = Modifier.padding(start = if (sameGroupAsPrev) 6.dp else 0.dp),
            ) {
                // ---- 状态图标 ----
                when {
                    ev.isRunning -> CircularProgressIndicator(
                        modifier = Modifier.padding(top = 3.dp).size(10.dp), strokeWidth = 1.5.dp)
                    ev.isFailed -> Text("⚠", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error)
                    else -> Text("✓", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary)
                }
                Spacer(Modifier.width(5.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // 并发标记 —— 只在"组头"上打一次
                        if (isGroupHead) {
                            Text("⇉ 并行 ", style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.tertiary)
                        }
                        Text(
                            ev.title,
                            style = MaterialTheme.typography.labelSmall,
                            color = when {
                                ev.isFailed -> MaterialTheme.colorScheme.error
                                ev.isRunning -> MaterialTheme.colorScheme.primary
                                else -> MaterialTheme.colorScheme.onSurface
                            },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        // 耗时 —— 只在**已经结束**的条目上显示。进行中的数字一直在跳,
                        // 反而让人盯着看(DeepSeek 那种"已用 3 秒"就够), 这里选择不显示。
                        if (!ev.isRunning) {
                            Spacer(Modifier.width(6.dp))
                            Text(
                                ev.costText(now),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    if (ev.detail.isNotBlank()) {
                        Text(
                            ev.detail,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (ev.isFailed) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

/**
 * ★★ **回答版本切换器** —— DeepSeek 那个 `‹ 1/2 ›` (2026-10-05)。
 *
 * 用户原话:「修改的那个功能，像 deepseek 一样有个 1/2，2/2 一样表示修改的次数
 * 还有每次的内容」。
 *
 * 「重新生成」/「修改后重发」会为同一条回答攒下多版(见 [UiMsg.variants]),
 * 这个胶囊就是用来翻的: 左边箭头回上一版, 中间显示 `当前版/总版数`, 右边箭头去下一版。
 *
 * ⚠️ 到头的那个箭头要**置灰**(不是隐藏) —— 隐藏会让胶囊宽度忽宽忽窄, 点的时候位置一直在变。
 * ⚠️ 只有一版时**整块不渲染**(调用点判的), 免得每条回答都挂个没用的 `1/1`。
 */
@Composable
private fun VersionSwitcher(index: Int, total: Int, onPrev: () -> Unit, onNext: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onPrev, enabled = index > 0, modifier = Modifier.size(28.dp)) {
                Icon(
                    Icons.Default.ChevronLeft, "上一版",
                    modifier = Modifier.size(16.dp),
                    tint = if (index > 0) MaterialTheme.colorScheme.onSurfaceVariant
                    else MaterialTheme.colorScheme.outline,
                )
            }
            Text(
                "${index + 1}/$total",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            IconButton(onClick = onNext, enabled = index < total - 1, modifier = Modifier.size(28.dp)) {
                Icon(
                    Icons.Default.ChevronRight, "下一版",
                    modifier = Modifier.size(16.dp),
                    tint = if (index < total - 1) MaterialTheme.colorScheme.onSurfaceVariant
                    else MaterialTheme.colorScheme.outline,
                )
            }
        }
    }
}

/** 单个胶囊开关: 关时不上色, 开时用主色 —— 一眼能看出状态 */
@Composable
private fun ToolChip(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, on: Boolean, onClick: () -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(50),
        color = if (on) accent.copy(alpha = 0.16f) else Color.Transparent,
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (on) accent.copy(alpha = 0.55f) else MaterialTheme.colorScheme.outlineVariant,
        ),
    ) {
        Row(
            Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                icon, null,
                tint = if (on) accent else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(15.dp),
            )
            Spacer(Modifier.width(4.dp))
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = if (on) accent else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
