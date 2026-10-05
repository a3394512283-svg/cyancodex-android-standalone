package com.canglan.cyancodex.data

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * 本地会话与配置存储
 */
object Session {
    private const val PREFS = "cyancodex"
    private lateinit var sp: SharedPreferences

    /**
     * ★ 工具轮数一次性迁移标记 (2026-10-05) —— 见 [aiToolRounds]。
     * 存 `true` 表示"已经按新默认值抬过一次存量", 之后用户怎么调都不再干预。
     */
    private const val K_ROUNDS_MIGRATED = "ai_tool_rounds_migrated_v2"
    private val gson = Gson()

    /**
     * 进程级 Application Context。
     *
     * 给"**脱离 Activity 也要活着**"的组件用 —— 目前是
     * [com.canglan.cyancodex.ai.AiTaskRunner] / [com.canglan.cyancodex.ai.AiTaskService]:
     * AI 请求要在界面被销毁后继续跑, 那时已经没有 Activity 可以借 context 了。
     *
     * ⛔ 存的是 `applicationContext`, 不是 Activity —— 存 Activity 就是内存泄漏。
     * 只在 [init] 里赋值一次; 别的地方只读。
     */
    lateinit var appCtx: Context
        private set

    fun init(ctx: Context) {
        appCtx = ctx.applicationContext
        sp = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        // 调试面板: 共用同一份 SharedPreferences (它自己在 SP 里另存键, 与这里的键不冲突)
        DebugStore.attach(sp)
        // 清理历史版本可能保存过的旧服务器地址(服务器地址现在固定内置)
        // ⚠️ 只清「旧版固定地址」那个键 (base_url); 调试面板的自定义地址是 debug_base_url, 不动它
        sp.edit().remove("base_url").apply()
        // 迁移旧版明文 token / AI Key -> 加密存储 (v1.0.9 及之前是明文)
        migrateLegacySecrets()
        // v1.0.50: 插件改为默认关闭 (此前"自定义背景"插件默认开启, 导致主页背景变黑且白天模式无效, 反馈 id=24)
        if (!sp.getBoolean("plugin_migrated_v150", false)) {
            sp.edit()
                .remove("plugin_dev.cyancodex.background")
                .remove("plugin_dev.cyancodex.example")
                .putBoolean("plugin_migrated_v150", true)
                .apply()
        }
    }

    private fun migrateLegacySecrets() {
        val edit = sp.edit()
        if (sp.getString("token_enc", null) == null) {
            val old = sp.getString("token", null)
            if (old != null) edit.putString("token_enc", Crypto.encryptOrNull(old))
            edit.remove("token")
        }
        if (sp.getString("ai_api_key_enc", null) == null) {
            val oldKey = sp.getString("ai_api_key", null)
            if (oldKey != null) edit.putString("ai_api_key_enc", Crypto.encryptOrNull(oldKey))
            edit.remove("ai_api_key")
        }
        edit.apply()
    }

    // ---------- 认证 (token 加密落盘, 明文仅内存短暂存在) ----------
    var token: String?
        get() = Crypto.decryptOrNull(sp.getString("token_enc", null))
        set(v) { sp.edit().putString("token_enc", Crypto.encryptOrNull(v)).apply() }

    fun saveUser(u: User) {
        sp.edit().putString("user_json", gson.toJson(u)).apply()
        token?.let { sp.edit().putString("token_enc", Crypto.encryptOrNull(it)).apply() }
    }

    fun user(): User? {
        val j = sp.getString("user_json", null) ?: return null
        return try { gson.fromJson(j, User::class.java) } catch (e: Exception) { null }
    }

    fun updatePoints(points: Int) {
        user()?.let { u ->
            saveUser(u.copy(points = points))
        }
    }

    fun isLoggedIn(): Boolean = !token.isNullOrBlank()

    /** 是否曾注册过账号 (首次进入 App 引导「注册」而非「登录」) */
    var hasRegistered: Boolean
        get() = sp.getBoolean("has_registered", false)
        set(v) { sp.edit().putBoolean("has_registered", v).apply() }

    fun logout() {
        sp.edit().remove("token_enc").remove("user_json").apply()
    }

