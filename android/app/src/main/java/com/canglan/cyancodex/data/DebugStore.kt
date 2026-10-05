package com.canglan.cyancodex.data

import android.content.SharedPreferences
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.SnapshotStateList
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 调试模式存储（隐藏功能）。
 *
 * 解锁方式：关于页连点版本号 7 次。解锁后设置页最底部出现「调试」入口。
 *
 * 三块能力：
 * 1. **服务器地址**（含端口）整串可改 —— 指向本地 / 局域网 / 自建后端调试。
 * 2. **日志开关 + 日志查看** —— 记录 App 运行日志，可在面板内查看 / 复制 / 清空。
 * 3. **网络请求记录** —— 每次 API 请求的 method / URL / 状态码 / 耗时 / 错误。
 *
 * ⛔ 日志与请求记录**只在内存 + 落盘各留一份**：内存里用固定长度环形缓冲
 * （日志 [LOG_CAP]，请求 [REQ_CAP]），超出丢最旧的 —— 长时间挂机不会把内存吃爆。
 */
object DebugStore {

    private const val PREFS = "cyancodex"

    // ---------- 持久化的开关 / 配置 ----------
    private const val K_UNLOCKED = "debug_unlocked"
    private const val K_LOG_ON = "debug_log_on"
    private const val K_BASE_URL = "debug_base_url"

    private lateinit var sp: SharedPreferences

    /** 调 [Session.init] 时一并初始化（共用同一份 SharedPreferences）。 */
    fun attach(prefs: SharedPreferences) {
        sp = prefs
        // ★ 同步 Compose 可观察镜像 —— 见 [unlockedState] / [masterState]。
        //   放在这里是因为 attach 是"SP 就绪"的唯一时刻, 之后再没有初始化窗口。
        unlockedState.value = runCatching { sp.getBoolean(K_UNLOCKED, false) }.getOrDefault(false)
        masterState.value = runCatching { sp.getBoolean(K_MASTER, true) }.getOrDefault(true)
    }

    private val ensure: SharedPreferences
        get() = sp

    // ---------- 调试总开关（仿「开发者选项」） ----------

    private const val K_MASTER = "debug_master_on"

    /**
     * ★ **调试总开关** —— 仿安卓「开发者选项」那个顶部开关 (2026-10-05)。
     *
     * 用户原话:「调试改成类似安卓开发者设置一样有个关闭开关」。
     *
     * ## 它为什么不能直接用 [unlocked]
     * [unlocked] 是**入口闸**(关于页连点版本号 7 次才有「调试」入口), 一旦解锁就长期有效 ——
     * 那是"我什么时候能进这个页面"。而本开关是**功能闸**(和开发者选项顶部那个开关一样),
     * 它管的是"进了页面之后, 这些调试能力**当下**要不要真的生效":
     *
     * | | 关 | 开 |
     * |---|---|---|
     * | 日志记录 | 不写 | 写 |
     * | 请求记录 | 不记 | 记 |
     * | Cookie 注入到请求 | **不注入** | 注入 |
     * | 自定义服务器地址 / 路径改写 | **不生效** | 生效 |
     *
     * ★ 与安卓开发者选项的语义**逐条对齐**: 开发者选项开着不代表 USB 调试开着 ——
     *   入口与每个具体能力是两层开关。之前只有 [unlocked] 一层, "解锁之后所有调试能力
     *   全部常开、关不掉", 用户要的正是这个**总闸**。
     *
     * ⚠️ 默认 **true** —— 老用户升级上来时 [unlocked] 已是 true, 若这里默认 false,
     *    他们的自定义服务器地址会**突然失效**, 属于破坏性变更。
     */
    var masterOn: Boolean
        get() = runCatching { ensure.getBoolean(K_MASTER, true) }.getOrDefault(true)
        set(v) {
            runCatching { ensure.edit().putBoolean(K_MASTER, v).apply() }
            // ★ 同步可观察镜像 —— 否则设置页那个"调试"入口不会随开关消失/出现
            masterState.value = v
        }

