package com.canglan.cyancodex

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import com.canglan.cyancodex.data.AnnouncementNotifier
import com.canglan.cyancodex.data.AppConfig
import com.canglan.cyancodex.data.AppForeground
import com.canglan.cyancodex.data.DebugStore
import com.canglan.cyancodex.data.Session
import com.canglan.cyancodex.data.SpeedTest
import com.canglan.cyancodex.data.TamperDetector
import com.canglan.cyancodex.plugin.PluginManager
import kotlinx.coroutines.launch

class CyanCodexApp : Application() {
    override fun onCreate() {
        super.onCreate()
        instance = this
        val t0 = System.currentTimeMillis()

        Session.init(this)
        // ★ 调试埋点从**这里**开始 —— 必须晚于 Session.init:
        //   DebugStore 的 SharedPreferences 是在 Session.init → DebugStore.attach 里注入的,
        //   在此之前打日志会被 [DebugStore.active] 的 runCatching 兜成 false 而静默丢弃。
        DebugStore.logI("App", "启动 · $packageName · v${appVersion()}")

        registerForegroundTracking()
        DebugStore.logD("App", "前台跟踪已注册 (AppForeground)")

        // 液态玻璃启动保护 (连续闪退 2 次自动关闭), 必须在渲染界面之前执行
        guardLiquidGlass()

        // 客户端检测: 启动即计算当前 APK 签名, 后续所有请求携带 X-App-Signature
        TamperDetector.init(this)
        DebugStore.logD("App", "签名检测初始化 (X-App-Signature)")
        // 插件系统: 复制内置插件 + 加载 (设置→插件 可管理)
        PluginManager.init(this)
        DebugStore.logD("App", "插件系统初始化")
        // 新公告系统通知: 建渠道 + 立即检查一次 + 注册周期检查
        AnnouncementNotifier.ensureChannel(this)
        AnnouncementNotifier.checkNow(this)
        AnnouncementNotifier.schedule(this)
        DebugStore.logD("App", "公告通知: 建渠道 + 立即检查 + 周期任务")
        // 后台常驻收消息的服务渠道 (真正的启动在 MainActivity / 开机 / 登录后, 避免后台启动被系统拒绝)
        com.canglan.cyancodex.data.MessageWatchService.ensureChannel(this)
        DebugStore.logD("App", "消息服务通知渠道已建")
        // 工具箱: 网络测速的历史记录存储
        SpeedTest.init(this)

        // ★★ 2026-10-17: 配置中心 —— 拉公告 / 远程开关 / 最新版本。
        //   刻意**异步且不等待**: 它是"让界面更准"的信息, 不该拖慢冷启动。
        //   失败也不做任何提示 (离线启动是正常场景, 用缓存值即可)。
        refreshRemoteConfig()

        DebugStore.logI("App", "启动流程结束 · 耗时 ${System.currentTimeMillis() - t0}ms")
    }

    /**
     * 后台刷新服务端配置。
     *
     * 只发一次请求, 不重试 —— 拿不到就用上一次的缓存 (见 [AppConfig.cachedFlags])。
     * 用 IO 调度器而不是裸 Thread, 是为了让它跟其它协程一起被结构化并发管理。
     */
    private fun refreshRemoteConfig() {
        runCatching {
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                val ok = AppConfig.refresh(this@CyanCodexApp)
                DebugStore.logD("AppConfig", if (ok) "远端配置已刷新" else "远端配置拉取失败, 使用缓存")
            }
        }.onFailure {
            DebugStore.logW("AppConfig", "配置刷新启动失败: ${it.message}")
        }
    }

    /**
     * 版本串 `2.0.0(20000)` —— 只给日志用。
     *
     * ⚠️ 不用 `BuildConfig.VERSION_NAME`: 本工程 `buildFeatures.buildConfig` 未开启,
     *    那个类不存在, 直接引用会编译失败。走 PackageManager 最稳, 且 `runCatching` 兜底。
     */
    private fun appVersion(): String = runCatching {
        val pi = packageManager.getPackageInfo(packageName, 0)
        "${pi.versionName}(${androidx.core.content.pm.PackageInfoCompat.getLongVersionCode(pi)})"
    }.getOrDefault("?")

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
        if (!Session.liquidGlass) {
            DebugStore.logD("Glass", "液态玻璃未开启, 跳过启动保护")
            return
        }
        var crashes = Session.glassCrashCount
        if (Session.glassBootPending) crashes += 1          // 上次启动没能存活 = 闪退了一次
        if (crashes >= 2) {
            Session.liquidGlass = false                     // 连续闪退 2 次: 自动关闭
            Session.glassCrashCount = 0
            Session.glassBootPending = false
            // ★ W 级: 这是"用户看到外观突然变了"的原因, 必须显眼
            DebugStore.logW("Glass", "连续闪退 $crashes 次 → 已自动关闭液态玻璃 (防止用户被闪退卡死)")
        } else {
            Session.glassCrashCount = crashes
            Session.glassBootPending = true
            DebugStore.logD("Glass", "启动保护: 累计闪退计数 $crashes, 待确认")
            // 存活 5 秒视为启动成功 -> 清零
            Handler(Looper.getMainLooper()).postDelayed({
                Session.glassBootPending = false
                Session.glassCrashCount = 0
                DebugStore.logD("Glass", "已存活 5 秒, 闪退计数清零")
            }, 5000L)
        }
    }
}
