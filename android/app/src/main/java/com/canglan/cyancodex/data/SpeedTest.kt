package com.canglan.cyancodex.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.Random
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

/** 一次测速结果 */
data class SpeedResult(
    val time: Long = System.currentTimeMillis(),
    val pingMs: Int = 0,
    val jitterMs: Int = 0,
    val downMbps: Double = 0.0,
    val upMbps: Double = 0.0,
    val network: String = "",
    val node: String = "",
    val consumedBytes: Long = 0,
)

/** 测速过程中的实时回调 */
data class SpeedProgress(
    val phase: String = "",          // 延迟 / 下载 / 上传
    val instantMbps: Double = 0.0,   // 瞬时速度
    val avgMbps: Double = 0.0,       // 本轮平均速度
    val doneBytes: Long = 0,
    val progress: Float = 0f,        // 0..1
)

/**
 * 网络测速: 打我们自己服务器的专用测速接口
 *   GET  api/speedtest/ping               延迟与抖动
 *   GET  api/speedtest/download?bytes=N   下载 (服务器流式吐随机数据)
 *   POST api/speedtest/upload             上传 (服务器只统计字节数)
 *   GET  api/speedtest/node               节点信息
 *
 * 之所以用自家服务器: App 本来就走 ServerDns 直连它, 测出来的就是 App 实际能拿到的速度,
 * 而且不依赖任何第三方测速站点, APK 里也不会多出额外域名。
 */
object SpeedTest {

    private const val PREFS = "cyancodex_speedtest"
    private const val MAX_HISTORY = 15
    private const val DOWNLOAD_SECONDS = 10
    private const val UPLOAD_SECONDS = 8
    private const val CHUNK = 256 * 1024
    private const val WARMUP_MS = 700L

    @Volatile private var sp: android.content.SharedPreferences? = null
    private val gson = Gson()

    fun init(ctx: Context) {
        if (sp == null) {
            synchronized(this) {
                if (sp == null) sp = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            }
        }
    }

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .dns(ServerDns)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    private fun url(path: String): String = Session.baseUrl.trimEnd('/') + "/" + path

    // ---------------- 历史记录 ----------------

    fun history(): List<SpeedResult> {
        val raw = runCatching { sp?.getString("history", null) }.getOrNull() ?: return emptyList()
        return runCatching {
            val t = object : TypeToken<MutableList<SpeedResult>>() {}.type
            gson.fromJson<MutableList<SpeedResult>>(raw, t) ?: mutableListOf()
        }.getOrDefault(mutableListOf())
    }

    private fun save(r: SpeedResult) {
        val cur = sp ?: return
        val list = (listOf(r) + history()).take(MAX_HISTORY)
        runCatching { cur.edit().putString("history", gson.toJson(list)).apply() }
    }

    fun clearHistory() {
        runCatching { sp?.edit()?.remove("history")?.apply() }
    }

    /** 当前网络类型 (WiFi / 移动网络 / 以太网 / 其它) */
    fun networkType(ctx: Context): String {
        return runCatching {
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val n = cm.activeNetwork ?: return@runCatching "未连接"
            val cap = cm.getNetworkCapabilities(n) ?: return@runCatching "未连接"
            when {
                cap.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WiFi"
                cap.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "移动网络"
                cap.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "以太网"
                cap.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
                else -> "其它"
            }
        }.getOrDefault("未知")
    }

    // ---------------- 单项测试 ----------------

    /** 延迟与抖动 (连打 6 次, 丢掉第一次连接建立的开销) */
    suspend fun ping(onProgress: (SpeedProgress) -> Unit = {}): Pair<Int, Int> = withContext(Dispatchers.IO) {
        val samples = ArrayList<Long>(6)
        for (i in 0 until 6) {
            coroutineContext.ensureActiveCompat()
            val cost = runCatching {
                val req = Request.Builder()
                    .url(url("api/speedtest/ping?t=" + System.nanoTime()))
                    .header("Cache-Control", "no-cache")
                    .build()
                val t0 = System.nanoTime()
                client.newCall(req).execute().use { resp ->
                    resp.body?.bytes()
                }
                (System.nanoTime() - t0) / 1_000_000
            }.getOrDefault(-1L)
            if (cost < 0) throw java.io.IOException("连不上测速节点")
            if (i > 0) samples.add(cost)
            onProgress(SpeedProgress("延迟", progress = (i + 1) / 6f))
        }
        val avg = samples.average().toInt()
        var jitter = 0.0
        for (i in 1 until samples.size) jitter += kotlin.math.abs(samples[i] - samples[i - 1])
        val j = if (samples.size > 1) (jitter / (samples.size - 1)).toInt() else 0
        avg to j
    }