    // ---------- 手动注入的 Cookie (隐藏调试验证入口, 2026-10-05) ----------
    //
    // 用途: 从浏览器里拷出已登录的 cookie, 直接搬进 App 当**登录凭证** ——
    //   省去在 App 里重新输密码/过验证码。
    //
    // ⚠️ 安全约束 (务必保持, 别为了图省事放宽):
    //   ① 与 token 同等对待 —— 经 Keystore 加密后才落 SharedPreferences, **绝不落明文**。
    //   ② **只注入给自家域名** (见 Api.kt 的 injectCookie) —— 这是第三条铁律,
    //      因为用户还配了自备 AI 域名 / 搜索引擎域名, 那些是**第三方**,
    //      把 cookie 发过去 = 把账号凭证交出去。注入点必须自己判断 host。
    //   ③ 日志 / 调试面板**永不打印 cookie 原文** —— 只显示"已设置(N 字符)"。
    //
    // 与 token 的关系: **两条并行的凭证通道**, 互不覆盖。
    //   token 为空但有 cookie 时, 服务端认 cookie 照样能用 (这正是本功能的目的)。
    var cookie: String?
        get() = Crypto.decryptOrNull(sp.getString("cookie_enc", null))?.takeIf { it.isNotBlank() }
        set(v) {
            val s = v?.trim()
            if (s.isNullOrBlank()) sp.edit().remove("cookie_enc").apply()
            else sp.edit().putString("cookie_enc", Crypto.encryptOrNull(s)).apply()
        }

    /** 是否已注入 cookie —— 供 UI 显示"清除"入口 / 状态栏提示 */
    fun hasCookie(): Boolean = !cookie.isNullOrBlank()

    /** 只清注入的 cookie, **不动** token / 用户信息 (用户明确要的语义) */
    fun clearCookie() {
        sp.edit().remove("cookie_enc").apply()
    }

    /**
     * 把用户粘贴的 cookie 洗成**请求头能用的形态**。
     *
     * 用户粘贴的来源五花八门, 这几种都得吃下:
     *  ① 浏览器 F12 → Network → Request Headers 里整行 `Cookie: a=1; b=2`
     *  ② Application → Cookies 表格里逐行拷出来的 `a=1` 多行文本
     *  ③ 只拷了值, 没有 `名字=`
     *  ④ 末尾带了换行/空格/分号
     *
     * 处理: 按行/分号切开 → 去掉 `Cookie:` 前缀 → 丢掉空段与无 `=` 的段 → 用 `; ` 重连。
     * ⛔ 不做 URL 编码 —— cookie 值里本来就可能含 `%`、`+` 等, 再编一次反而错。
     */
    fun sanitizeCookie(raw: String): String {
        val parts = raw
            .replace("\r\n", "\n").replace('\r', '\n')
            .split('\n', ';')
            .map { it.trim() }
            .map { it.removePrefix("Cookie:").removePrefix("cookie:").trim() }
            .filter { it.contains('=') }
        return parts.joinToString("; ")
    }

    // ---------- API 配置 ----------
    // 服务器地址以「XOR+Base64 混淆」形式内置, 避免 APK 明文暴露服务器 IP;
    // 运行时解码, App 开箱即连官方服务器, 用户无需手动填写。
    // 注意: 始终使用内置默认地址, 忽略历史版本可能保存过的旧地址(防止旧值覆盖新服务器)
    // 线路: 直连高防主服务器 IP (ServerDns 强制解析), 域名字符串仅用于 HTTPS 证书校验(SNI);
    //       直连失败时自动回退系统 DNS 解析出的备用线路
    // 2026-09-22: 迁移到新服务器（开源版已移除具体地址）
    private const val SERVER_KEY = "canglan"
    private const val SERVER_ENC = "CxUaFx9bQUwAHhdCAwIWBBkIHgodCw4eSRgOHkw="
    // 开源版本已移除官方高防服务器 IP（原值以 XOR+Base64 混淆内置）:
    // 需要直连自己的服务器时, 把 IP 用下面的 encode() 处理成 XOR+Base64 填进来;
    // 留空 = 不做直连, 完全走系统 DNS
    private const val SERVER_IP_ENC = ""

    private fun decode(enc: String): String {
        val bytes = java.util.Base64.getDecoder().decode(enc)
        val key = SERVER_KEY.toByteArray(Charsets.UTF_8)
        val out = ByteArray(bytes.size)
        for (i in bytes.indices) out[i] = (bytes[i].toInt() xor key[i % key.size].toInt()).toByte()
        return String(out, Charsets.UTF_8)
    }

    /** 打包自己的服务器地址用: XOR+Base64(与 decode 对称), 结果填进 SERVER_ENC / SERVER_IP_ENC */
    fun encode(plain: String): String {
        val key = SERVER_KEY.toByteArray(Charsets.UTF_8)
        val src = plain.toByteArray(Charsets.UTF_8)
        val out = ByteArray(src.size)
        for (i in src.indices) out[i] = (src[i].toInt() xor key[i % key.size].toInt()).toByte()
        return java.util.Base64.getEncoder().encodeToString(out)
    }

    private fun decodeServer(): String = decode(SERVER_ENC)

    /** 高防主服务器 IP (内置混淆; 空串表示不做直连) */
    val serverIp: String get() = runCatching { decode(SERVER_IP_ENC) }.getOrDefault("")

    val DEFAULT_BASE_URL: String get() = decodeServer()

