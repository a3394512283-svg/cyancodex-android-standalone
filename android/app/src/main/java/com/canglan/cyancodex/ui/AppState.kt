package com.canglan.cyancodex.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.canglan.cyancodex.data.Session
import com.canglan.cyancodex.data.User

/**
 * 全局可观察应用状态 (登录态/积分等)
 */
object AppState {

    var user by mutableStateOf<User?>(Session.user())
        private set
    var token by mutableStateOf<String?>(Session.token)
        private set
    var themeMode by mutableStateOf(Session.themeMode)
        private set

    fun applyThemeMode(v: String) {
        Session.themeMode = v
        themeMode = v
    }

    // 主题来源: brand / dynamic / custom
    var themeSource by mutableStateOf(Session.themeSource)
        private set
    // 自定义主色 (#RRGGBB)
    var themeCustomColor by mutableStateOf(Session.themeCustomColor)
        private set
    // 液态玻璃开关 —— 这是**用户设置**(设置页开关显示的就是它)。
    // ⚠️ 改它**不会**立刻改变界面外观, 见下面 glassActiveNow 的说明。
    var liquidGlass by mutableStateOf(Session.liquidGlass)
        private set
    /**
     * 本次**已生效**的玻璃状态: 由 MainActivity.onCreate 刷新, 拨开关不会改它。
     *
     * 为什么要冻结成"下次启动生效"(2026-09-26 用户要求):
     * 玻璃开关会一次性改变底栏 / 返回键 / 输入条 / 开关 / 好友底栏等一大批组件的外观与布局,
     * 点一下整个界面"啪"地换一套长相 —— 观感很突兀, 用户容易以为点错了别的东西。
     * 所以改成"设置立刻保存, 外观下次启动生效", 设置页给明确提示 + 一键重启入口。
     *
     * 注意它是**普通属性而不是 mutableStateOf** —— 这是刻意的:
     * 这样开关变化不会触发任何重组, 界面才会真的保持原样直到重启。
     *
     * 刷新时机 = MainActivity.onCreate (见 refreshGlassAtLaunch), 所以:
     * 「拨开关」不刷新 → 外观不变; 「重建 Activity / 重开 App」刷新 → 外观切换。
     */
    private var glassFrozen: Boolean = Session.liquidGlass
    val glassActiveNow: Boolean get() = glassFrozen

    /**
     * 把"用户设置"刷成"当前生效值"。
     *
     * 只由 MainActivity.onCreate 调用, 且**必须早于 setContent** ——
     * 这样 Compose 第一次组合时读到的就已经是新值, 不会先画一帧旧外观。
     */
    fun refreshGlassAtLaunch() {
        glassFrozen = Session.liquidGlass
    }
    // 底部导航隐藏的 Tab
    var hiddenTabs by mutableStateOf(Session.hiddenTabs())
        private set
    // 首页隐藏的模块
    var hiddenModules by mutableStateOf(Session.hiddenModules())
        private set

    fun applyThemeSource(v: String) {
        Session.themeSource = v
        themeSource = v
    }

    fun applyThemeCustomColor(v: String) {
        Session.themeCustomColor = v
        themeCustomColor = v
    }

    fun applyLiquidGlass(v: Boolean) {
        // 只写持久化值 + 开关状态; 刻意**不**动 glassFrozen —— 外观要重启后才变。
        // 改完界面不会重排, 设置页会提示"重启后生效"。
        Session.liquidGlass = v
        liquidGlass = v
    }

    // ---------- 自定义背景图片 (意见 id=22, 2026-09-26 改为**全局生效**) ----------
    // 之前只画在主页内容里, 其他页面看不到。现在提到 MainActivity 最底层做全局背景,
    // 各页 Scaffold 的默认容器色被 Theme 改成透明(见 CyanCodexTheme), 背景图才透得出来。
    // 这里做成 mutableStateOf 而不是直接读 Session: 设置页换图后主题要立刻重组,
    // 透明底与背景图才会即时生效(换图片不属于"布局突变", 不需要重启)。
    var backgroundImage by mutableStateOf(Session.backgroundImage)
        private set

    fun applyBackgroundImage(path: String) {
        Session.backgroundImage = path
        backgroundImage = path
    }

    fun toggleHiddenTab(route: String) {
        Session.toggleHiddenTab(route)
        hiddenTabs = Session.hiddenTabs()
    }

