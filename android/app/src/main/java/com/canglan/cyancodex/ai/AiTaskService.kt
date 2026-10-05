package com.canglan.cyancodex.ai

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import com.canglan.cyancodex.MainActivity
import com.canglan.cyancodex.R
import com.canglan.cyancodex.data.AppForeground

/**
 * AI 回答任务的**前台服务外壳** (2026-10-02, 2026-10-05 强化保活)。
 *
 * ## 为什么需要它
 * 用户反馈「AI 任务一切后台就断」。拆开是**两层**原因, 必须分开治:
 *
 *  ① **Activity 被回收 → 协程被取消**。原来请求跑在 `rememberCoroutineScope()` 里,
 *     这个 scope 绑在 composition 上 —— Activity 一销毁(系统内存回收 / 从最近任务划掉 /
 *     配置变化)整棵协程树就被 cancel, 请求当场断。
 *     → 治法是 [AiTaskRunner]: 把请求搬到**进程级**作用域, 与界面生命周期解耦。
 *
 *  ② **进程被冻结/回收**。Android 对"没有前台服务的后台应用"会限制 CPU 与网络
 *     (Doze / App Standby Buckets)。请求再长一点, 即使协程还在, socket 也会被掐。
 *     → 治法是本服务: 请求期间挂一个前台服务, 进程升到 "foreground service" 优先级。
 *
 * ★ **两个必须一起做**。只做 ① 能扛住 Activity 重建, 扛不住系统杀进程;
 *   只做 ② 则 Activity 一销毁协程先没了, 服务还挂着, 等于白挂。
 *
 * ## 通知策略
 *  - **进行中**: 常驻通知(前台服务不挂通知系统会直接拒绝启动)。
 *    ★ 2026-10-05 起它同时是**保活的可观测凭证**: 带**正向计时器** + 「停止生成」按钮。
 *      用户切出去后一眼能看出"还在跑"(秒数在跳), 不用回来确认;
 *      想放弃时**不用切回 App**, 通知里直接点停止。
 *  - **结束后**: 只有 App **不在前台**时才补一条「回答好了」——
 *    用户正盯着界面看时再弹一条纯属打扰。前台判定见 [AppForeground]。
 *
 * ## 启动时机的合法性
 * `startForegroundService` 在 Android 12+ **不允许从后台启动**。这里没问题:
 * 调用点永远是"用户点了发送"那一刻(前台) → 启动 → 之后 App 才进后台, 服务已在跑。
 * 即便如此仍加了 try-catch: 万一被系统拒(某些 ROM 的后台限制), 只损失"保活",
 * 请求本身照跑 —— **降级要静默, 不能让发送按钮炸掉**。
 *
 * ## 2026-10-05 的三处加固 (用户二次反馈「一切后台就断」)
 *
 *  ③ **`onStartCommand` 里再挂一次前台**。`onCreate` 里那次若被系统/ROM 拒(异常被吞),
 *     服务就退化成普通后台服务, 之后**再没有补救机会**。现在每次 `start()` 都会再走一遍
 *     `startForeground` —— 幂等, 已在前台时等于刷新通知文案。用户等待期间若又发了一条消息,
 *     这次调用就是一次免费的重试窗口。
 *
 *  ④ **`onTaskRemoved` 里自救**。用户从最近任务把 App 划掉时, 若还有会话在跑,
 *     把服务重新顶到前台 —— 划掉界面 != 放弃回答。
 *
 *  ⑤ **通知内容随在跑会话数变化**。多会话并发时写清"2 个会话正在回答",
 *     而不是让人以为只有一条。
 *
 * ## 与国产 ROM 的关系(必须说清)
 * 本服务只能把进程提到 "foreground service" 优先级, **挡不住 ROM 的"自启动/后台冻结"策略**。
 * MIUI/HyperOS、ColorOS、OriginOS、EMUI 等在"未允许自启动 + 未加电池白名单"时
 * 仍会强杀。这是**系统策略, 不是 App 能绕过的** —— 所以设置页里同时给了
 * 「关闭电池优化」与「自启动设置」两个直达入口。见 `SettingsScreen` 的「消息通知」卡片。
 */
