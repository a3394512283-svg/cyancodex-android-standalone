package com.canglan.cyancodex.data

import android.content.Context
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * 服务端配置中心（版本分发 + 公告 + 远程开关）
 * ==========================================================================
 *
 * ## 它解决什么问题
 *
 * 在此之前，每一次"服务端想告诉客户端点什么"都要走一遍完整发版流程：
 * 改代码 → 打包 → 上传 APK → 用户手动安装。三个典型场景都很痛：
 *
 * | 场景 | 以前 | 现在 |
 * |---|---|---|
 * | 有新版本 | 用户永远不会知道 | 启动时提示，可一键去下载 |
 * | 线上出了严重缺陷 | 只能祈祷用户自己更新 | 抬高 `min_code`，强制升级 |
 * | 某功能要紧急下线 | 只能发版 | 关掉对应的 `flag_*`，接口立刻 503 |
 *
 * ## 缓存策略
 *
 * 拉到的结果落进 SharedPreferences，下次启动**先用缓存**把界面画出来，
 * 再后台刷新 —— 与 [FreeAi.cachedStatus] 同一套路。理由也一样：
 * 网络经 Cloudflare 一圈要几秒，界面不能为此空白。
 *
 * ⚠️ 这些都是**缓存**，不是设置。所以它们被列进了
 * [SettingsBackup.LOCAL_ONLY]：换机后第一次联网自然会重新拿到，
 * 没必要占备份体积。
 */
object AppConfig {

    /** 配置缓存（公告 + 开关 + 限制） */
    private const val K_CFG = "app_config_cache"

    /** 版本信息缓存 */
    private const val K_VER = "app_version_cache"

    /** 公告。`id` 变了就是一条新公告，客户端据此去重（不必反复打扰同一个人） */
    data class Notice(
        val id: String = "",
        val text: String = "",
        /** info / warn / danger */
        val level: String = "info",
        val url: String = "",
        val until: Long = 0L,
    )

    /** 最新版本信息 */
    data class Version(
        val latest: String = "",
        val code: Long = 0L,
        /** > 0 表示低于此 versionCode 必须升级 */
        val minCode: Long = 0L,
        val url: String = "",
        val md5: String = "",
        val size: Long = 0L,
        val notes: List<String> = emptyList(),
        val publishedAt: Long = 0L,
    ) {
        /** 有没有可下载的安装包（只推了版本号、还没传包时为 false） */
        val hasDownload: Boolean get() = url.isNotBlank()
    }

    /** 远程开关。语义是"这个能力服务端还提不提供"，关掉后对应入口应隐藏 */
    data class Flags(
        val freeAi: Boolean = true,
        val search: Boolean = true,
        val convSync: Boolean = true,
        val share: Boolean = true,
    )

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences("cyancodex", Context.MODE_PRIVATE)

    /**
     * 从免费 AI 的 base 反推 API 根。
     *
     * `FreeAi.BASE` 是 `.../api/v1/ai/`，去掉末尾的 `ai` 就得到 `.../api/v1/`。
     * 走这条路而不是写死常量，是为了让调试用的 `freeai_base_url` 覆盖**同时生效** ——
     * 否则改了指中转地址、配置中心还在打生产，排查时会非常迷惑。
     */
    fun apiRoot(ctx: Context): String {
        val ai = FreeAi.baseUrl(ctx).trimEnd('/')
        val root = if (ai.endsWith("/ai")) ai.dropLast(3) else ai
        return root.trimEnd('/') + "/"
    }

    private fun get(ctx: Context, key: String): String? =
        prefs(ctx).getString(key, null)

    // ------------------------------------------------------------------
    //  读缓存（纯本地，不发网络）
    // ------------------------------------------------------------------

    fun cachedNotice(ctx: Context): Notice? = try {
        val s = get(ctx, K_CFG) ?: return null
        val o = JsonParser().parse(s).asJsonObject
        val n = o.getAsJsonObject("notice") ?: return null
        val text = n.get("text")?.asString.orEmpty()
        if (text.isBlank()) null else Notice(
            id = n.get("id")?.asString.orEmpty(),
            text = text,
            level = n.get("level")?.asString ?: "info",
            url = n.get("url")?.asString.orEmpty(),
            until = n.get("until")?.asLong ?: 0L,
        )
    } catch (e: Exception) {
        null
    }