    /**
     * 服务器地址。
     *
     * 正常走内置默认值；**调试面板解锁并填了自定义地址时优先用自定义的**
     * （整串，含端口，例如 `http://192.168.1.5:8080/`）——
     * 用来把 App 指向本地 / 局域网 / 自建后端调试。
     *
     * ⛔ Retrofit 要求 baseUrl 以 `/` 结尾，这里统一补上，免得用户少打一个斜杠就崩。
     */
    val baseUrl: String
        get() {
            // ★ 2026-10-05: 必须 `DebugStore.active` —— 调试面板总开关关掉后自定义地址**不生效**,
            //   自动退回内置默认。用户要的"关闭开关"若只关掉 UI 而地址还在偷偷生效, 那是假开关。
            val custom = runCatching { DebugStore.customBaseUrl }.getOrDefault("")
            if (custom.isBlank() || !runCatching { DebugStore.active }.getOrDefault(false)) {
                return DEFAULT_BASE_URL
            }
            return if (custom.endsWith("/")) custom else "$custom/"
        }

    /** 当前是否在用调试面板的自定义地址（同样要总开关开着才算生效） */
    val usingCustomBaseUrl: Boolean
        get() = runCatching {
            DebugStore.active && DebugStore.customBaseUrl.isNotBlank()
        }.getOrDefault(false)

    /** 需要直连的 API 主机名 (取自 baseUrl) */
    val apiHost: String
        get() = runCatching { java.net.URI(baseUrl).host ?: "" }.getOrDefault("")

    /** 该主机是否应强制解析到高防 IP (只作用于自己的服务器, 其它域名走系统 DNS) */
    fun forcedIpFor(host: String): String? =
        if (serverIp.isNotBlank() && host.equals(apiHost, ignoreCase = true)) serverIp else null

    // ---------- AI 配置 (用户自备 API) ----------
    var aiApiBase: String
        get() = sp.getString("ai_api_base", "https://api.openai.com/v1/")!!
        set(v) { sp.edit().putString("ai_api_base", if (v.endsWith("/")) v else "$v/").apply() }

    var aiApiKey: String
        get() = Crypto.decryptOrNull(sp.getString("ai_api_key_enc", null)) ?: ""
        set(v) { sp.edit().putString("ai_api_key_enc", Crypto.encryptOrNull(v)).apply() }

    /**
     * 是否配过 AI Key —— **只查键存不存在, 不做解密**。
     *
     * ⚠️ 别在组合期直接读 [aiApiKey]: 它要过 Android Keystore 做 AES-GCM 解密,
     * 一次几毫秒, 而 Compose 的组合函数会被反复调用 —— 放在 body 里就是每帧一次解密。
     * 需要"有没有配"这个布尔量时一律用这个。
     */
    fun hasAiKey(): Boolean = sp.getString("ai_api_key_enc", null)?.isNotBlank() == true

    /**
     * 显式选择「用官方 AI」—— 走服务器代理, 按积分计费。
     *
     * 为什么必须有这个开关: 配了自备 Key 之后, 界面上再也回不到官方 AI 了
     * (判断条件一直是"有没有 Key"), 用户想临时用一次官方能力只能把 Key 删掉。
     *
     * ⚠️ 默认值**不能**写死 true: 老用户已经配好 Key, 一升级就会被莫名其妙地
     * 推回官方计费。默认按"有没有 Key"推断 —— 没 Key 就是官方, 有 Key 就是直连,
     * 与升级前的行为完全一致。
     */
    var aiUseOfficial: Boolean
        get() = sp.getBoolean("ai_use_official", !hasAiKey())
        set(v) { sp.edit().putBoolean("ai_use_official", v).apply() }

    var aiModel: String
        get() = sp.getString("ai_model", "gpt-4o-mini")!!
        set(v) { sp.edit().putString("ai_model", v).apply() }

    /**
     * 「深度思考」开关 —— 输入框里那个 chip, 默认关。
     *
     * 关 = 一个思考参数都不发 (最稳, 跟上游默认一致);
     * 开 = 按 [aiThinkLevel] 发强度参数。两者分开存, 所以来回切开关不会丢掉用户选的强度。
     */
    var aiThinkOn: Boolean
        get() = sp.getBoolean("ai_think_on", false)
        set(v) { sp.edit().putBoolean("ai_think_on", v).apply() }

    /** 思考强度, 取值是 ai.ThinkLevel 的 id: low / medium / high (不含 auto) */
    var aiThinkLevel: String
        get() = sp.getString("ai_think_level", "medium")!!
        set(v) { sp.edit().putString("ai_think_level", v).apply() }

    /** 本次请求实际要用的思考档位 —— 开关关着就是 AUTO(不发参数) */
    fun effectiveThink(): com.canglan.cyancodex.ai.ThinkLevel =
        if (aiThinkOn) com.canglan.cyancodex.ai.ThinkLevel.of(aiThinkLevel)
        else com.canglan.cyancodex.ai.ThinkLevel.AUTO

