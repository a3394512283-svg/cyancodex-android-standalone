package com.canglan.cyancodex.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * MIDI 曲库: 两家都接
 *   ① BitMidi(第三方) —— 搜索接口是公开 JSON, 直接下 .mid
 *   ② 沧蓝自建精选 —— 服务器上的静态目录 /downloads/midi/ , 版权干净(公共领域/自制)
 *
 * 版权说明(如实写在界面上): 第三方曲库的曲子由网友上传, 仅供个人学习试听;
 * 我们自建的精选只放公共领域或自制旋律。
 *
 * 网络注意(踩过的坑, 别改回去):
 *   必须用 OkHttp + ServerDns, 跟 App 其它请求保持一致。
 *   最早这里用的是裸 HttpURLConnection(走系统 DNS): 国内 DNS 被污染时,
 *   其它功能都正常、只有曲库请求失败 —— 用户反馈「刷新精选刷新不出来」(意见 #97)。
 *   ServerDns 只对本项目自己的域名强制解析到高防 IP, 别的域名照走系统 DNS,
 *   所以同一个 client 也能安全地访问 BitMidi。
 */
object MidiLibrary {

    data class Track(
        val id: String,
        val name: String,
        val source: String,      // "bitmidi" | "cyan"
        val url: String,         // 可直接下载的绝对地址
        val plays: Int = 0,
        val sizeKb: Int = 0,
        val note: String = "",   // 自建曲库的说明/作者/风格
    )

    private const val UA = "CyanCodex-Android"

    /** 单文件上限 4MB: MIDI 一般几十 KB, 防意外大文件把内存吃光 */
    private const val MAX_BYTES = 4 * 1024 * 1024

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .dns(ServerDns)
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    /** 第三方曲库搜索 */
    suspend fun searchBitMidi(query: String, page: Int = 0): List<Track> = withContext(Dispatchers.IO) {
        val q = URLEncoder.encode(query, "UTF-8")
        val body = httpGet("https://bitmidi.com/api/midi/search?q=$q&page=$page") ?: return@withContext emptyList()
        val out = ArrayList<Track>()
        runCatching {
            val arr = JSONObject(body).getJSONObject("result").getJSONArray("results")
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val dl = o.optString("downloadUrl")
                if (dl.isEmpty()) continue
                out.add(
                    Track(
                        id = "bitmidi-" + o.optInt("id"),
                        name = o.optString("name").removeSuffix(".mid"),
                        source = "bitmidi",
                        url = if (dl.startsWith("http")) dl else "https://bitmidi.com$dl",
                        plays = o.optInt("plays"),
                    )
                )
            }
        }
        out
    }

    /** 沧蓝自建精选(服务器静态 JSON + 静态文件, 不需要额外接口) */
    suspend fun curated(): List<Track> = withContext(Dispatchers.IO) {
        val body = httpGet(curatedUrl()) ?: return@withContext emptyList()
        val out = ArrayList<Track>()
        runCatching {
            val arr = JSONObject(body).getJSONArray("items")
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val file = o.optString("file")
                if (file.isEmpty()) continue
                out.add(
                    Track(
                        id = "cyan-" + file,
                        name = o.optString("title").ifEmpty { file.removeSuffix(".mid") },
                        source = "cyan",
                        url = baseUrl() + "/downloads/midi/" + file,
                        sizeKb = o.optInt("size_kb"),
                        note = o.optString("note"),
                    )
                )
            }
        }
        out
    }

    /** 精选清单地址(单独抽出来, 方便排查) */
    fun curatedUrl(): String = baseUrl() + "/downloads/midi/index.json"

    private fun baseUrl(): String = Session.baseUrl.trimEnd('/')

    /** 下载 .mid 原始字节 */
    suspend fun download(url: String): ByteArray = withContext(Dispatchers.IO) {
        httpGetBytes(url) ?: throw IllegalStateException("下载失败，请检查网络后重试")
    }

    // ---------------- HTTP(与 App 其它请求同一套 DNS 策略) ----------------

    private fun httpGet(url: String): String? =
        runCatching { httpGetBytes(url)?.toString(Charsets.UTF_8) }.getOrNull()

    private fun httpGetBytes(url: String): ByteArray? {
        return try {
            val req = Request.Builder().url(url).header("User-Agent", UA).build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val body = resp.body ?: return null
                val bytes = body.bytes()
                if (bytes.size > MAX_BYTES) bytes.copyOf(MAX_BYTES) else bytes
            }
        } catch (e: Exception) {
            null
        }
    }
}