    /**
     * ★★ **总开关的 Compose 可观察镜像** (2026-10-05 修 bug)。
     *
     * 为什么必须有它: SharedPreferences **不是** Compose 可观察对象。
     * 原来设置页写的是 `remember { mutableStateOf(DebugStore.unlocked) }` —— 那是个
     * **一次性快照**, 用户在调试面板里关掉总开关再返回, 设置页的"调试"入口**照样在**
     * (用户报的正是这个: 「我不是说把调试模式关上设置底下的调试就自动隐藏吗」)。
     *
     * 有了它, `DebugStore.activeState` 被 Composable 直接读, 开关一改就重组。
     */
    val masterState = mutableStateOf(true)

    /**
     * ★★ **解锁态的 Compose 可观察镜像** (2026-10-05)。
     *
     * 关于页连点 7 次解锁后, 设置页要**立刻**出现"调试"入口 —— 只写 SP 是不够的,
     * 设置页可能已经在返回栈里活着(它的 composition 还在), 读不到新值。
     */
    val unlockedState = mutableStateOf(false)

    /** 调试能力**当下是否真的生效** —— 解锁 且 总开关开着。所有调试功能都该判它, 而不是 [unlocked]。 */
    val active: Boolean get() = unlocked && masterOn

    /**
     * ★★ **[active] 的 Compose 可观察版本** —— **UI 一律用这个**。
     *
     * 语义与 [active] 完全一致, 区别只在"读它会被 Compose 订阅重组"。
     * 逻辑判定(拦截器、日志、请求记录)继续用 [active] —— 那些不在组合里跑, 不需要可观察性,
     * 而且每次都读 SP 才是它们想要的"绝对最新"。
     *
     * ⛔ 别在 Composable 里读 [active]: 它读 SharedPreferences, 而组合函数会被反复调用 ——
     *    等于每帧读一次磁盘(虽然 SP 有内存缓存, 但订阅不到变化, 表现就是"改了界面不刷新")。
     */
    val activeState: Boolean get() = unlockedState.value && masterState.value

    /**
     * ★★ **Cookie 功能是否可用** —— 2026-10-05 用户要求:
     * 「cookie 你就应该在开启调试模式的时候直接开启…调试模式关闭时也要同时隐藏入口，类似安卓开发者模式一样」。
     *
     * 语义 = [active]（调试模式开着就**直接可用**, 不需要任何额外条件）。
     * 单独抽一个名字是为了让调用点读起来就是用户那句话, 也方便将来真要加别的条件时有唯一入口。
     *
     * ★ 与 [active] 的区别: 目前完全相同, 只是语义命名。**不要**把 cookie 另判成
     *   `unlocked && cookieOn` 之类 —— 用户明确说了"开调试模式就开", 多层开关会再次变成
     *   "符合条件却开不了"（这正是本次要修的 bug）。
     */
    val cookieEnabled: Boolean get() = active

    // ---------- 调试解锁 ----------

    /** 是否已解锁调试模式（连点版本号 7 次）。 */
    var unlocked: Boolean
        get() = runCatching { ensure.getBoolean(K_UNLOCKED, false) }.getOrDefault(false)
        set(v) {
            runCatching { ensure.edit().putBoolean(K_UNLOCKED, v).apply() }
            unlockedState.value = v     // ★ 同步可观察镜像, 见 [unlockedState]
        }

    /** 是否开启日志记录（默认开；只有在解锁后才真正往缓冲里写）。 */
    var logEnabled: Boolean
        get() = runCatching { ensure.getBoolean(K_LOG_ON, true) }.getOrDefault(true)
        set(v) { runCatching { ensure.edit().putBoolean(K_LOG_ON, v).apply() } }

    /** 自定义服务器地址（整串，含端口）；空串 = 用内置默认地址。 */
    var customBaseUrl: String
        get() = runCatching { ensure.getString(K_BASE_URL, "") ?: "" }.getOrDefault("")
        set(v) { runCatching { ensure.edit().putString(K_BASE_URL, v.trim()).apply() } }

    // ---------- 接口路径改写 ----------