    /**
     * 「获取模型」的结果缓存, 格式见 ai.encodeModels。
     * 空串 = 从没拉过。缓存是为了开配置面板时不必每次都等一次网络往返。
     */
    var aiModelList: String
        get() = sp.getString("ai_model_list", "")!!
        set(v) { sp.edit().putString("ai_model_list", v).apply() }

    // ---------- 智能搜索 (可选, 用户自备搜索引擎) ----------
    /** 搜索引擎 id (见 ai.SEARCH_ENGINES); 空串 = 没配, 输入框里的搜索 chip 就不显示 */
    var aiSearchEngine: String
        get() = sp.getString("ai_search_engine", "")!!
        set(v) { sp.edit().putString("ai_search_engine", v).apply() }

    /**
     * 搜索引擎的 Key —— 与 AI Key 一样加密落盘, 但**按引擎分开存**。
     *
     * ⛔ 曾经是单一槽位, 那是个真会咬人的坑: 换引擎不换 Key,
     *    于是 Exa 的 Key 会顶着「TinyFish API Key」的标签发出去,
     *    服务端回一句 `Invalid or expired API key`, 而输入框里是掩码,
     *    用户根本看不出里面装的其实是别人家的 Key。
     */
    fun searchKeyFor(engineId: String): String {
        if (engineId.isBlank()) return ""
        sp.getString(keySlot(engineId), null)?.let { return Crypto.decryptOrNull(it) ?: "" }
        return migrateLegacyKey(engineId)
    }

    /** 写入某一家引擎的 Key。只动它自己那一格, 绝不波及其它引擎 */
    fun setSearchKeyFor(engineId: String, v: String) {
        if (engineId.isBlank()) return
        sp.edit().putString(keySlot(engineId), Crypto.encryptOrNull(v)).apply()
    }

    /** 某一家引擎配过 Key 没有 —— 组合期用这个, 别解密 */
    fun hasSearchKeyFor(engineId: String): Boolean {
        if (engineId.isBlank()) return false
        if (sp.getString(keySlot(engineId), null)?.isNotBlank() == true) return true
        // 老版本的单槽位: 无法判断它原本属于哪一家, 只认「当前选中的那家」, 不猜
        return engineId == aiSearchEngine && sp.getString("ai_search_key_enc", null)?.isNotBlank() == true
    }

    /** 当前引擎的 Key (便利入口) */
    var aiSearchKey: String
        get() = searchKeyFor(aiSearchEngine)
        set(v) { setSearchKeyFor(aiSearchEngine, v) }

    /** 当前引擎配过 Key 没有 —— 同 [hasAiKey], 组合期用这个, 别解密 */
    fun hasSearchKey(): Boolean = hasSearchKeyFor(aiSearchEngine)

    private fun keySlot(engineId: String) = "ai_search_key_enc_$engineId"

    /**
     * 老版本(单槽位)升级上来时的搬运: 把 `ai_search_key_enc` 认领给**当前选中的那家**,
     * 搬完就把老槽位删掉, 免得它被第二家重复认领。
     * 只在读的时候触发(配置面板打开那一刻), 不在组合期跑。
     */
    private fun migrateLegacyKey(engineId: String): String {
        if (engineId != aiSearchEngine) return ""
        val legacy = sp.getString("ai_search_key_enc", null) ?: return ""
        val plain = Crypto.decryptOrNull(legacy) ?: return ""
        sp.edit().putString(keySlot(engineId), legacy).remove("ai_search_key_enc").apply()
        return plain
    }

    /** 「智能搜索」开关 (输入框里的 chip), 默认关 */
    var aiSearchOn: Boolean
        get() = sp.getBoolean("ai_search_on", false)
        set(v) { sp.edit().putBoolean("ai_search_on", v).apply() }

    /**
     * 「指令专家」开关 (输入框里的 chip) —— **默认开**。
     *
     * 开 = 给模型多挂两个工具: 查 MC 百科 + 核对指令语法, 并在提示词里告诉它什么时候用。
     * 这两个工具**都不花钱、不需要任何 Key**, 而且直接决定"写出来的指令能不能用",
     * 所以默认开; 只有上游明确不支持 `tools` 的用户才需要关掉
     * (关掉后行为与加这个功能之前**完全一致**)。
     */
    var aiCmdExpertOn: Boolean
        get() = sp.getBoolean("ai_cmd_expert_on", true)
        set(v) { sp.edit().putBoolean("ai_cmd_expert_on", v).apply() }

