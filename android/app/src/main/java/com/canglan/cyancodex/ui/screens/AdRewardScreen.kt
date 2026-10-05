package com.canglan.cyancodex.ui.screens

import android.view.ViewGroup
import android.widget.VideoView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.navigation.NavHostController
import com.canglan.cyancodex.data.AdItem
import com.canglan.cyancodex.data.AdStatus
import com.canglan.cyancodex.data.ApiClient
import com.canglan.cyancodex.ui.AppState
import com.canglan.cyancodex.ui.components.AppTopBar
import com.canglan.cyancodex.ui.components.GlassActionButton
import com.canglan.cyancodex.ui.components.LoadingBox
import com.canglan.cyancodex.ui.components.safeApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 看公益广告得积分
 *  · 广告来自央视网公益频道（公益广告 / 公益视频），服务端已把片源转成 MP4 放在我们自己服务器，
 *    所以这里是**纯原生播放器**（VideoView），没有 WebView
 *  · 必须完整看完一条（duration 秒）才能领取积分；每天最多 N 次（服务端把关）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdRewardScreen(nav: NavHostController) {
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current

    var status by remember { mutableStateOf<AdStatus?>(null) }
    var loading by remember { mutableStateOf(true) }
    var phase by remember { mutableStateOf("idle") }   // idle / playing / done / claimed
    var ad by remember { mutableStateOf<AdItem?>(null) }
    var viewId by remember { mutableStateOf(0L) }
    var watched by remember { mutableStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf("") }
    var gained by remember { mutableStateOf(0) }
    var videoRef by remember { mutableStateOf<VideoView?>(null) }
    var playError by remember { mutableStateOf("") }
    var localPath by remember { mutableStateOf("") }     // 下载到本地的 MP4 路径
    var downloading by remember { mutableStateOf(false) }
    var retryToken by remember { mutableStateOf(0) }

    fun load() {
        scope.launch {
            val r = safeApi { ApiClient.get().adStatus() }
            if (r.ok) status = r.data else msg = r.error ?: "加载失败"
            loading = false
        }
    }

    fun startAd() {
        if (busy || downloading) return          // 防重入: 别重复开播
        scope.launch {
            busy = true; msg = ""; gained = 0; watched = 0; playError = ""; localPath = ""
            val r = safeApi { ApiClient.get().adStart() }
            if (!r.ok || r.data == null) { busy = false; msg = r.error ?: "开播失败"; return@launch }
            val a = r.data.ad
            ad = a
            viewId = r.data.viewId
            if (a.playUrl.isBlank()) {
                busy = false
                playError = "这条广告没有可播放的地址"
                phase = "playing"
                return@launch
            }
            // 先用 App 自己的 HTTP 下载到缓存, 再让系统播放器播本地文件:
            // 播放器自带的 HTTP 栈经常抽风(会报 -1004), 本地文件最稳, 也不会中途卡缓冲
            val url = if (a.playUrl.startsWith("/"))
                com.canglan.cyancodex.data.Session.baseUrl.trimEnd('/') + a.playUrl else a.playUrl
            downloading = true
            val f = withContext(Dispatchers.IO) { downloadAd(ctx, url, a.id) }
            downloading = false
            busy = false
            if (f == null) {
                playError = "广告下载失败，请检查网络后重试"
                phase = "playing"
            } else {
                localPath = f.absolutePath
                phase = "playing"
            }
        }
    }

    fun claim() {
        val a = ad ?: return
        scope.launch {
            busy = true; msg = ""
            val r = safeApi {
                ApiClient.get().adClaim(mapOf(
                    "view_id" to viewId.toString(),
                    "watched_sec" to watched.coerceAtLeast(a.duration).toString()))
            }
            busy = false
            if (r.ok && r.data != null) {
                gained = r.data.gained
                AppState.patchUser(points = r.data.points)
                phase = "claimed"
                load()
            } else {
                msg = r.error ?: "领取失败"
            }
        }
    }

    LaunchedEffect(Unit) { load() }

    // 计时: 按播放器真实进度累加(播放器不带控制条, 用户无法拖动)
    // 看门狗: 超过 15 秒还停在 0 秒, 说明这台设备/播放器起不来, 给出明确提示而不是干等
    LaunchedEffect(phase, ad) {
        if (phase != "playing") return@LaunchedEffect
        val dur = ad?.duration ?: 30
        var waited = 0
        while (phase == "playing") {
            delay(400)
            if (downloading || localPath.isBlank()) { waited = 0; continue }   // 还没下载好, 不计时
            val vv = videoRef ?: continue
            if (playError.isNotBlank()) continue
            val pos = runCatching { vv.currentPosition / 1000 }.getOrDefault(0)
            if (pos > watched) watched = pos
            if (watched == 0) {
                waited += 400
                if (waited >= 15000) {
                    playError = "播放器没有开始播放（可能是设备解码器不支持）"
                    continue
                }
            }
            if (watched >= dur) { phase = "done"; break }
        }
    }

    // 离开页面时停止播放
    DisposableEffect(Unit) {
        onDispose { runCatching { videoRef?.stopPlayback() } }
    }

    Scaffold(
        topBar = {
            AppTopBar(title = "看广告得积分", icon = Icons.Default.PlayCircle, onBack = {
                runCatching { videoRef?.stopPlayback() }
                nav.popBackStack()
            })
        },
    ) { pad ->
        if (loading && status == null) { LoadingBox(Modifier.padding(pad)); return@Scaffold }
        val s = status

        Column(
            Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // ---------- 广告播放区 ----------
            val a = ad
            if (phase == "playing" && a != null) {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text(a.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                        Text("来自央视网公益频道 · 共 ${a.duration} 秒",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(10.dp))

                        AndroidView(
                            modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f)
                                .background(Color.Black, RoundedCornerShape(8.dp)),
                            factory = { c ->
                                VideoView(c).apply {
                                    layoutParams = ViewGroup.LayoutParams(
                                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                                    videoRef = this
                                    setOnPreparedListener { mp ->
                                        mp.isLooping = false
                                        mp.start()      // 注意: 这是 MediaPlayer 的 start(), 不是上面的开播函数
                                    }
                                    setOnCompletionListener { if (phase == "playing") phase = "done" }
                                    setOnErrorListener { _, _, _ ->
                                        playError = "这条广告播放失败了"
                                        true
                                    }
                                }
                            },
                            // 本地文件下载好之后才 setVideoPath (factory 可能先跑, 所以放 update 里)
                            update = { vv ->
                                val key = localPath + "#" + retryToken
                                if (localPath.isNotBlank() && vv.tag != key) {
                                    vv.tag = key
                                    vv.setVideoPath(localPath)
                                    if (playError.isBlank()) vv.start()
                                }
                            },
                        )

                        if (downloading) {
                            Spacer(Modifier.height(8.dp))
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                            Text("正在准备广告…", style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }

                        if (playError.isNotBlank()) {
                            Spacer(Modifier.height(8.dp))
                            Text(playError, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error)
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                GlassActionButton(
                                    onClick = {
                                        playError = ""
                                        watched = 0
                                        retryToken++          // 触发 update 重新 setVideoPath + start
                                    },
                                    modifier = Modifier.weight(1f),
                                ) { Text("重试这条") }
                                GlassActionButton(filled = true, onClick = { phase = "idle"; ad = null; startAd() },
                                    enabled = !busy, modifier = Modifier.weight(1f)) { Text("换一条") }
                            }
                            Spacer(Modifier.height(6.dp))
                        }

                        Spacer(Modifier.height(10.dp))
                        val left = (a.duration - watched).coerceAtLeast(0)
                        LinearProgressIndicator(
                            progress = { (watched.toFloat() / a.duration.coerceAtLeast(1)).coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            when {
                                playError.isNotBlank() -> "播放失败：$playError"
                                left > 0 -> "必须完整看完才能领取 · 还需 $left 秒"
                                else -> "已看完，正在结算…"
                            },
                            style = MaterialTheme.typography.labelMedium,
                            color = if (playError.isNotBlank()) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }

            // ---------- 看完后领取 ----------
            if (phase == "done") {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                    modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(18.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("广告已看完 🎉", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(6.dp))
                        Text("可以领取 ${s?.reward ?: 50} 积分了", style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.height(12.dp))
                        GlassActionButton(filled = true, onClick = { claim() }, enabled = !busy, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                            if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                            else Text("领取 ${s?.reward ?: 50} 积分", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            // ---------- 领取成功 ----------
            if (phase == "claimed") {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                    modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(18.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("+$gained 积分", style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                        Text("当前积分 ${AppState.user?.points ?: 0}",
                            style = MaterialTheme.typography.labelMedium)
                        Spacer(Modifier.height(12.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            GlassActionButton(onClick = { phase = "idle"; ad = null },
                                modifier = Modifier.weight(1f)) { Text("返回") }
                            GlassActionButton(filled = true, onClick = { phase = "idle"; ad = null; startAd() },
                                enabled = (s?.remain ?: 0) > 0 && !busy,
                                modifier = Modifier.weight(1f)) { Text("再看一条") }
                        }
                    }
                }
            }

            // ---------- 说明卡 ----------
            if (phase == "idle" || phase == "claimed") {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                    modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(20.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("当前积分", style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer)
                        Text("${AppState.user?.points ?: 0}", style = MaterialTheme.typography.displaySmall,
                            fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    AdStat("今日已看", "${s?.todayCount ?: 0}/${s?.dailyLimit ?: 20} 次", Modifier.weight(1f))
                    AdStat("剩余次数", "${s?.remain ?: 0} 次", Modifier.weight(1f))
                    AdStat("累计获得", "${s?.totalPoints ?: 0} 积分", Modifier.weight(1f))
                }
                GlassActionButton(filled = true,
                    onClick = { startAd() },
                    enabled = !busy && (s?.enabled != false) && (s?.remain ?: 0) > 0,
                    modifier = Modifier.fillMaxWidth().height(50.dp),
                ) {
                    if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    else {
                        Icon(Icons.Default.PlayCircle, null, Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            when {
                                s?.enabled == false -> "看广告得积分已关闭"
                                (s?.remain ?: 0) <= 0 -> "今日次数已用完"
                                else -> "看广告得 ${s?.reward ?: 50} 积分"
                            },
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
                Text(
                    "规则：广告来自央视网公益频道（公益广告 / 公益视频）。必须完整看完一条才能获得 ${s?.reward ?: 50} 积分，"
                        + "每天最多 ${s?.dailyLimit ?: 20} 次，看完一次领一次，次日 0 点重置。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            if (msg.isNotBlank()) {
                Text(msg, style = MaterialTheme.typography.bodySmall,
                    color = if (phase == "claimed") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

/** 把广告 MP4 下载到应用缓存目录 (播放器只读本地文件, 最稳) */
private fun downloadAd(ctx: android.content.Context, url: String, adId: Long): java.io.File? {
    return try {
        val dir = java.io.File(ctx.cacheDir, "ads").apply { mkdirs() }
        // 顺手清掉 1 天前的旧广告
        val cutoff = System.currentTimeMillis() - 24 * 3600 * 1000L
        dir.listFiles()?.forEach { f -> if (f.lastModified() < cutoff) f.delete() }
        val out = java.io.File(dir, "ad_$adId.mp4")
        if (out.exists() && out.length() > 20000) return out
        val client = okhttp3.OkHttpClient.Builder()
            .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
            .build()
        client.newCall(okhttp3.Request.Builder().url(url).build()).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val body = resp.body ?: return null
            out.outputStream().use { os -> body.byteStream().copyTo(os) }
        }
        if (out.length() > 20000) out else null
    } catch (e: Exception) { null }
}

@Composable
private fun AdStat(label: String, value: String, modifier: Modifier = Modifier) {
    Card(modifier = modifier, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(vertical = 10.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(value, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Text(label, style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