    fun resetHiddenTabs() {
        Session.resetHiddenTabs()
        hiddenTabs = Session.hiddenTabs()
    }

    fun toggleHiddenModule(route: String) {
        Session.toggleHiddenModule(route)
        hiddenModules = Session.hiddenModules()
    }

    fun resetHiddenModules() {
        Session.resetHiddenModules()
        hiddenModules = Session.hiddenModules()
    }

    fun setLogin(u: User, t: String) {
        Session.token = t
        Session.saveUser(u)
        user = u
        token = t
        // 登录后开启后台常驻收消息 (前台服务)
        com.canglan.cyancodex.CyanCodexApp.instance?.let {
            com.canglan.cyancodex.data.MessageWatchService.start(it)
        }
    }

    /** 仅更新令牌 (改密后服务端会作废旧令牌并回发新令牌) */
    fun updateToken(t: String) {
        Session.token = t
        token = t
    }

    /**
     * ★★ **导入登录态并换取登录** (2026-10-05 重写)。
     *
     * 用户原话:「我的调试的 cookie 一直导出不了，我的 cookie 是指登录态之类的，
     * 反正输进去能进行登陆就行」。
     *
     * ## 为什么不再是"用 Cookie 登录"
     * 2026-10-05 实测确认: **服务器从不下发 `Set-Cookie`**（`api/auth/captcha` 与
     * `api/auth/login` 的响应头里都没有该字段），认证完全靠 `Authorization: Bearer <token>`。
     * 所以"密码登录后导出 cookie"这条路**物理上不存在** —— 那正是用户报
     * 「登录状态下获取和重新登陆一次再获取都不行」的根因。
     *
     * 现在统一成**导入登录态**: 粘贴的内容由 [Session.parseLoginState] 自动识别,
     * token 与 cookie 都能吃下。
     *
     * ## 流程
     * 1. 解析输入 → 拿到 `token` / `cookie`（两者可只有一个）;
     * 2. **先落盘, 再重建 ApiClient** —— 拦截器才会带上对应的 `Authorization` / `Cookie` 头;
     * 3. 调 `api/auth/me` 做**真正的验证**: 服务端认这个凭证才返回用户信息。
     *    过期 / 无效在这里就失败, 而不是假装登录成功, 让用户在一堆"登录已过期"里自己猜;
     * 4. 成功 → 若**没有**真 token（只靠 cookie 认的身份），才写哨兵 [Session.COOKIE_TOKEN]
     *    让全 App 的 `token != null` 判定成立; 有真 token 时**保持真 token**,
     *    否则会把一个假凭证当成登录态存下来;
     * 5. **失败 → 回滚**到导入前的凭证 (见下面的 prev 快照)。
     *    没有这一步的话, 用户粘错一个 Cookie 就会把原本好好的登录**顶掉** ——
     *    "只是想试一下"变成"当场被踢下线", 代价太大。
     *
     * @param raw 用户粘贴的登录态文本(带标签格式 / 裸 token / 裸 cookie 都行)
     * @return 成功时 `Result.success(用户)`; 失败时 `Result.failure` 且 message 是**给用户看**的原因
     */
    suspend fun importLoginState(raw: String): Result<User> = runCatching {
        val st = Session.parseLoginState(raw)
        if (st.isEmpty) {
            throw RuntimeException("没有识别到可用的登录态（需要令牌或形如 a=1; b=2 的 Cookie）")
        }
        // ---- 导入前快照, 失败要回滚 (见上面第 5 点) ----
        val prevToken = Session.token
        val prevCookie = Session.cookie

        // ---- 先落盘 ----
        // ⛔⛔ **互斥清理**: 导入的是一份"完整登录态快照", 没带的那一路**必须清掉**。
        //   否则会出现"旧令牌 + 新 Cookie 一起发出去" —— 服务端优先认 `Authorization`,
        //   于是 `me()` 返回的是**旧账号**, 用户以为换了号, 实际上根本没换。
        //   这个坑很隐蔽: 界面显示登录成功, 但账号是错的。
        if (st.token != null) {
            Session.token = st.token
            if (st.cookie == null) Session.clearCookie()
        } else {
            Session.token = null          // putString(key, null) 等价于 remove
            Session.cookie = st.cookie
        }
        // 重建客户端: 拦截器每次请求现读 Session, 但连接池/拦截器链在旧实例上,
        // 重建一次最稳(与 DebugScreen/AuthScreen 保存凭证后的做法一致)。
        com.canglan.cyancodex.data.ApiClient.reset()

        val r = runCatching { com.canglan.cyancodex.data.ApiClient.get().me() }
        val u = r.getOrNull()?.data
        if (r.isFailure || r.getOrNull()?.ok != true || u == null) {
            // ★ 回滚 —— 把刚才写进去的凭证还原, 用户不至于因为"试一下"而掉线。
            Session.token = prevToken
            Session.cookie = prevCookie
            com.canglan.cyancodex.data.ApiClient.reset()
            val why = r.exceptionOrNull()?.message?.takeIf { it.isNotBlank() }
                ?: r.getOrNull()?.error?.takeIf { it.isNotBlank() }
                ?: if (st.token != null)
                    "服务端不认这个令牌（可能已过期或被吊销）"
                else
                    "服务端没有通过这个 Cookie 识别到登录态（可能已过期，或该 Cookie 不属于本服务器）"
            // ★ 埋点 (2026-10-07): 导入失败是"看起来像 bug 的正常结果", 必须留痕
            com.canglan.cyancodex.data.DebugStore.logE("Auth", "登录态导入失败 → 已回滚: $why")
            throw RuntimeException(why)
        }
        // ★ 只有"靠 cookie 认的身份"才需要哨兵占位 —— 有真 token 时别覆盖它。
        if (st.token == null) Session.token = Session.COOKIE_TOKEN
        Session.saveUser(u)
        com.canglan.cyancodex.data.DebugStore.logI(
            "Auth",
            "登录态导入成功: ${u.nickname ?: u.username ?: "当前账号"}" +
                if (st.token == null) " (Cookie 登录)" else " (令牌登录)",
        )
        user = u
        token = Session.token
        // 登录后开启后台常驻收消息 (与 setLogin 一致)
        com.canglan.cyancodex.CyanCodexApp.instance?.let {
            com.canglan.cyancodex.data.MessageWatchService.start(it)
        }
        u
    }