    private const val K_PATH_RULES = "debug_path_rules"

    /**
     * 接口路径改写规则（原路径 → 新路径）。
     *
     * 用来把某个接口打到别的路径上，例如 `/api/commands` → `/api/abc` ——
     * 调试服务端、验证接口行为、绕过某个出问题的端点。
     *
     * 持久化成 `原路径\t新路径` 每行一条（用 tab 分隔，不用 JSON ——
     * 路径里几乎不可能出现 tab，比 JSON 少一层转义，人也能直接读）。
     */
    var pathRules: List<Pair<String, String>>
        get() = runCatching {
            ensure.getString(K_PATH_RULES, "")?.lineSequence()
                ?.mapNotNull { line ->
                    val parts = line.split('\t')
                    if (parts.size >= 2 && parts[0].isNotBlank()) parts[0].trim() to parts[1].trim() else null
                }?.toList() ?: emptyList()
        }.getOrDefault(emptyList())
        set(v) {
            val text = v.filter { it.first.isNotBlank() }
                .joinToString("\n") { "${it.first.trim()}\t${it.second.trim()}" }
            runCatching { ensure.edit().putString(K_PATH_RULES, text).apply() }
        }

    /**
     * 对请求路径应用改写规则。
     *
     * ★ **按顺序取第一个命中的**，不做链式替换 —— 链式替换会让
     * `a→b`、`b→c` 两条规则变成 `a→c`，规则表就没法预测了。
     * ★ **前缀匹配**：用 `startsWith` 而不是全等，这样 `/api/commands` 规则
     * 能同时命中 `/api/commands/12/like`。
     * ★ 返回的是**新路径**（不含 query），query 由调用方保留。
     */
    fun rewritePath(path: String): String? {
        if (path.isBlank()) return null
        for ((from, to) in pathRules) {
            if (from.isBlank()) continue
            if (path == from || path.startsWith(from)) {
                val suffix = path.removePrefix(from)
                return to + suffix
            }
        }
        return null
    }

    // ---------- 日志 ----------

    private const val LOG_CAP = 500
    private const val REQ_CAP = 200

    private val _logs = mutableStateListOf<String>()
    private val _requests = mutableStateListOf<ReqRecord>()

    /** 日志行（Compose 可观察）。 */
    val logs: SnapshotStateList<String> get() = _logs

    /** 网络请求记录（Compose 可观察）。 */
    val requests: SnapshotStateList<ReqRecord> get() = _requests

    /** 请求计数（用于触发重组）。 */
    val reqTick = mutableStateOf(0)

