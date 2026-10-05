package com.canglan.cyancodex.data

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.canglan.cyancodex.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 后台常驻收消息 (前台服务)
 *
 * 背景: 之前只有 App 打开 (ON_RESUME) 或 AlarmManager 每 15 分钟查一次, 关掉 App 后
 * 消息要等很久甚至收不到。这里用一个前台服务按 Session.watchIntervalSec (默认 60 秒)
 * 轮询一次新公告 / 新站内信, 保证消息能实时到。
 *
 * 说明:
 *  · 通知本身是静默的 (见 AnnouncementNotifier), 只在通知栏出现, 不弹横幅;
 *  · 常驻通知本身也用最低重要级渠道, 不响不弹;
 *  · 退出登录 / 关闭开关 / 系统回收进程后自动停止 (START_STICKY 由系统按需重启)。
 */
class MessageWatchService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var loop: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running = true
        ensureChannel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            Session.backgroundWatch = false
            stopWatch()
            return START_NOT_STICKY
        }
        startForegroundCompat()
        if (loop == null || loop?.isActive != true) {
            loop = scope.launch {
                while (isActive) {
                    if (!Session.isLoggedIn() || !Session.backgroundWatch) {
                        stopWatch()
                        break
                    }
                    try { AnnouncementNotifier.checkNow(this@MessageWatchService) } catch (e: Exception) {}
                    delay(Session.watchIntervalSec * 1000L)
                }
            }
        }
        return START_STICKY
    }

    private fun startForegroundCompat() {
        val notif = NotificationCompat.Builder(this, WATCH_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("沧蓝工坊正在后台接收消息")
            .setContentText("新公告 / 站内信会静默显示在通知栏，不弹横幅")
            .setOngoing(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setDefaults(0)
            .setVibrate(null)
            .setContentIntent(
                PendingIntent.getActivity(
                    this, 0,
                    Intent(this, MainActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    },
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )
            .addAction(
                0, "关闭后台接收",
                PendingIntent.getService(
                    this, 1,
                    Intent(this, MessageWatchService::class.java).setAction(ACTION_STOP),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )
            .build()
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(FG_ID, notif, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(FG_ID, notif)
            }
        } catch (e: Exception) {
            try { startForeground(FG_ID, notif) } catch (e2: Exception) {}
        }
    }

    @Suppress("DEPRECATION")
    private fun stopWatch() {
        running = false
        loop?.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        running = false
        loop?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "com.canglan.cyancodex.action.STOP_WATCH"
        private const val FG_ID = 1001
        private const val WATCH_CHANNEL_ID = "watch_service"

        @Volatile
        var running: Boolean = false
            private set

        fun ensureChannel(ctx: Context) {
            if (Build.VERSION.SDK_INT < 26) return
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(
                NotificationChannel(WATCH_CHANNEL_ID, "后台接收消息", NotificationManager.IMPORTANCE_MIN).apply {
                    description = "常驻通知：保持后台接收新消息（静默，不弹横幅不响铃）"
                    setSound(null, null)
                    enableVibration(false)
                    enableLights(false)
                    setShowBadge(false)
                }
            )
        }

        /** 需要登录 + 开关打开才会启动; 启动失败(系统限制)时静默忽略, 退回闹钟轮询 */
        fun start(ctx: Context) {
            if (!Session.isLoggedIn() || !Session.backgroundWatch) return
            ensureChannel(ctx)
            try {
                val i = Intent(ctx, MessageWatchService::class.java)
                if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
            } catch (e: Exception) {
                // Android 12+ 后台启动前台服务可能被拒 (ForegroundServiceStartNotAllowedException)
            }
        }

        fun stop(ctx: Context) {
            try { ctx.stopService(Intent(ctx, MessageWatchService::class.java)) } catch (e: Exception) {}
        }
    }
}
