package com.canglan.cyancodex.ai

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.canglan.cyancodex.MainActivity
import com.canglan.cyancodex.R
import com.canglan.cyancodex.data.AppForeground

/**
 * AI 回答任务的**前台服务外壳** (2026-10-02)。
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
 *  - **结束后**: 只有 App **不在前台**时才补一条「回答好了」——
 *    用户正盯着界面看时再弹一条纯属打扰。前台判定见 [AppForeground]。
 *
 * ## 启动时机的合法性
 * `startForegroundService` 在 Android 12+ **不允许从后台启动**。这里没问题:
 * 调用点永远是"用户点了发送"那一刻(前台) → 启动 → 之后 App 才进后台, 服务已在跑。
 * 即便如此仍加了 try-catch: 万一被系统拒(某些 ROM 的后台限制), 只损失"保活",
 * 请求本身照跑 —— **降级要静默, 不能让发送按钮炸掉**。
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

        fun start(ctx: Context) {
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
            if (!stillRunning) stop(ctx)
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
        try {
            startForeground(NOTI_RUNNING, buildNotification())
        } catch (e: Exception) {
            // 前台服务被系统拒(API 34 的 type 校验 / ROM 限制) → 退化成普通后台服务。
            // 不 stopSelf: 让它活着不影响请求, 只是保活能力弱一些。
            Log.w(TAG, "startForeground failed: ${e.message}")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY

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
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("AI 正在回答")
            .setContentText("可以先切到别的应用，回答好了会通知你")
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }
}
