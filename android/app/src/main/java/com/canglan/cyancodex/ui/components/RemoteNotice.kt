package com.canglan.cyancodex.ui.components

import android.content.Context
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.canglan.cyancodex.data.AppConfig

/**
 * 服务端公告弹窗
 * ==========================================================================
 *
 * 数据来自 `GET /api/v1/app/config` 的 `notice` 字段。管理端用
 * `ai_server.py set-notice <id> <文本>` 下发，改 `id` 即视为一条**新**公告。
 *
 * ## 为什么按 id 去重
 *
 * 用户最烦的就是"每次打开都弹同一个通知"。这里把已读的 id 记在本地，
 * 同一条公告只弹一次；管理员换一个 id 才会再弹 —— 这比"按天去重"更可控：
 * 想让所有人再看一遍，改 id 就行。
 *
 * ## 为什么不阻塞启动
 *
 * 先用本地缓存把公告画出来（缓存命中时几乎没有延迟），再后台刷新一次拿最新值。
 * 网络失败就什么都不做 —— 公告不是关键路径，不能因为拉不到就弹错误。
 */
@Composable
fun RemoteNoticeDialog() {
    val ctx = LocalContext.current
    var notice by remember { mutableStateOf<AppConfig.Notice?>(null) }

    LaunchedEffect(Unit) {
        // 第一轮：缓存（快）
        notice = pick(ctx, AppConfig.cachedNotice(ctx))
        // 第二轮：刷新后覆盖（慢，但更新）
        if (AppConfig.refresh(ctx)) {
            notice = pick(ctx, AppConfig.cachedNotice(ctx))
        }
    }

    val n = notice ?: return

    AlertDialog(
        onDismissRequest = {
            markSeen(ctx, n.id)
            notice = null
        },
        title = { Text(titleOf(n.level)) },
        text = { Text(n.text) },
        confirmButton = {
            TextButton(onClick = {
                markSeen(ctx, n.id)
                notice = null
            }) { Text("知道了") }
        },
    )
}

private fun titleOf(level: String): String = when (level) {
    "danger" -> "重要通知"
    "warn" -> "注意"
    else -> "公告"
}

/** 过滤掉：空公告 / 已过期 / 已经看过的那一条 */
private fun pick(ctx: Context, n: AppConfig.Notice?): AppConfig.Notice? {
    if (n == null || n.id.isBlank() || n.text.isBlank()) return null
    // 服务端的 until 是**秒**级时间戳
    if (n.until > 0 && n.until * 1000L < System.currentTimeMillis()) return null
    return if (seenId(ctx) == n.id) null else n
}

private fun prefs(ctx: Context) =
    ctx.applicationContext.getSharedPreferences("cyancodex", Context.MODE_PRIVATE)

private fun seenId(ctx: Context): String =
    prefs(ctx).getString("notice_seen_id", "").orEmpty()

private fun markSeen(ctx: Context, id: String) {
    prefs(ctx).edit().putString("notice_seen_id", id).apply()
}