    private val timeFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())

    /**
     * 记一条日志。
     *
     * ⛔ **只在主线程改 `_logs`** —— 它被 Compose 直接观察，
     * 从 IO 线程写会踩快照冲突（跟 AiTaskRunner 里 onEvent/onDelta 必须切主线程同理）。
     * 所以这里只把写操作 post 到主线程（见 [enqueue]）。
     *
     * ★ 2026-10-05: 判据从 [unlocked] 改成 [active] —— 用户在面板里把总开关关掉后,
     *   日志应当**立刻停写**, 而不是等解锁状态变化(它不会变)。
     */
    fun log(tag: String, msg: String) {
        if (!active || !logEnabled) return
        val line = "${timeFmt.format(Date())}  [$tag]  $msg"
        enqueue { pushLog(line) }
    }

    /** 记一条网络请求记录。同样判 [active] —— 总开关关掉后不再堆内存。 */
    fun record(req: ReqRecord) {
        if (!active) return
        enqueue { pushReq(req) }
    }

    // ---------- 主线程调度 ----------

    private fun enqueue(block: () -> Unit) {
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            block()
        } else {
            main.post(block)
        }
    }

    private fun pushLog(line: String) {
        _logs.add(line)
        while (_logs.size > LOG_CAP) _logs.removeAt(0)
    }

    private fun pushReq(r: ReqRecord) {
        _requests.add(0, r)          // 新的排最前，方便看最近发生了什么
        while (_requests.size > REQ_CAP) _requests.removeAt(_requests.size - 1)
        reqTick.value++
    }

    fun clearLogs() = enqueue { _logs.clear() }
    fun clearRequests() = enqueue { _requests.clear() }

    /** 把日志与请求记录导出成一段可复制的文本。 */
    fun exportText(): String {
        val sb = StringBuilder()
        sb.append("=== CyanCodex 调试日志 ===\n")
        sb.append("调试总开关: ").append(if (masterOn) "开" else "关").append('\n')
        sb.append("服务器地址: ").append(currentBaseUrlDisplay()).append('\n')
        sb.append("日志开关: ").append(if (logEnabled) "开" else "关").append('\n')
        // ★ Cookie 状态 (2026-10-05) —— **只报有没有、多少字符, 永不打印原文**。
        //   cookie 等于完整账号权限; 这份导出文本用户会到处贴(github issue / 群里),
        //   打进去就是主动泄露。要导出 cookie 的话走面板上那个专门的「导出 Cookie」按钮,
        //   那个至少是用户明确知道自己导出的是什么。
        sb.append("Cookie 注入: ").append(cookieStatusText()).append('\n')
        sb.append("登录态: ").append(runCatching { Session.loginStateSummary().replace('\n', ' ') }.getOrDefault("读取失败")).append('\n')
        sb.append('\n')
        sb.append("--- 日志 (").append(_logs.size).append(") ---\n")
        _logs.forEach { sb.append(it).append('\n') }
        sb.append('\n')
        sb.append("--- 网络请求 (").append(_requests.size).append(") ---\n")
        _requests.forEach { sb.append(it.toLine()).append('\n') }
        return sb.toString()
    }

    /**
     * Cookie 状态的**安全摘要** —— 给面板和导出文本用。
     *
     * ⛔ 只返回"已设置(N 字符)" / "未设置", **绝不返回原文**。见 [exportText] 的注释。
     */
    fun cookieStatusText(): String = runCatching {
        val c = Session.cookie
        if (c.isNullOrBlank()) "未设置" else "已设置（${c.length} 字符，已加密落盘）"
    }.getOrDefault("读取失败")

    /**
     * **导出登录态原文** —— 用户明确点「导出登录态」按钮时才调 (2026-10-05)。
     *
     * ⛔ 2026-10-05 改名 + 改语义: 原来叫 `exportCookie()` 且**只**返回 `Session.cookie`。
     *    但实测服务器**不下发 Set-Cookie**, 密码登录的会话里 cookie 恒为空 ——
     *    于是"登录状态下导出"永远是空串, 用户报「导出不了」。
     *    现在改为导出**整个登录态**(token 优先, cookie 有就一并带上),
     *    语义对齐用户那句话: 「我的 cookie 是指登录态之类的, 反正输进去能进行登陆就行」。
     *    详见 [Session.exportLoginState]。
     *
     * ⚠️ 返回原文是**有意的**, 这与"日志永不打印凭证"不矛盾:
     *   日志是**自动被动记录**(用户没主动要, 却可能随手贴出去);
     *   这里是**用户主动索取**(点了按钮, 且有二次确认)。两者风险面完全不同。
     */
    fun exportLoginState(): String = runCatching { Session.exportLoginState() }.getOrDefault("")

    /** 面板顶部显示的当前生效地址（自定义优先）。 */
    fun currentBaseUrlDisplay(): String =
        customBaseUrl.ifBlank { runCatching { Session.DEFAULT_BASE_URL }.getOrDefault("") }

    // ---------- 请求记录模型 ----------

    data class ReqRecord(
        val time: Long,
        val method: String,
        val url: String,
        val code: Int,          // 0 = 未拿到响应（连接失败/超时）
        val costMs: Long,
        val error: String = "",
    ) {
        fun toLine(): String {
            val t = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.getDefault()).format(Date(time))
            val status = if (code > 0) code.toString() else "ERR"
            return "$t  $method  $status  ${costMs}ms  $url" +
                if (error.isNotBlank()) "  ← $error" else ""
        }
    }
}
