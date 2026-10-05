package com.canglan.cyancodex.ui.screens

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.canglan.cyancodex.data.ApiClient
import com.canglan.cyancodex.data.SchoolShareResp
import com.canglan.cyancodex.data.SchoolShareStatus
import com.canglan.cyancodex.ui.AppState
import com.canglan.cyancodex.ui.components.AppTopBar
import com.canglan.cyancodex.ui.components.GlassActionButton
import com.canglan.cyancodex.ui.components.copyToClipboard
import com.canglan.cyancodex.ui.components.safeApi
import kotlinx.coroutines.launch

/**
 * 开学季福利活动: 把推广信息分享到 QQ 群, 累计 N 次得积分 (公告 type=activity 点入)
 * 检测方式: 拉起系统分享面板(可分享到 QQ 群), 用户确认完成, 服务端累计
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SchoolShareScreen(nav: NavHostController) {
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    var st by remember { mutableStateOf<SchoolShareStatus?>(null) }
    var loading by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf("") }
    var toastMsg by remember { mutableStateOf<String?>(null) }

    suspend fun load() {
        val r = safeApi { ApiClient.get().schoolShareStatus() }
        if (r.ok) {
            st = r.data
            err = ""
        } else err = r.error ?: "加载失败"
        loading = false
    }
    LaunchedEffect(Unit) { load() }
    toastMsg?.let { t ->
        Toast.makeText(ctx, t, Toast.LENGTH_SHORT).show()
        toastMsg = null
    }

    // 推广文案 (开学季): 用户复制后粘贴到 QQ 群
    val promo = "CyanCodex（沧蓝工坊）——我的世界基岩版指令助手：指令库 / 聊天室 / 五子棋 / AI 助手，新学期全部免费！" +
        "下载链接：https://cc.blueworkshop.top"

    fun shareToQQ() {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, promo)
            // 优先直接唤起 QQ (装了 QQ 才生效)
            setPackage("com.tencent.mobileqq")
        }
        runCatching { ctx.startActivity(send) }.onFailure {
            // 没装 QQ: 回退到系统分享面板
            val fallback = Intent.createChooser(send.apply { setPackage(null) }, "分享到 QQ 群")
            runCatching { ctx.startActivity(fallback) }
                .onFailure { toastMsg = "未找到可分享的应用，请复制文案后手动发送到 QQ 群" }
        }
    }

    fun confirmOnce() {
        scope.launch {
            busy = true
            val r = safeApi { ApiClient.get().schoolShareConfirm() }
            busy = false
            if (r.ok) {
                val d: SchoolShareResp? = r.data
                toastMsg = d?.message ?: "已记录"
                d?.points?.let { AppState.patchUser(points = it) }
                load()
            } else {
                err = r.error ?: "操作失败"
            }
        }
    }

    Scaffold(
        topBar = { AppTopBar(title = "开学季福利", icon = Icons.Default.Campaign, onBack = { nav.popBackStack() }) },
    ) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // 活动说明卡
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Star, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("开学季福利 · 分享得积分", style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold)
                    }
                    Text(
                        "把 CyanCodex 的推广信息分享到 ${st?.target ?: 5} 个 QQ 群，" +
                            "完成 ${st?.target ?: 5} 次确认即可获得 ${st?.reward ?: 200} 积分！" +
                            "\n\n每次分享后回到本页点「我已分享 1 次」进行确认。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }

            // 进度
            if (st != null) {
                val s = st!!
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(if (s.claimed) "已完成，奖励已发放" else "分享进度", style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
                            Text("${s.count.coerceAtMost(s.target)} / ${s.target}", style = MaterialTheme.typography.titleSmall)
                        }
                        LinearProgressIndicator(
                            progress = if (s.target > 0) (s.count.coerceAtMost(s.target).toFloat() / s.target) else 0f,
                            modifier = Modifier.fillMaxWidth().height(8.dp),
                        )
                        Text(if (s.claimed) "恭喜获得 ${s.reward} 积分，快去指令库看看吧！"
                        else if (s.count >= s.target) "已达分享次数，正在发放奖励…"
                        else "还差 ${s.target - s.count} 次即可领取 ${s.reward} 积分",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        s.points?.let { p ->
                            Text("当前积分: $p", style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }

            // 操作
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GlassActionButton(onClick = { copyToClipboard(ctx, promo, "推广文案已复制") },
                    modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.ContentCopy, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("复制文案")
                }
                GlassActionButton(filled = true, onClick = { shareToQQ() }, modifier = Modifier.weight(1f)) {
                    Text("分享到 QQ 群")
                }
            }

            val done = st?.claimed == true
            GlassActionButton(filled = true,
                onClick = { confirmOnce() },
                enabled = !busy && !done,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    when {
                        busy -> "确认中…"
                        done -> "已完成"
                        (st?.count ?: 0) >= (st?.target ?: 5) -> "已达次数，点击领取奖励"
                        else -> "我已分享 1 次（${(st?.count ?: 0) + 1}/${st?.target ?: 5}）"
                    },
                )
            }
            if (err.isNotBlank()) {
                Text(err, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }

            // 推广文案预览
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("推广文案预览", style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(promo, style = MaterialTheme.typography.bodySmall)
                    Text("提示：请发送到 5 个不同的 QQ 群（自己拥有的群或允许发布内容的群），请勿刷屏骚扰他人。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