    /** 下载测速: 固定时长内尽量多读, 结果取预热之后窗口的平均速度 (Mbps) */
    suspend fun download(
        seconds: Int = DOWNLOAD_SECONDS,
        onProgress: (SpeedProgress) -> Unit = {},
    ): Double = withContext(Dispatchers.IO) {
        val budgetMs = seconds * 1000L
        val req = Request.Builder()
            .url(url("api/speedtest/download?bytes=" + (64L * 1024 * 1024) + "&t=" + System.nanoTime()))
            .header("Cache-Control", "no-cache")
            .build()
        var consumed = 0L
        var result = 0.0
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw java.io.IOException("测速节点返回 ${resp.code}")
            val body = resp.body ?: throw java.io.IOException("测速节点没有返回数据")
            val input = body.byteStream()
            val buf = ByteArray(64 * 1024)
            val t0 = System.nanoTime()
            var warmBytes = 0L
            var warmAt = 0L
            var lastAt = t0
            var lastBytes = 0L
            var read = 0L
            val localConsumed = ArrayList<Long>(1)
            while (true) {
                coroutineContext.ensureActiveCompat()
                val n = input.read(buf)
                if (n <= 0) break
                read += n
                val now = System.nanoTime()
                val elapsedMs = (now - t0) / 1_000_000
                if (warmAt == 0L && elapsedMs >= WARMUP_MS) {
                    warmAt = now
                    warmBytes = read
                }
                if (now - lastAt >= 250_000_000L) {
                    val inst = (read - lastBytes) * 8.0 / ((now - lastAt) / 1e9) / 1e6
                    val avgWindow = if (warmAt != 0L && now > warmAt) (read - warmBytes) * 8.0 / ((now - warmAt) / 1e9) / 1e6 else 0.0
                    onProgress(
                        SpeedProgress(
                            phase = "下载",
                            instantMbps = inst,
                            avgMbps = avgWindow,
                            doneBytes = read,
                            progress = (elapsedMs.toFloat() / budgetMs).coerceIn(0f, 1f),
                        )
                    )
                    lastAt = now
                    lastBytes = read
                }
                if (elapsedMs >= budgetMs) break
            }
            val endMs = (System.nanoTime() - t0) / 1_000_000
            consumed = read
            localConsumed.add(consumed)
            val measuredMs = if (warmAt != 0L) endMs - WARMUP_MS else endMs
            val measuredBytes = if (warmAt != 0L) read - warmBytes else read
            result = if (measuredMs > 0) measuredBytes * 8.0 / (measuredMs / 1000.0) / 1e6 else 0.0
            onProgress(SpeedProgress("下载", result, result, consumed, 1f))
        }
        result
    }

    /** 上传测速: 固定时长内反复 POST 256KB, 结果取预热之后窗口的平均速度 (Mbps) */
    suspend fun upload(
        seconds: Int = UPLOAD_SECONDS,
        onProgress: (SpeedProgress) -> Unit = {},
    ): Double = withContext(Dispatchers.IO) {
        val budgetMs = seconds * 1000L
        val chunk = ByteArray(CHUNK).also { Random().nextBytes(it) }
        val media = "application/octet-stream".toMediaType()
        val t0 = System.nanoTime()
        var sent = 0L
        var warmSent = 0L
        var warmAt = 0L
        var lastAt = t0
        var lastBytes = 0L
        var result = 0.0
        while (true) {
            coroutineContext.ensureActiveCompat()
            val req = Request.Builder()
                .url(url("api/speedtest/upload?t=" + System.nanoTime()))
                .post(chunk.toRequestBody(media))
                .build()
            client.newCall(req).execute().use { resp ->
                resp.body?.bytes()
                if (!resp.isSuccessful) throw java.io.IOException("测速节点返回 ${resp.code}")
            }
            sent += CHUNK
            val now = System.nanoTime()
            val elapsedMs = (now - t0) / 1_000_000
            if (warmAt == 0L && elapsedMs >= WARMUP_MS) {
                warmAt = now
                warmSent = sent
            }
            if (now - lastAt >= 250_000_000L) {
                val inst = (sent - lastBytes) * 8.0 / ((now - lastAt) / 1e9) / 1e6
                val avgWindow = if (warmAt != 0L && now > warmAt) (sent - warmSent) * 8.0 / ((now - warmAt) / 1e9) / 1e6 else 0.0
                onProgress(
                    SpeedProgress(
                        phase = "上传",
                        instantMbps = inst,
                        avgMbps = avgWindow,
                        doneBytes = sent,
                        progress = (elapsedMs.toFloat() / budgetMs).coerceIn(0f, 1f),
                    )
                )
                lastAt = now
                lastBytes = sent
            }
            if (elapsedMs >= budgetMs) break
        }
        val endMs = (System.nanoTime() - t0) / 1_000_000
        val measuredMs = if (warmAt != 0L) endMs - WARMUP_MS else endMs
        val measuredBytes = if (warmAt != 0L) sent - warmSent else sent
        result = if (measuredMs > 0) measuredBytes * 8.0 / (measuredMs / 1000.0) / 1e6 else 0.0
        onProgress(SpeedProgress("上传", result, result, sent, 1f))
        result
    }

    /** 节点名称 (取不到就显示域名) */
    suspend fun nodeName(): String = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder().url(url("api/speedtest/node")).build()
            val txt = client.newCall(req).execute().use { it.body?.string() ?: "" }
            org.json.JSONObject(txt).optString("name").ifBlank { Session.apiHost }
        }.getOrDefault(Session.apiHost)
    }

    /** 完整测速流程 */
    suspend fun run(
        ctx: Context,
        upload: Boolean = true,
        onPhase: (String) -> Unit = {},
        onProgress: (SpeedProgress) -> Unit = {},
    ): SpeedResult {
        init(ctx)
        val node = nodeName()
        val net = networkType(ctx)
        onPhase("延迟")
        val (ping, jitter) = ping(onProgress)
        onPhase("下载")
        val down = download(onProgress = onProgress)
        var up = 0.0
        if (upload) {
            onPhase("上传")
            up = upload(onProgress = onProgress)
        }
        onPhase("完成")
        val consumed = ((down + up) / 8.0 * 1_000_000).toLong()
        val r = SpeedResult(
            time = System.currentTimeMillis(),
            pingMs = ping, jitterMs = jitter,
            downMbps = down, upMbps = up,
            network = net, node = node,
            consumedBytes = consumed,
        )
        save(r)
        return r
    }
}

/** 协程取消检查 (避免 import 太多) */
private fun kotlin.coroutines.CoroutineContext.ensureActiveCompat() {
    val job = this[kotlinx.coroutines.Job]
    if (job != null && !job.isActive) throw kotlinx.coroutines.CancellationException("测速已取消")
}
