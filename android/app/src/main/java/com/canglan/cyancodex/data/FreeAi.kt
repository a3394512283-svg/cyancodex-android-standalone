package com.canglan.cyancodex.data

import android.content.Context
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * 「限时免费」AI 通道
 * ==========================================================================
 *
 * 二改自带的**第三个 AI 通道**, 与「官方通道(扣积分)」「自备 Key」并列。
 *
 * ## 它和另外两条有什么不同
 *
 * | | 上游 Key 在哪 | 计费 | 登录态 |
 * |---|---|---|---|
 * | 自备 Key | 用户自己的设备上 | 用户自己付 | 不需要 |
 * | 官方通道 | 官方服务器 | 扣积分 | 必须 |
 * | **限时免费** | **二改作者的服务器** | **免费(有每日额度)** | 必须 |
 *
 * 客户端**永远拿不到上游 Key** —— 请求发到 [BASE] 由服务端转发, 服务端先校验
 * 账号登录态、再查每日额度, 通过后才用作者自己的 Key 调上游。
 *
 * ## 为什么能直接复用 [AiClient]
 *
 * 中转服务实现的是 **OpenAI 兼容**协议, 所以这里不需要写新的请求逻辑 ——
 * 只要把 base 指向 [BASE]、把 apiKey 换成**账号令牌**即可:
 *
 *     base = https://cyancodex.bluestudio.cn/api/v1/ai/
 *     key  = Session.token            ← 服务端拿它去官方 api/auth/me 校验
 *
 * 流式、工具调用、思考参数全部原样透传, 与自备 Key 那条路的体验一致。
 *
 * ## 限制: Cookie 登录用不了
 *
 * Cookie 登录时 [Session.token] 是哨兵值 [Session.COOKIE_TOKEN], 不是真令牌,
 * 服务端无法用它校验身份 —— 这种情况下 [canUse] 返回 false, UI 应引导用户
 * 用账号密码重新登录。
 */
object FreeAi {

    /** 中转服务地址 (OpenAI 兼容: base + "chat/completions") */
    const val BASE = "https://cyancodex.bluestudio.cn/api/v1/ai/"

    /** 调试用: 自定义中转地址 */
    private const val K_BASE_OVERRIDE = "freeai_base_url"

    /** 服务端返回的通道状态 */
    data class Status(
        val enabled: Boolean = false,
        val model: String = "CyanFree",
        val announcement: String = "",
        val requireLogin: Boolean = true,
        val loggedIn: Boolean = false,
        val limit: Int = 0,
        val used: Int = 0,
        val remain: Int = -1,
    ) {
        /** 剩余次数文案 */
        fun quotaText(): String = when {
            !enabled -> "未开放"
            limit <= 0 -> "不限次数"
            remain <= 0 -> "今日额度已用完"
            else -> "今日剩余 $remain 次"
        }
    }

    fun baseUrl(ctx: Context): String {
        val custom = ctx.applicationContext
            .getSharedPreferences("cyancodex", Context.MODE_PRIVATE)
            .getString(K_BASE_OVERRIDE, "") ?: ""
        return (if (custom.isBlank()) BASE else custom).trimEnd('/') + "/"
    }

    /** 可用于免费通道的账号令牌; null = 不可用 (未登录 / Cookie 登录) */
    fun token(): String? {
        val t = Session.token
        return if (t.isNullOrBlank() || t == Session.COOKIE_TOKEN) null else t
    }

    /** 当前是否具备使用条件 (只判本地条件, 不代表服务端已开放) */
    fun canUse(): Boolean = token() != null

    /**
     * 探测服务端状态。失败返回 null (网络问题不应让设置页崩掉)。
     * ⚠️ 会发起网络请求, 必须在 IO 线程调用。
     */
    suspend fun status(ctx: Context): Status? = withContext(Dispatchers.IO) {
        var conn: HttpURLConnection? = null
        try {
            conn = (URL(baseUrl(ctx) + "status").openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 8_000
                readTimeout = 12_000
                setRequestProperty("Accept", "application/json")
                setRequestProperty("User-Agent", "CyanCodex-Android")
                token()?.let { setRequestProperty("Authorization", "Bearer $it") }
                runCatching { setRequestProperty("X-Device-Id", Session.deviceId) }
            }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) return@withContext null
            val o = JsonParser().parse(text).asJsonObject
            val q = o.getAsJsonObject("quota")
            Status(
                enabled = o.get("enabled")?.asBoolean ?: false,
                model = o.get("model")?.asString ?: "CyanFree",
                announcement = o.get("announcement")?.asString ?: "",
                requireLogin = o.get("require_login")?.asBoolean ?: true,
                loggedIn = o.get("logged_in")?.asBoolean ?: false,
                limit = q?.get("limit")?.asInt ?: 0,
                used = q?.get("used")?.asInt ?: 0,
                remain = q?.get("remain")?.asInt ?: -1,
            )
        } catch (e: Exception) {
            DebugStore.logW("FreeAi", "状态探测失败: " + e.message)
            null
        } finally {
            conn?.disconnect()
        }
    }
}