    /**
     * 退出登录:
     * 1) 立即清本地状态 (UI 马上回到登录页, 不等网络)
     * 2) 用「退出前的令牌」在应用级协程里通知服务端吊销该令牌
     *    (不能用页面级 scope: 页面随登出被销毁会把请求一起取消; 也不能依赖 Session.token,
     *     因为本地已清空, 所以这里显式把令牌传给接口)
     */
    fun logout() {
        val old = token
        Session.logout()
        user = null
        token = null
        // ★ Cookie 登录态下**必须连 cookie 一起清** (2026-10-05): 只清 token 的话,
        //   拦截器还会继续把 cookie 发出去 —— 用户以为"退出了", 实际上请求仍带着完整账号权限。
        //   而且哨兵值也不是真凭证, 发给 logout 接口毫无意义。
        val wasCookieLogin = old == Session.COOKIE_TOKEN
        if (wasCookieLogin) Session.clearCookie()
        // 退出登录后停掉后台常驻收消息 (不再需要前台服务)
        com.canglan.cyancodex.CyanCodexApp.instance?.let {
            com.canglan.cyancodex.data.MessageWatchService.stop(it)
        }
        if (old == null || wasCookieLogin) return
        // 独立线程 + runBlocking: 不依赖页面生命周期, 也不依赖任何协程作用域, 确保请求一定发出去
        Thread {
            try {
                val r = kotlinx.coroutines.runBlocking {
                    com.canglan.cyancodex.data.ApiClient.get()
                        .logout("Bearer $old", com.canglan.cyancodex.data.LogoutReq(false))
                }
                android.util.Log.i("CyanLogout", "server logout ok=" + r.ok + " err=" + r.error)
            } catch (e: Throwable) {
                android.util.Log.e("CyanLogout", "server logout failed: " + e, e)
            }
        }.start()
    }

    fun patchUser(
        points: Int? = null,
        aiUnlocked: Boolean? = null,
        nickname: String? = null,
        email: String? = null,
        gender: String? = null,
        avatar: String? = null,
    ) {
        val cur = user ?: return
        val upd = cur.copy(
            points = points ?: cur.points,
            aiUnlocked = aiUnlocked ?: cur.aiUnlocked,
            nickname = nickname ?: cur.nickname,
            email = email ?: cur.email,
            gender = gender ?: cur.gender,
            avatar = avatar ?: cur.avatar,
        )
        Session.saveUser(upd)
        user = upd
    }
}