    fun cachedFlags(ctx: Context): Flags = try {
        val s = get(ctx, K_CFG) ?: return Flags()
        val f = JsonParser().parse(s).asJsonObject.getAsJsonObject("flags") ?: return Flags()
        Flags(
            freeAi = f.get("free_ai")?.asBoolean ?: true,
            search = f.get("search")?.asBoolean ?: true,
            convSync = f.get("conv_sync")?.asBoolean ?: true,
            share = f.get("share")?.asBoolean ?: true,
        )
    } catch (e: Exception) {
        Flags()
    }

    fun cachedVersion(ctx: Context): Version? = try {
        val s = get(ctx, K_VER) ?: return null
        parseVersion(JsonParser().parse(s).asJsonObject)
    } catch (e: Exception) {
        null
    }

    private fun parseVersion(o: com.google.gson.JsonObject): Version = Version(
        latest = o.get("latest")?.asString.orEmpty(),
        code = o.get("code")?.asLong ?: 0L,
        minCode = o.get("min_code")?.asLong ?: 0L,
        url = o.get("url")?.asString.orEmpty(),
        md5 = o.get("md5")?.asString.orEmpty(),
        size = o.get("size")?.asLong ?: 0L,
        notes = try {
            o.getAsJsonArray("notes")?.mapNotNull { it.asString } ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        },
        publishedAt = o.get("published_at")?.asLong ?: 0L,
    )

    /**
     * 当前安装包的 versionCode。
     *
     * ⚠️ 不用 `BuildConfig.VERSION_CODE` —— 本工程没开 `buildFeatures.buildConfig`，
     *    那个类不存在。走 PackageManager 是唯一稳妥的取法。
     */
    fun installedCode(ctx: Context): Long = runCatching {
        val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        androidx.core.content.pm.PackageInfoCompat.getLongVersionCode(pi)
    }.getOrDefault(0L)

    fun installedName(ctx: Context): String = runCatching {
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName.orEmpty()
    }.getOrDefault("")

    /** 有没有新版本（只比 code，不比字符串 —— `2.0.10` 和 `2.0.9` 按字符串比会出错） */
    fun hasUpdate(ctx: Context): Boolean {
        val v = cachedVersion(ctx) ?: return false
        val cur = installedCode(ctx)
        return v.code > 0 && cur > 0 && v.code > cur
    }

    /** 当前版本是否已被服务端判定为"必须升级" */
    fun mustUpdate(ctx: Context): Boolean {
        val v = cachedVersion(ctx) ?: return false
        val cur = installedCode(ctx)
        return v.minCode > 0 && cur > 0 && cur < v.minCode
    }

    // ------------------------------------------------------------------
    //  联网刷新
    // ------------------------------------------------------------------

    private fun httpGet(url: String, token: String?, deviceId: String?): String? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 8_000
                readTimeout = 12_000
                setRequestProperty("Accept", "application/json")
                setRequestProperty("User-Agent", "CyanCodex-Android")
                token?.let { setRequestProperty("Authorization", "Bearer $it") }
                deviceId?.let { setRequestProperty("X-Device-Id", it) }
            }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code in 200..299) text else null
        } catch (e: Exception) {
            DebugStore.logW("AppConfig", "拉取失败 $url: ${e.message}")
            null
        } finally {
            conn?.disconnect()
        }
    }

    /**
     * 刷新配置与版本信息。
     *
     * 两个接口互不依赖，任何一个失败都不影响另一个 —— 所以分开 try，
     * 不做"一个挂了就整体放弃"的处理。
     *
     * @return 是否至少成功了一项
     */
    suspend fun refresh(ctx: Context): Boolean = withContext(Dispatchers.IO) {
        val root = apiRoot(ctx)
        val token = FreeAi.token()
        val dev = runCatching { Session.deviceId }.getOrNull()
        var any = false

        httpGet(root + "app/config", token, dev)?.let { text ->
            runCatching { prefs(ctx).edit().putString(K_CFG, text).apply() }
            any = true
        }
        httpGet(root + "app/version", token, dev)?.let { text ->
            runCatching { prefs(ctx).edit().putString(K_VER, text).apply() }
            any = true
        }
        any
    }
}