    /**
     * **「语法核对」开关** (输入框里的 chip) —— **默认开** (2026-10-05)。
     *
     * 用户原话:「ai 加一个专门核对语法的…就类似这样可以切换」——
     * 要的是一个**独立可切**的开关, 而不是把语法核对永久焊在「指令专家」里。
     *
     * ## 与「指令专家」[aiCmdExpertOn] 的关系
     * 「指令专家」是**总闸** —— 它同时管「查百科 `mc_wiki`」+「核语法 `check_command`」。
     * 本开关是**核语法的细粒度闸**:
     *
     * | 指令专家 | 语法核对 | 实际挂的工具 |
     * |---|---|---|
     * | 关 | 任意 | 无 (两个都不挂) |
     * | 开 | 开 | `mc_wiki` + `check_command` |
     * | 开 | 关 | 仅 `mc_wiki` (只查资料, 不强制自查) |
     *
     * ★ 为什么允许"只查资料不自查": 有时用户只是想问"execute 怎么用"(纯知识问答),
     *   让模型每轮都去调 `check_command` 是纯浪费 —— 那些情形下它没有指令要核。
     * ★ 关掉后**客户端自己的复查**([aiRecheckOn]) 也一并跳过 —— 见那里的注释。
     */
    var aiSyntaxCheckOn: Boolean
        get() = sp.getBoolean("ai_syntax_check_on", true)
        set(v) { sp.edit().putBoolean("ai_syntax_check_on", v).apply() }

    /**
     * **客户端复查**开关 —— **默认开** (2026-10-05)。
     *
     * 这是 2026-10-02 加的"不信模型说自己查过了"那道保险: 拿到最终正文后,
     * 客户端自己再拿 [com.canglan.cyancodex.ai.CommandCheck.audit] 验一遍 ```mc 代码块。
     *
     * ⚠️ 它**独立**于 [aiSyntaxCheckOn]: 那个管的是"模型挂不挂 `check_command` 工具",
     *    这个管的是"客户端跑不跑本机静态检查"。
     * ⚠️ 关掉它的代价: 用户报过的「石头 ID 都不对」那类问题就没人兜了 ——
     *    所以默认开, 只在用户明确嫌"复查发现问题→又让 AI 改一轮"太慢时才关。
     */
    var aiRecheckOn: Boolean
        get() = sp.getBoolean("ai_recheck_on", true)
        set(v) { sp.edit().putBoolean("ai_recheck_on", v).apply() }

    /**
     * **工具调用轮数上限** —— 模型最多"调工具 → 看结果 → 再调"几轮。
     *
     * 两条通道（自备 Key 直连 / 官方代理）**共用这一个值** —— 同一个概念不该有两个数。
     * 范围与默认值见 [com.canglan.cyancodex.ai.AI_TOOL_ROUNDS_MIN] / `_MAX` / `_DEFAULT`。
     *
     * ★ 为什么要给用户调: 加了「指令专家」之后, 一轮问答里模型可能要
     *   `check_command` 核一遍、`mc_wiki` 查一次、改完再核一遍 —— 默认 4 轮不够,
     *   用户撞上了「已连续调用工具 4 轮仍未得出结论」。现在默认 6, 且可调。
     *
     * ⚠️ 读的时候夹范围: 老版本没这个键 → 走默认值; 万一被写坏 → 兜回合法区间,
     *   免得 0 轮让工具彻底失效、或一个离谱的大数把用户的钱烧掉。
     *
     * ★★ 2026-10-05 一次性迁移: 上限从 12 → 50 时, **老用户存的旧值(如 6)会一直卡在 6** ——
     *   `sp.getInt` 只做 clamp, 不会因为默认值变大就把存量抬上去。用户看到设置里写着 6,
     *   会以为"上限改成 50"这个更新没生效。所以这里做一次**只升不降**的迁移:
     *   把**低于新默认值**的存量抬到新默认值; 用户手动调过、且大于等于新默认值的不动。
     *   ⛔ 用 [K_ROUNDS_MIGRATED] 标记, **只跑一次** —— 否则用户手动调回 5, 下次冷启动又被抬走,
     *     那就是"设置保存不了"的 bug。迁移后用户怎么调就是他的事。
     */
    var aiToolRounds: Int
        get() {
            runCatching {
                if (!sp.getBoolean(K_ROUNDS_MIGRATED, false)) {
                    val cur = sp.getInt("ai_tool_rounds", -1)
                    // -1 = 从没写过 → 走默认值即可, 不必写; 写了反而把"未设置"变成"已设置"。
                    if (cur in 1 until com.canglan.cyancodex.ai.AI_TOOL_ROUNDS_DEFAULT) {
                        sp.edit()
                            .putInt("ai_tool_rounds", com.canglan.cyancodex.ai.AI_TOOL_ROUNDS_DEFAULT)
                            .apply()
                    }
                    sp.edit().putBoolean(K_ROUNDS_MIGRATED, true).apply()
                }
            }
            return sp.getInt("ai_tool_rounds", com.canglan.cyancodex.ai.AI_TOOL_ROUNDS_DEFAULT)
                .coerceIn(
                    com.canglan.cyancodex.ai.AI_TOOL_ROUNDS_MIN,
                    com.canglan.cyancodex.ai.AI_TOOL_ROUNDS_MAX,
                )
        }
        set(v) {
            sp.edit().putInt(
                "ai_tool_rounds",
                v.coerceIn(
                    com.canglan.cyancodex.ai.AI_TOOL_ROUNDS_MIN,
                    com.canglan.cyancodex.ai.AI_TOOL_ROUNDS_MAX,
                ),
            ).apply()
        }