class AiTaskService : Service() {

    companion object {
        private const val TAG = "CyanCodexAiTask"
        private const val CHANNEL_ID = "cyancodex_ai_task"
        private const val CHANNEL_DONE_ID = "cyancodex_ai_done"

        /** 前台服务常驻通知的 id */
        private const val NOTI_RUNNING = 1002

        /** 「回答好了」通知的 id */
        private const val NOTI_DONE = 1003

        /** 通知里「停止生成」按钮触发的 action */
        const val ACTION_STOP = "com.canglan.cyancodex.action.AI_STOP"

        /** 通知里「停止生成」按钮的 requestCode */
        private const val RC_STOP = 2002

        /**
         * 当前在跑的会话数。只用于通知文案与 [onTaskRemoved] 的"还要不要自救"判断。
         *
         * 由 [start] / [finish] 一对一维护 —— 这两个方法各自每次任务**恰好调用一次**,
         * 所以不需要加锁。`@Volatile` 只为跨线程可见性(通知可能被系统在别的线程重建)。
         */
        @Volatile
        private var runningCount = 0

        /**
         * 这一轮任务最早开始的**墙钟**时刻。
         *
         * ⚠️ 必须是 `currentTimeMillis()` 而**不是** `uptimeMillis()` ——
         * `Notification.setWhen()` 收的是 Unix 时间戳, 塞 uptime 进去会让计时器
         * 显示成"已运行 50 年"。内部算耗时才用 uptime(见 [AiTaskRunner])。
         */
        @Volatile
        private var startedAtWall = 0L

        /** 系统通知总开关有没有被关(Android 13+ 的 POST_NOTIFICATIONS 或用户手动关)。
         *  关掉时前台服务**照样能跑**, 只是用户看不见 —— 这点要在界面上如实说明。 */
        fun notificationsEnabled(ctx: Context): Boolean = try {
            NotificationManagerCompat.from(ctx).areNotificationsEnabled()
        } catch (e: Exception) {
            true   // 查不出来就假定开着, 不要因此吓唬用户
        }

        /** 当前在跑的会话数(给设置页/调试面板看) */
        fun runningCount(): Int = runningCount

        fun start(ctx: Context) {
            runningCount += 1
            if (startedAtWall == 0L) startedAtWall = System.currentTimeMillis()
            try {
                val i = Intent(ctx, AiTaskService::class.java)
                if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i)
                else ctx.startService(i)
            } catch (e: Exception) {
                // 降级: 没有前台服务也能跑, 只是后台存活率低一些。不打扰用户。
                Log.w(TAG, "start failed (降级为无前台服务): ${e.message}")
            }
        }

        fun stop(ctx: Context) {
            try {
                ctx.stopService(Intent(ctx, AiTaskService::class.java))
            } catch (e: Exception) {
                Log.w(TAG, "stop failed: ${e.message}")
            }
        }

        /**
         * 任务收尾。**由 [AiTaskRunner] 调用, 不要在别处调** —— 它同时负责
         * "停服务" 与 "要不要补完成通知" 两件事, 拆开容易只做一半。
         *
         * @param ok    是否成功(失败就不说"回答好了", 免得点进去看到一条报错)
         * @param stillRunning 收尾的这一刻**还有没有别的会话在跑**。
         *                 2026-10-02 起支持多会话并发 —— 服务是进程级的,
         *                 只要还有一个任务没完就必须继续挂着, 否则那个任务的保活就断了。
         */
        fun finish(ctx: Context, ok: Boolean, stillRunning: Boolean) {
            if (!stillRunning) {
                runningCount = 0
                startedAtWall = 0L
                stop(ctx)
            }
            if (AppForeground.isVisible) return   // 人就在界面里看着, 不用再通知
            notifyDone(ctx, ok)
        }

