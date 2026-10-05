package com.canglan.cyancodex

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import com.canglan.cyancodex.data.AnnouncementNotifier
import com.canglan.cyancodex.data.AppForeground
import com.canglan.cyancodex.data.Session
import com.canglan.cyancodex.data.SpeedTest
import com.canglan.cyancodex.data.TamperDetector
import com.canglan.cyancodex.plugin.PluginManager

class CyanCodexApp : Application() {
    override fun onCreate() {
        super.onCreate()
        instance = this
        Session.init(this)
        registerForegroundTracking()
        // 液态玻璃启动保护 (连续闪退 2 次自动关闭), 必须在渲染界面之前执行
        guardLiquidGlass()
        // 客户端检测: 启动即计算当前 APK 签名, 后续所有请求携带 X-App-Signature
        TamperDetector.init(this)
        // 插件系统: 复制内置插件 + 加载 (设置→插件 可管理)
        PluginManager.init(this)
        // 新公告系统通知: 建渠道 + 立即检查一次 + 注册周期检查
        AnnouncementNotifier.ensureChannel(this)
        AnnouncementNotifier.checkNow(this)
        AnnouncementNotifier.schedule(this)
        // 后台常驻收消息的服务渠道 (真正的启动在 MainActivity / 开机 / 登录后, 避免后台启动被系统拒绝)
        com.canglan.cyancodex.data.MessageWatchService.ensureChannel(this)
        // 工具箱: 网络测速的历史记录存储
        SpeedTest.init(this)
    }

    companion object {
        /** 全局 Application 引用 (退出登录时需要停掉后台服务) */
        @Volatile
        var instance: CyanCodexApp? = null
            private set
    }

    /**
     * 维护「App 现在对用户可见吗」。
     *
     * 只有一个消费者: [com.canglan.cyancodex.ai.AiTaskService.finish] ——
     * AI 回答结束时若用户已经切走, 补一条「回答好了」通知; 人就在界面里看着就不弹。
     *
     * 数 started 的 Activity 个数而不是用布尔: 见 [AppForeground] 的说明。
     * `coerceAtLeast(0)` 是防御性的 —— 正常不会为负, 但真为负了会让"可见"永远判成 false,
     * 症状是"完成通知该弹的时候不弹", 属于难查的隐性故障, 不值得省这一下。
     */
    private fun registerForegroundTracking() {
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                AppForeground.startedActivities++
            }

            override fun onActivityStopped(activity: Activity) {
                AppForeground.startedActivities = (AppForeground.startedActivities - 1).coerceAtLeast(0)
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }

    /**
     * 液态玻璃「启动闪退自动关闭」保护。
     *
     * 原理: 每次启动若开启了液态玻璃, 先把 glassBootPending 置 true 落盘; 应用存活 5 秒后
     * 再清 false。若某次启动在 5 秒内崩溃(进程被杀, 来不及清 false), 下次启动就会看到
     * pending=true, 于是累计一次闪退。连续闪退达到 2 次 -> 自动关闭液态玻璃并复位,
     * 避免用户被一直闪退卡死进不去 App。
     */
    private fun guardLiquidGlass() {
        if (!Session.liquidGlass) return
        var crashes = Session.glassCrashCount
        if (Session.glassBootPending) crashes += 1          // 上次启动没能存活 = 闪退了一次
        if (crashes >= 2) {
            Session.liquidGlass = false                     // 连续闪退 2 次: 自动关闭
            Session.glassCrashCount = 0
            Session.glassBootPending = false
        } else {
            Session.glassCrashCount = crashes
            Session.glassBootPending = true
            // 存活 5 秒视为启动成功 -> 清零
            Handler(Looper.getMainLooper()).postDelayed({
                Session.glassBootPending = false
                Session.glassCrashCount = 0
            }, 5000L)
        }
    }
}