    // ---------- 快捷指令 (输入法/悬浮窗共用) ----------
    fun quickCommands(): MutableList<QuickCommand> {
        val j = sp.getString("quick_commands", null) ?: return mutableListOf()
        return try {
            gson.fromJson(j, object : TypeToken<MutableList<QuickCommand>>() {}.type)
        } catch (e: Exception) { mutableListOf() }
    }

    fun saveQuickCommands(list: List<QuickCommand>) {
        sp.edit().putString("quick_commands", gson.toJson(list)).apply()
    }

    fun addQuickCommand(qc: QuickCommand) {
        val list = quickCommands().filter { it.command != qc.command }.toMutableList()
        list.add(0, qc)
        if (list.size > 100) list.removeAt(list.size - 1)
        saveQuickCommands(list)
    }

    fun removeQuickCommand(command: String) {
        saveQuickCommands(quickCommands().filter { it.command != command })
    }

    // ---------- 最近使用 (IME 快捷) ----------
    fun recentCommands(): MutableList<String> {
        val j = sp.getString("recent_commands", null) ?: return mutableListOf()
        return try { gson.fromJson(j, object : TypeToken<MutableList<String>>() {}.type) } catch (e: Exception) { mutableListOf() }
    }

    fun addRecent(command: String) {
        val list = recentCommands().filter { it != command }.toMutableList()
        list.add(0, command)
        if (list.size > 30) list.removeAt(list.size - 1)
        sp.edit().putString("recent_commands", gson.toJson(list)).apply()
    }

    // ---------- 首页隐藏模块 (意见#68: 长按隐藏卡片) ----------
    private val hiddenModulesType = object : TypeToken<MutableList<String>>() {}.type

    /**
     * ★ 首页模块**不设任何限制** (2026-09-26 用户明确要求)。
     *
     * 用户原话: 「首页模块没必要限制吧，人家想关就关。点击我的那是能进入到设置的。」
     *
     * 之前这里钉了「输入法模式/悬浮窗/优化」三张指向设置页的卡, 属于**过度设计** ——
     * 设置页已经有稳定入口 (底栏「我的」, 见 [pinnedTabs]), 首页那几张卡关掉不影响可达性。
     * 用户想清空首页就让他清空, 首页有"点击这里恢复全部入口"兜底空状态, 不会卡死。
     */
    fun hiddenModules(): Set<String> {
        val j = sp.getString("hidden_modules", null) ?: return emptySet()
        return try {
            val list: MutableList<String> = gson.fromJson(j, hiddenModulesType)
            list.toSet()
        } catch (e: Exception) { emptySet() }
    }

    fun toggleHiddenModule(title: String) {
        val cur = hiddenModules().toMutableSet()
        if (!cur.add(title)) cur.remove(title)
        sp.edit().putString("hidden_modules", gson.toJson(cur.toList())).apply()
    }

    fun resetHiddenModules() {
        sp.edit().remove("hidden_modules").apply()
    }

    // ---------- 收藏缓存 (IME/悬浮窗离线可用) ----------
    var cachedFavorites: String
        get() = sp.getString("cached_favorites", "[]")!!
        set(v) { sp.edit().putString("cached_favorites", v).apply() }

    // ---------- 优化选项 ----------
    var reduceMotion: Boolean
        get() = sp.getBoolean("reduce_motion", false)
        set(v) { sp.edit().putBoolean("reduce_motion", v).apply() }

    var animationsScale: Float
        get() = sp.getFloat("anim_scale", 1f)
        set(v) { sp.edit().putFloat("anim_scale", v).apply() }

    fun clearCacheData() {
        sp.edit().remove("cached_favorites").remove("recent_commands").apply()
    }

    // ---------- 悬浮窗调节 (BNM 参考: 大小/透明度/位置持久化) ----------
    var floatWidthDp: Int
        get() = sp.getInt("float_width_dp", 300)
        set(v) { sp.edit().putInt("float_width_dp", v).apply() }

    var floatHeightDp: Int
        get() = sp.getInt("float_height_dp", 520)
        set(v) { sp.edit().putInt("float_height_dp", v).apply() }

    var floatOpacity: Float
        get() = sp.getFloat("float_opacity", 1f)
        set(v) { sp.edit().putFloat("float_opacity", v).apply() }

    var floatX: Int
        get() = sp.getInt("float_x", -1)   // -1 = 未设置(默认右上角)
        set(v) { sp.edit().putInt("float_x", v).apply() }

    var floatY: Int
        get() = sp.getInt("float_y", -1)
        set(v) { sp.edit().putInt("float_y", v).apply() }