        private fun notifyDone(ctx: Context, ok: Boolean) {
            try {
                val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
                if (Build.VERSION.SDK_INT >= 26) {
                    nm.createNotificationChannel(
                        NotificationChannel(
                            CHANNEL_DONE_ID, "AI 回答完成", NotificationManager.IMPORTANCE_DEFAULT
                        ).apply { description = "AI 回答完毕、可以回来看了" }
                    )
                }
                val pi = PendingIntent.getActivity(
                    ctx, 0, Intent(ctx, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
                val n = Notification.Builder(ctx, CHANNEL_DONE_ID)
                    .setSmallIcon(R.drawable.ic_notification)
                    .setContentTitle(if (ok) "AI 回答好了" else "AI 回答中断")
                    .setContentText(if (ok) "点击回到沧蓝工坊查看" else "点击回到沧蓝工坊重试")
                    .setContentIntent(pi)
                    .setAutoCancel(true)
                    .build()
                nm.notify(NOTI_DONE, n)
            } catch (e: Exception) {
                Log.w(TAG, "notifyDone failed: ${e.message}")
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        attachForeground()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 通知里点了「停止生成」—— 不用切回 App 就能放弃这一轮。
        // 停止是**协作式**的: 旗标 + cancel 双管, 已流出的半截回答会被保留, 见 AiTaskRunner.stop。
        if (intent?.action == ACTION_STOP) {
            AiTaskRunner.stopAll()
            return START_NOT_STICKY
        }
        // ★ 加固③: 每次 start 都再挂一遍前台。幂等 —— 已在前台时只是刷新通知文案
        //   (在跑会话数变了); 若 onCreate 那次被拒, 这里是唯一的补救点。
        attachForeground()
        return START_NOT_STICKY
    }

    /**
     * 用户从最近任务把 App 划掉。
     *
     * 有会话还在跑 → 把服务重新顶到前台, 尽量让请求跑完。
     * 注意 `stopWithTask` 默认为 false, 所以正常情况下服务本来就不会因划卡片而被停,
     * 这里只是**再保险一次**; 真正的生死权仍在 ROM 的"自启动/后台冻结"策略手里。
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        if (runningCount > 0) attachForeground()
    }

    /** 挂前台 + 刷新通知。任何一步失败都只记日志 —— 降级要静默, 不能把发送流程炸掉。 */
    private fun attachForeground() {
        try {
            startForeground(NOTI_RUNNING, buildNotification())
        } catch (e: Exception) {
            // 前台服务被系统拒(API 34+ 的 type 校验 / ROM 限制) → 退化成普通后台服务。
            // 不 stopSelf: 让它活着不影响请求, 只是保活能力弱一些。
            Log.w(TAG, "startForeground failed: ${e.message}")
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "AI 回答中", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "AI 正在回答时显示，回答结束自动消失"
                    setShowBadge(false)
                }
            )
        }
    }

    private fun buildNotification(): Notification {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        // 「停止生成」: 直接投给本服务的 onStartCommand, 不需要 App 界面在场
        val stopPi = PendingIntent.getService(
            this, RC_STOP,
            Intent(this, AiTaskService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val text = if (runningCount > 1)
            "$runningCount 个会话正在回答，好了会通知你"
        else
            "可以先切到别的应用，回答好了会通知你"

        val b = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("AI 正在回答")
            .setContentText(text)
            .setContentIntent(pi)
            .setOngoing(true)
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, R.drawable.ic_notification),
                    "停止生成",
                    stopPi
                ).build()
            )

        // ★ 正向计时器 = "还在跑"的可观测凭证。用户切出去瞄一眼秒数在跳就放心了,
        //   不用切回来确认 —— 这正是"切后台就断"这类问题的心理成本所在。
        if (startedAtWall > 0L) {
            b.setWhen(startedAtWall).setShowWhen(true).setUsesChronometer(true)
        }
        return b.build()
    }
}
