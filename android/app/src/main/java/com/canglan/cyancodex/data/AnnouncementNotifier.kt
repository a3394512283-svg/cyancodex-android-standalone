package com.canglan.cyancodex.data

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import com.canglan.cyancodex.MainActivity
import com.canglan.cyancodex.ui.components.versionNewer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 新消息系统通知: 公告 + 站内信都走安卓自带通知渠道。
 * 发现比 lastNotified* 更新的内容时推送通知。
 * 检查时机: ① 进程启动/回到前台立即检查; ② AlarmManager 链式 setAndAllowWhileIdle 每 15 分钟
 * (Doze 下也能触发, 不会被系统无限延后; 进程被杀后由系统代为触发, 每次触发后自动重排下一轮);
 * ③ 开机后由 BootReceiver 重新排程。
 */
object AnnouncementNotifier {
    // v1.0.91: 通知改为静默 (只在通知栏显示, 不弹横幅/不响铃/不震动)。
    // 安卓 8+ 渠道重要级别创建后不可修改, 所以换新渠道 id, 并删掉旧的 HIGH 渠道,
    // 否则老用户的通知还是会走旧渠道继续弹横幅。
    const val CHANNEL_ID = "announcements_v2"
    const val MSG_CHANNEL_ID = "messages_v2"
    private val LEGACY_CHANNELS = listOf("announcements", "messages")
    private const val CHECK_INTERVAL_MS = 15 * 60 * 1000L // 每 15 分钟检查一轮

    fun ensureChannel(ctx: Context) {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "公告通知", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "新公告 / 新版本提醒（静默显示，不弹横幅）"
                    setSound(null, null)
                    enableVibration(false)
                    enableLights(false)
                    setShowBadge(true)
                }
            )
            nm.createNotificationChannel(
                NotificationChannel(MSG_CHANNEL_ID, "站内信", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "新站内信 / 系统消息提醒（静默显示，不弹横幅）"
                    setSound(null, null)
                    enableVibration(false)
                    enableLights(false)
                    setShowBadge(true)
                }
            )
            // 删掉历史的高优先级渠道 (旧版会弹横幅)
            LEGACY_CHANNELS.forEach { id ->
                try { nm.deleteNotificationChannel(id) } catch (e: Exception) {}
            }
        }
    }

    /** 立即检查一次: 新公告与新站内信都发系统通知 */
    fun checkNow(ctx: Context) {
        CoroutineScope(Dispatchers.IO).launch {
            // ---- 公告 ----
            try {
                val r = ApiClient.get().announcements("all")
                if (r.ok) {
                    val anns = r.data ?: emptyList()
                    val target = anns
                        .filter { it.id > Session.lastNotifiedAnnouncementId }
                        .filter { it.type != "update" || versionNewer(it.version) }
                        .maxByOrNull { it.id }
                    if (target != null) {
                        Session.lastNotifiedAnnouncementId = target.id
                        post(ctx, target)
                    }
                }
            } catch (e: Exception) {}

            // ---- 站内信 (需登录) ----
            if (Session.token == null) return@launch
            try {
                val r = ApiClient.get().messages(1, 20)
                if (r.ok) {
                    val msgs = (r.data?.list ?: emptyList())
                        .filter { it.id > Session.lastNotifiedMessageId }
                        .sortedBy { it.id }
                    if (msgs.isNotEmpty()) {
                        Session.lastNotifiedMessageId = msgs.maxOf { it.id }
                        // 逐条推送, 最多 5 条, 避免刷屏
                        msgs.takeLast(5).forEach { m -> postMessage(ctx, m) }
                    }
                }
            } catch (e: Exception) {}
        }
    }

    /** 发公告通知 (点击打开 App → 公告页) */
    fun post(ctx: Context, ann: Announcement) {
        val intent = Intent(ctx, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("goto", "announcements")
        }
        val pi = PendingIntent.getActivity(ctx, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val title = if (ann.type == "update" && !ann.version.isNullOrBlank()) "发现新版本 v${ann.version}" else "新公告"
        val notif = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(ann.title)
            .setStyle(NotificationCompat.BigTextStyle().bigText(ann.content))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setDefaults(0)
            .setVibrate(null)
            .build()
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(ann.id.toInt(), notif)
    }

    /** 发站内信通知 (点击打开 App → 站内信页) */
    fun postMessage(ctx: Context, m: MessageItem) {
        val intent = Intent(ctx, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("goto", "messages")
        }
        // requestCode 用消息 id, 避免通知互相覆盖
        val pi = PendingIntent.getActivity(ctx, 10000 + m.id.toInt(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notif = NotificationCompat.Builder(ctx, MSG_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setContentTitle(m.title)
            .setContentText(m.content)
            .setStyle(NotificationCompat.BigTextStyle().bigText(m.content))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setDefaults(0)
            .setVibrate(null)
            .build()
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(20000 + m.id.toInt(), notif)
    }

    /**
     * 注册周期检查: 链式 setAndAllowWhileIdle。
     * 每次触发后由接收器重新排程下一轮; 进程被杀/系统延迟后由系统代为触发, 不依赖进程存活。
     */
    fun schedule(ctx: Context) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = PendingIntent.getBroadcast(
            ctx, 0,
            Intent(ctx, AnnouncementCheckReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        // setAndAllowWhileIdle: 非精确闹钟, Doze 下约 15 分钟内触发, 无需 SCHEDULE_EXACT_ALARM 权限
        am.setAndAllowWhileIdle(
            AlarmManager.ELAPSED_REALTIME_WAKEUP,
            SystemClock.elapsedRealtime() + CHECK_INTERVAL_MS,
            pi
        )
    }
}

/** 周期检查广播接收器 (链式重排: 触发 → 检查 → 排下一轮) */
class AnnouncementCheckReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val app = ctx.applicationContext
        AnnouncementNotifier.checkNow(app)
        AnnouncementNotifier.schedule(app)
    }
}