    // 悬浮球位置 (BNM 式悬浮球, -1 = 默认右侧中部)
    var floatBallX: Int
        get() = sp.getInt("float_ball_x", -1)
        set(v) { sp.edit().putInt("float_ball_x", v).apply() }

    var floatBallY: Int
        get() = sp.getInt("float_ball_y", -1)
        set(v) { sp.edit().putInt("float_ball_y", v).apply() }

    // 悬浮球大小 (dp, 小/中/大)
    var floatBallSizeDp: Int
        get() = sp.getInt("float_ball_size_dp", 52)
        set(v) { sp.edit().putInt("float_ball_size_dp", v).apply() }

    // ---------- 插件 ----------
    // 默认关闭: 需用户在设置→插件里主动开启 (避免默认改动外观, 反馈 id=24)
    fun pluginEnabled(id: String): Boolean = sp.getBoolean("plugin_$id", false)
    fun setPluginEnabled(id: String, v: Boolean) { sp.edit().putBoolean("plugin_$id", v).apply() }

    // ---------- AI 对话 (JSON, 会话内持久: 切页/重进不丢, 意见 id=12) ----------
    // ⚠️ 已被多对话取代 (见 ai_convs), 仅保留给老数据迁移读取, 不再写入。
    var aiMessages: String
        get() = sp.getString("ai_messages", "[]")!!
        set(v) { sp.edit().putString("ai_messages", v).apply() }

    // ---------- AI 多对话 (2026-09-27) ----------
    // 存 List<AiConv> 的 JSON。读出口由 ai.AiConvStore 统一负责 (含老数据迁移)。
    var aiConvs: String
        get() = sp.getString("ai_convs", "[]")!!
        set(v) { sp.edit().putString("ai_convs", v).apply() }

    /** 当前会话 id; 空串 = 还没有会话 */
    var aiCurrentConv: String
        get() = sp.getString("ai_current_conv", "")!!
        set(v) { sp.edit().putString("ai_current_conv", v).apply() }

    /** ai_convs 这个键是否已经写过 —— 用来区分「从没用过多对话」和「会话被删光了」 */
    fun hasAiConvs(): Boolean = sp.contains("ai_convs")

    // ---------- 自定义背景图片 (意见 id=22) ----------
    var backgroundImage: String
        get() = sp.getString("background_image", "")!!
        set(v) { sp.edit().putString("background_image", v).apply() }

    // ---------- 外观 ----------
    // theme_mode: system=跟随系统 / light=白天 / dark=夜晚 (默认跟随系统)
    var themeMode: String
        get() = sp.getString("theme_mode", "system")!!
        set(v) { sp.edit().putString("theme_mode", v).apply() }

    // 主题来源: brand=品牌默认配色 / dynamic=莫奈取色(需 Android 12+) / custom=自定义主色
    var themeSource: String
        get() = sp.getString("theme_source", "brand")!!
        set(v) { sp.edit().putString("theme_source", v).apply() }

    // 自定义主色 (theme_source=custom 时生效), 形如 #4ADE80
    var themeCustomColor: String
        get() = sp.getString("theme_custom_color", "#4ADE80")!!
        set(v) { sp.edit().putString("theme_custom_color", v).apply() }

    // ---------- 底部导航: 隐藏的 Tab 路由 (首页/我的 不参与) ----------
    private val hiddenTabsType = object : TypeToken<MutableList<String>>() {}.type

    /**
     * ★ 不参与隐藏的 Tab (2026-09-26)：「首页」是默认落点，「我的」是**设置页的唯一稳定入口**。
     *
     * 用户原话: 「点击我的那是能进入到设置的」—— 所以**设置页里根本不该给出"关掉我的"这个选项**
     * (SettingsScreen 的列表已经不含「我的」)。这里的过滤是**第二道保险**:
     * 旧版本存下的 `hidden_tabs` 里可能还写着 `profile`, 不加这层用户升级后底栏就没有「我的」了。
     *
     * 在**读出口**统一过滤, 而不是只靠 UI 不显示:
     * ① UI 不显示只是"当下点不到", 历史数据仍会生效;
     * ② 底栏渲染、设置页开关都消费这一个函数, 在这一层过滤 = 全链路一致。
     */
    private val pinnedTabs = setOf("home", "profile")

    fun hiddenTabs(): Set<String> {
        val j = sp.getString("hidden_tabs", null) ?: return emptySet()
        return try {
            val list: MutableList<String> = gson.fromJson(j, hiddenTabsType)
            // 过滤掉不参与隐藏的 Tab —— 顺带把历史脏数据"治好"
            list.toSet() - pinnedTabs
        } catch (e: Exception) { emptySet() }
    }

    /** 该 Tab 是否参与隐藏 (设置页列表据此过滤; 目前只有「我的」不参与) */
    fun isTabPinnable(route: String): Boolean = route !in pinnedTabs

