package com.canglan.cyancodex.data

import android.content.Context
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * 指令分享短链
 * ==========================================================================
 *
 * 把一段指令（或一次对话）发到服务端，换回一个 `https://…/s/Ab3xK9` 的链接。
 * 链接是**公开可访问**的网页，对方不用装 App 就能看到内容 —— 这是 Minecraft
 * 玩家社群最自然的传播方式：群里发一个能直接看的链接，比发截图有用得多。
 *
 * ## 为什么必须登录
 *
 * 不登录就能发公开内容，等于开了一个零成本的垃圾投放口。服务端在
 * `share_post` 里强制校验登录态，并且按账号限每日条数（`share_daily_quota`）。
 *
 * ## 隐私提醒
 *
 * 分享出去的内容是**公开的**，任何人都能通过链接看到。所以：
 * - 只在用户**主动点分享**时才调用，绝不自动上传；
 * - UI 上必须让用户知道"这条链接谁都能打开"；
 * - 服务端有 90 天过期与下架能力（`ai_server.py unshare <sid>`）。
 */
object Share {

    data class Result(
        val ok: Boolean,
        val url: String = "",
        val message: String = "",
    )

    /** 本地能不能分享（登录态 + 服务端开关）。真正的判定仍在服务端 */
    fun canShare(ctx: Context): Boolean =
        FreeAi.token() != null && AppConfig.cachedFlags(ctx).share

    /**
     * 创建分享链接。
     *
     * @param title 标题（可空，服务端会兜底成"指令分享"）
     * @param content 正文，上限由服务端配置（默认 8000 字符）
     * @param kind `cmd` / `conv` / `text`，只影响页面上那个小标签
     */
    suspend fun create(
        ctx: Context,
        title: String,
        content: String,
        kind: String = "cmd",
    ): Result = withContext(Dispatchers.IO) {
        val token = FreeAi.token()
            ?: return@withContext Result(false, message = "请先登录账号后再分享")

        if (!AppConfig.cachedFlags(ctx).share) {
            return@withContext Result(false, message = "服务端当前未开放分享功能")
        }
        if (content.isBlank()) {
            return@withContext Result(false, message = "没有可分享的内容")
        }

        val body = JsonObject().apply {
            addProperty("title", title.take(120))
            addProperty("kind", kind)
            addProperty("content", content)
        }.toString()

        postJson(ctx, body, token)
    }

    /**
     * 创建**整段对话**分享链接 (2026-10-17)。
     *
     * 与 [create] 的区别只在请求体: 服务端支持 `{title, messages:[{role,content}]}`,
     * 会把整段问答渲染成一页, 比只分享一条回答有用得多。
     *
     * 只上传 user / assistant 且正文非空的消息 —— 本地 `err` 气泡、空占位都不是对话内容。
     * 服务端限制: 1..200 条、单条 ≤20000 字符、总计 ≤60000 字符, 超出会被拒。
     */
    suspend fun createConversation(
        ctx: Context,
        title: String,
        messages: List<UiMsg>,
    ): Result = withContext(Dispatchers.IO) {
        val token = FreeAi.token()
            ?: return@withContext Result(false, message = "请先登录账号后再分享")

        if (!AppConfig.cachedFlags(ctx).share) {
            return@withContext Result(false, message = "服务端当前未开放分享功能")
        }

        val picked = messages.filter {
            (it.role == "user" || it.role == "assistant") && it.content.isNotBlank()
        }
        if (picked.isEmpty()) {
            return@withContext Result(false, message = "没有可分享的内容")
        }

        val arr = JsonArray()
        picked.forEach { m ->
            arr.add(JsonObject().apply {
                addProperty("role", m.role)
                addProperty("content", m.content)
            })
        }
        val body = JsonObject().apply {
            addProperty("title", title.take(120))
            add("messages", arr)
        }.toString()

        postJson(ctx, body, token)
    }

    /** 发一次 POST /share 并解析统一响应。阻塞调用, 必须在 IO 线程上用。 */
    private fun postJson(ctx: Context, body: String, token: String): Result {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(AppConfig.apiRoot(ctx) + "share").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = 10_000
                readTimeout = 25_000
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Accept", "application/json")
                setRequestProperty("User-Agent", "CyanCodex-Android")
                setRequestProperty("Authorization", "Bearer $token")
                runCatching { setRequestProperty("X-Device-Id", Session.deviceId) }
            }
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }

            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()

            val o = runCatching { JsonParser().parse(text).asJsonObject }.getOrNull()
                ?: return Result(false, message = "服务端返回异常（HTTP $code）")

            if (code !in 200..299 || o.get("ok")?.asBoolean == false) {
                val msg = runCatching {
                    o.getAsJsonObject("error").get("message").asString
                }.getOrDefault("分享失败（HTTP $code）")
                return Result(false, message = msg)
            }

            val url = o.get("url")?.asString.orEmpty()
            if (url.isBlank()) {
                return Result(false, message = "服务端没有返回链接")
            }
            DebugStore.logI("Share", "已创建分享链接 $url")
            Result(true, url = url)
        } catch (e: Exception) {
            DebugStore.logW("Share", "分享失败: ${e.message}")
            Result(false, message = "网络请求失败：" + (e.message ?: "未知错误"))
        } finally {
            conn?.disconnect()
        }
    }
}
