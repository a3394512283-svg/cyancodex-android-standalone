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
        // 退出登录后停掉后台常驻收消息 (不再需要前台服务)
        com.canglan.cyancodex.CyanCodexApp.instance?.let {
            com.canglan.cyancodex.data.MessageWatchService.stop(it)
        }
        if (old == null) return
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