    fun toggleHiddenTab(route: String) {
        if (!isTabPinnable(route)) return
        val cur = hiddenTabs().toMutableSet()
        if (!cur.add(route)) cur.remove(route)
        sp.edit().putString("hidden_tabs", gson.toJson(cur.toList())).apply()
    }

    fun resetHiddenTabs() { sp.edit().remove("hidden_tabs").apply() }

    // ---------- 液态玻璃 (Liquid Glass) ----------
    // 默认关闭; 基于开源库 io.github.kyant0:backdrop 实现; 连续 2 次启动闪退会自动关闭
    var liquidGlass: Boolean
        get() = sp.getBoolean("liquid_glass", false)
        set(v) { sp.edit().putBoolean("liquid_glass", v).apply() }

    // 启动保护状态 (commit 同步写盘, 保证崩溃前也落盘):
    //  - glassBootPending: 上次启动是否"未存活"(true = 上次启动后没等到健康标记就崩了)
    //  - glassCrashCount : 累计的连续启动闪退次数
    var glassBootPending: Boolean
        get() = sp.getBoolean("liquid_glass_boot_pending", false)
        set(v) { sp.edit().putBoolean("liquid_glass_boot_pending", v).commit() }

    var glassCrashCount: Int
        get() = sp.getInt("liquid_glass_crash_count", 0)
        set(v) { sp.edit().putInt("liquid_glass_crash_count", v).commit() }

    // ---------- 生成历史 (本地兜底: 离线/未登录也保存) ----------
    fun localHistory(): List<GenHistory> {
        val j = sp.getString("local_history", null) ?: return emptyList()
        return try {
            gson.fromJson(j, object : TypeToken<List<GenHistory>>() {}.type)
        } catch (e: Exception) { emptyList() }
    }

    fun addLocalHistory(genType: String, inputJson: String, outputCommand: String) {
        val list = localHistory().toMutableList()
        list.add(0, GenHistory(
            id = System.currentTimeMillis(),
            genType = genType, inputJson = inputJson, outputCommand = outputCommand, createdAt = null,
        ))
        if (list.size > 200) list.removeAt(list.size - 1)
        sp.edit().putString("local_history", gson.toJson(list)).apply()
    }

    fun removeLocalHistory(outputCommand: String) {
        sp.edit().putString("local_history", gson.toJson(localHistory().filter { it.outputCommand != outputCommand })).apply()
    }

    // ---------- 公告已读 (commit 同步写盘, 防止进程被杀丢失导致反复弹窗) ----------
    var lastSeenAnnouncementId: Long
        get() = sp.getLong("last_seen_announcement", 0)
        set(v) { sp.edit().putLong("last_seen_announcement", v).commit() }

    // 已发系统通知的公告 id (与已读分开, 避免通知逻辑干扰弹窗逻辑)
    var lastNotifiedAnnouncementId: Long
        get() = sp.getLong("last_notified_announcement", 0)
        set(v) { sp.edit().putLong("last_notified_announcement", v).commit() }

    // 已发系统通知的站内信 id (新增消息才提醒)
    var lastNotifiedMessageId: Long
        get() = sp.getLong("last_notified_message", 0)
        set(v) { sp.edit().putLong("last_notified_message", v).commit() }

    // 设备唯一 ID (浏览量去重用, 首次生成后固定)
    val deviceId: String
        get() {
            sp.getString("device_id", null)?.let { return it }
            val id = java.util.UUID.randomUUID().toString()
            sp.edit().putString("device_id", id).apply()
            return id
        }

    /** 设备展示名 (上报给服务器, 用于「最近登录设备」列表) */
    val deviceName: String
        get() {
            val custom = sp.getString("device_name", null)
            if (!custom.isNullOrBlank()) return custom
            val brand = android.os.Build.MANUFACTURER ?: ""
            val model = android.os.Build.MODEL ?: ""
            val n = (brand + " " + model).trim()
            return n.ifBlank { "Android 设备" }
        }

    var deviceNameOverride: String?
        get() = sp.getString("device_name", null)
        set(v) { sp.edit().putString("device_name", v).apply() }

    // ---------- 后台常驻收消息 ----------
    /** 是否开启后台常驻 (前台服务轮询, 消息实时到达) */
    var backgroundWatch: Boolean
        get() = sp.getBoolean("bg_watch", true)
        set(v) { sp.edit().putBoolean("bg_watch", v).apply() }

    /** 轮询间隔 (秒), 最小 30 */
    var watchIntervalSec: Int
        get() = sp.getInt("bg_watch_interval", 60).coerceAtLeast(30)
        set(v) { sp.edit().putInt("bg_watch_interval", v.coerceAtLeast(30)).apply() }

    /** 是否已提示过忽略电池优化 */
    var batteryTipShown: Boolean
        get() = sp.getBoolean("battery_tip_shown", false)
        set(v) { sp.edit().putBoolean("battery_tip_shown", v).apply() }

    fun cacheSizeBytes(): Long = 0  // 占位: 主要缓存都在内存/网络
}
