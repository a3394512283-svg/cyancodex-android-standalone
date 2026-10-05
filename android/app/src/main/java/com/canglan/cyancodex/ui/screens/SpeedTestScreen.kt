package com.canglan.cyancodex.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.canglan.cyancodex.data.SpeedProgress
import com.canglan.cyancodex.data.SpeedResult
import com.canglan.cyancodex.data.SpeedTest
import com.canglan.cyancodex.ui.components.AppTopBar
import com.canglan.cyancodex.ui.components.GlassActionButton
import com.canglan.cyancodex.ui.components.GlassSwitch
import com.canglan.cyancodex.ui.components.SectionTitle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 网络测速
 *
 * 打自家服务器的 speedtest 接口: 延迟 → 下载 → 上传, 结果自动留最近 15 次。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpeedTestScreen(nav: NavHostController) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) { SpeedTest.init(ctx) }

    var running by remember { mutableStateOf(false) }
    var job by remember { mutableStateOf<Job?>(null) }
    var phase by remember { mutableStateOf("") }
    var progress by remember { mutableStateOf(SpeedProgress()) }
    var result by remember { mutableStateOf<SpeedResult?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var doUpload by remember { mutableStateOf(true) }
    var history by remember { mutableStateOf(SpeedTest.history()) }
    val net = remember { SpeedTest.networkType(ctx) }

    fun start() {
        if (running) return
        result = null
        error = null
        progress = SpeedProgress()
        job = scope.launch {
            running = true
            try {
                val r = SpeedTest.run(
                    ctx = ctx,
                    upload = doUpload,
                    onPhase = { phase = it },
                    onProgress = { progress = it },
                )
                result = r
            } catch (e: CancellationException) {
                error = "已取消测速"
            } catch (e: Exception) {
                error = e.message ?: "测速失败，请检查网络后重试"
            } finally {
                running = false
                phase = ""
                history = SpeedTest.history()
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        running = false
    }

    DisposableEffect(Unit) { onDispose { job?.cancel() } }

    Scaffold(
        topBar = { AppTopBar(title = "网络测速", icon = Icons.Default.Speed, onBack = { nav.popBackStack() }) },
    ) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // 节点信息
            Card {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("测速节点：沧蓝工坊服务器（受服务器出口带宽限制）", style = MaterialTheme.typography.bodyMedium)
                    Text("当前网络：$net · 直连官方服务器（不经第三方测速站）",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    // 用户反馈「测速服务器受带宽等影响无法代表真实速度」：把语义说清楚
                    Text("本测速测的是「手机 ↔ 沧蓝服务器」这一段，服务器出口带宽与线路会压低结果，不能代表你宽带的真实上限。想测家里宽带的真实速度，请用 speedtest.net 之类选就近本地节点。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error)
                }
            }

            // 实时读数
            val shown = result
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                BigMetric(
                    "延迟", if (running && phase == "延迟") "…" else "${shown?.pingMs ?: 0}", "ms",
                    if (shown != null && shown.jitterMs > 0) "抖动 ${shown.jitterMs}ms" else "越小越好",
                    Modifier.weight(1f),
                )
                BigMetric(
                    "下载", fmtMbps(if (running && phase == "下载") progress.avgMbps else shown?.downMbps),
                    "Mbps", "越大越好", Modifier.weight(1f),
                )
                BigMetric(
                    "上传",
                    fmtMbps(if (running && phase == "上传") progress.avgMbps else shown?.upMbps),
                    "Mbps", "越大越好", Modifier.weight(1f),
                )
            }

            if (running) {
                Card {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("正在测${phase}", style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium)
                            Spacer(Modifier.weight(1f))
                            if (phase != "延迟") {
                                Text("${fmtMbps(progress.instantMbps)} Mbps",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary)
                            }
                        }
                        LinearProgressIndicator(
                            progress = { if (phase == "延迟") progress.progress.coerceIn(0f, 1f) else progress.progress.coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        if (progress.doneBytes > 0) {
                            Text("已传输 ${fmtBytes(progress.doneBytes)}", style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }

            error?.let {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                    Text(it, Modifier.padding(14.dp), style = MaterialTheme.typography.bodySmall)
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                GlassActionButton(filled = true,
                    modifier = Modifier.weight(1f),
                    enabled = !running,
                    onClick = { start() },
                ) { Text(if (result == null) "开始测速" else "重新测速") }
                if (running) {
                    GlassActionButton(modifier = Modifier.weight(1f), onClick = { stop() }) { Text("停止") }
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("测试上传速度", style = MaterialTheme.typography.bodyMedium)
                    Text("关闭可少用一半流量", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                GlassSwitch(checked = doUpload, onCheckedChange = { doUpload = it }, enabled = !running)
            }

            result?.let { r ->
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("测速完成", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                        Text("延迟 ${r.pingMs} ms（抖动 ${r.jitterMs} ms）· 下载 ${fmtMbps(r.downMbps)} Mbps" +
                            (if (r.upMbps > 0) " · 上传 ${fmtMbps(r.upMbps)} Mbps" else ""),
                            style = MaterialTheme.typography.bodySmall)
                        Text("本次约消耗流量 ${fmtBytes(r.consumedBytes)} · ${r.network} · ${fmtDate(r.time)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            SectionTitle("历史记录（${history.size}）")
            if (history.isEmpty()) {
                Text("还没有测速记录。", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Card {
                    Column(Modifier.padding(vertical = 4.dp)) {
                        history.forEach { r ->
                            Row(
                                Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text("↓ ${fmtMbps(r.downMbps)} Mbps" +
                                        (if (r.upMbps > 0) "　↑ ${fmtMbps(r.upMbps)} Mbps" else ""),
                                        style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                                    Text("延迟 ${r.pingMs}ms · ${r.network} · ${fmtDate(r.time)}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
                TextButton(onClick = { SpeedTest.clearHistory(); history = emptyList() }) { Text("清空历史记录") }
            }

            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Text(
                    "说明：本测速的下载/上传速度是「到沧蓝服务器」的速度，受服务器出口带宽与线路影响，" +
                    "不等于你宽带的真实上限（延迟与抖动这两项仍然能反映当前网络质量）。" +
                    "测速会消耗流量（一次约 20-40MB，取决于网速），建议在 WiFi 下测试；" +
                    "下载测试固定 10 秒、上传固定 8 秒，结果取去掉前 0.7 秒预热后的平均值。",
                    Modifier.padding(14.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun BigMetric(label: String, value: String, unit: String, hint: String, modifier: Modifier = Modifier) {
    Card(modifier) {
        Column(Modifier.padding(vertical = 14.dp, horizontal = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(label, style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(verticalAlignment = Alignment.Bottom) {
                Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(3.dp))
                Text(unit, style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 3.dp))
            }
            Text(hint, style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun fmtMbps(v: Double?): String {
    val x = v ?: 0.0
    if (x <= 0) return "0"
    return if (x >= 100) String.format(Locale.CHINA, "%.0f", x)
    else if (x >= 10) String.format(Locale.CHINA, "%.1f", x)
    else String.format(Locale.CHINA, "%.2f", x)
}

private fun fmtBytes(b: Long): String {
    if (b <= 0) return "0 B"
    val kb = b / 1024.0
    val mb = kb / 1024.0
    return when {
        mb >= 1 -> String.format(Locale.CHINA, "%.1f MB", mb)
        kb >= 1 -> String.format(Locale.CHINA, "%.0f KB", kb)
        else -> "$b B"
    }
}

private fun fmtDate(ms: Long): String =
    SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(Date(ms))
