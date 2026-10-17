package com.canglan.cyancodex.data

import android.content.Context
import com.canglan.cyancodex.ai.AiConvStore
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * AI 会话云同步
 * ==========================================================================
 *
 * ## 为什么单独做，而不是塞进设置备份
 *
 * 会话是**用户内容**，不是设置。混在 [SettingsBackup] 里有两个直接后果：
 *
 * 1. `ai_convs` 是备份体积超限的头号元凶 —— 它是**无上限**的（聊得越多越大），
 *    而设置本身只有几十 KB。两者捆在一起，等于让"我只想同步一下偏好设置"
 *    被几百 KB 的聊天记录绑架。
 * 2. 语义不对。"恢复设置"不该把半年前的对话一起灌回来。
 *
 * 所以这里把它们拆开：设置走 [SettingsBackup]，会话走本类。
 *
 * ## 冲突策略：last-write-wins
 *
 * 两台设备交替改同一条会话时，后推的旧数据会盖掉新的。按 `updatedAt` 裁决，
 * 至少保证"更新的内容不会因为后到而被覆盖"。服务端做同一套判断
 * （`ai_common.conv_apply`），两边规则一致，不会来回打架。
 *
 * ## 游标为什么用服务端时间
 *
 * 增量拉取用 `since` 游标，取的是**服务端**返回的 `server_time`，不是本机的
 * `System.currentTimeMillis()`。手机时钟不准（手动改过、时区错乱）时，用本地
 * 时间当游标会漏数据 —— 而漏数据在同步场景里是最难被发现的一类 bug。
 *
 * ## 删除怎么同步
 *
 * 服务端留"墓碑"（`deleted=1`）而不是直接删行。否则 A 设备删了会话、B 设备
 * 下次拉取时看不到它，就会以为"服务端没有"，于是把它**重新推上去**复活。
 */
object ConvSync {

    private const val K_ENABLED = "conv_sync_enabled"
    private const val K_SINCE = "conv_sync_since"
    private const val K_LAST_AT = "conv_sync_last_at"
    private const val K_LAST_MSG = "conv_sync_last_msg"

    /** 一次最多推几条（服务端上限 50，这里留余量） */
    private const val MAX_PUSH = 20

    /** 单次推送字节预算，服务端请求体上限 512 KB，留出余量 */
    private const val PUSH_BUDGET = 400 * 1024

    /** 单条会话上限，超过就跳过（服务端同样会拒） */
    private const val MAX_ONE = 256 * 1024

    private val gson = Gson()

    data class Result(
        val ok: Boolean,
        val pushed: Int = 0,
        val pulled: Int = 0,
        val message: String = "",
    )

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences("cyancodex", Context.MODE_PRIVATE)

    // ------------------------------------------------------------------
    //  开关与状态
    // ------------------------------------------------------------------

    /** 默认开启 —— 用户没表态时，多设备用户更需要它 */
    fun isEnabled(ctx: Context): Boolean = prefs(ctx).getBoolean(K_ENABLED, true)

    fun setEnabled(ctx: Context, v: Boolean) {
        prefs(ctx).edit().putBoolean(K_ENABLED, v).apply()
    }

    fun lastSyncAt(ctx: Context): Long = prefs(ctx).getLong(K_LAST_AT, 0L)

    fun lastMessage(ctx: Context): String = prefs(ctx).getString(K_LAST_MSG, "").orEmpty()

    private fun since(ctx: Context): Long = prefs(ctx).getLong(K_SINCE, 0L)

    /** 用户清了本地数据 / 换账号时，把游标归零，下次全量拉 */
    fun resetCursor(ctx: Context) {
        prefs(ctx).edit().remove(K_SINCE).apply()
    }

    // ------------------------------------------------------------------
    //  同步
    // ------------------------------------------------------------------

    /**
     * 双向同步一次：**推**本地变更 → **拉**服务端变更（同一个请求里完成）。
     *
     * 为什么不拆成两个请求：移动网络下一次往返的成本远高于服务端多做一点活。
     * 服务端 `convs_post` 在写完后就地回传 `since` 之后的全部变化，一次搞定。
     */
    suspend fun sync(ctx: Context): Result = withContext(Dispatchers.IO) {
        val token = FreeAi.token()
            ?: return@withContext Result(false, message = "未登录，无法同步会话")

        if (!AppConfig.cachedFlags(ctx).convSync) {
            return@withContext Result(false, message = "服务端当前未开放会话同步")
        }
        if (!isEnabled(ctx)) {
            return@withContext Result(false, message = "本地已关闭会话同步")
        }

        val push = buildPush()
        val req = JsonObject().apply {
            add("convs", push)
            addProperty("since", since(ctx))
        }

        val dev = runCatching { Session.deviceId }.getOrNull()
        val resp = postJson(AppConfig.apiRoot(ctx) + "convs", token, req.toString(), dev)
            ?: return@withContext Result(false, message = "网络请求失败，稍后再试")

        val o = runCatching { JsonParser().parse(resp).asJsonObject }.getOrNull()
            ?: return@withContext Result(false, message = "响应解析失败")

        if (o.get("ok")?.asBoolean == false) {
            val msg = runCatching {
                o.getAsJsonObject("error").get("message").asString
            }.getOrDefault("同步失败")
            return@withContext Result(false, message = msg)
        }

        val accepted = o.get("accepted")?.asInt ?: 0
        val pulled = applyRemote(o.getAsJsonArray("convs") ?: JsonArray())

        val st = o.get("server_time")?.asLong ?: 0L
        val msg = "已上传 $accepted 条，拉取 $pulled 条"
        prefs(ctx).edit()
            .putLong(K_SINCE, if (st > 0) st else since(ctx))
            .putLong(K_LAST_AT, System.currentTimeMillis())
            .putString(K_LAST_MSG, msg)
            .apply()

        DebugStore.logI("ConvSync", msg)
        Result(true, accepted, pulled, msg)
    }

    /** 按"最近更新优先"打包本地会话，受条数与字节双预算约束 */
    private fun buildPush(): JsonArray {
        val arr = JsonArray()
        var bytes = 0
        var n = 0
        for (c in AiConvStore.list().sortedByDescending { it.updatedAt }) {
            if (n >= MAX_PUSH) break
            val payload = runCatching { gson.toJson(c) }.getOrNull() ?: continue
            val sz = payload.toByteArray(Charsets.UTF_8).size
            if (sz > MAX_ONE) {
                DebugStore.logW("ConvSync", "会话 ${c.id} 超过单条上限，跳过（${sz / 1024} KB）")
                continue
            }
            if (bytes + sz > PUSH_BUDGET) break
            arr.add(JsonObject().apply {
                addProperty("cid", c.id)
                addProperty("updated_at", c.updatedAt)
                addProperty("title", c.title)
                addProperty("mode", c.mode)
                addProperty("payload", payload)
                addProperty("deleted", false)
            })
            bytes += sz
            n++
        }
        return arr
    }

    /**
     * 把服务端返回的会话合并进本地。
     *
     * 规则与 `ai_common.conv_apply` 完全对称：`updatedAt` 大的赢；墓碑直接删本地。
     * 只在**真的发生变化**时才回写 —— `replaceAll` 会整表序列化，
     * 无变化时白写一次 SharedPreferences 是没必要的开销。
     *
     * @return 实际应用的条数
     */
    private fun applyRemote(items: JsonArray): Int {
        val local = AiConvStore.list().associateBy { it.id }.toMutableMap()
        var changed = 0

        for (el in items) {
            val o = runCatching { el.asJsonObject }.getOrNull() ?: continue
            val cid = o.get("cid")?.asString.orEmpty()
            if (cid.isBlank()) continue
            val upd = o.get("updated_at")?.asLong ?: 0L
            val deleted = o.get("deleted")?.asBoolean ?: false

            if (deleted) {
                if (local.remove(cid) != null) changed++
                continue
            }
            val cur = local[cid]
            if (cur != null && cur.updatedAt >= upd) continue   // 本地更新，保留本地

            val payload = o.get("payload")?.asString.orEmpty()
            if (payload.isBlank()) continue
            val conv = runCatching { gson.fromJson(payload, AiConv::class.java) }.getOrNull()
                ?: continue
            local[cid] = conv
            changed++
        }

        if (changed > 0) {
            AiConvStore.replaceAll(local.values.toList())
            // 当前会话可能被远端删掉了 —— 让 store 重新选一条
            AiConvStore.ensureCurrent()
        }
        return changed
    }

    private fun postJson(url: String, token: String, body: String, deviceId: String?): String? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = 10_000
                readTimeout = 30_000
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Accept", "application/json")
                setRequestProperty("User-Agent", "CyanCodex-Android")
                setRequestProperty("Authorization", "Bearer $token")
                deviceId?.let { setRequestProperty("X-Device-Id", it) }
            }
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code in 200..299) text else {
                DebugStore.logW("ConvSync", "HTTP $code: ${text.take(200)}")
                null
            }
        } catch (e: Exception) {
            DebugStore.logW("ConvSync", "请求失败: ${e.message}")
            null
        } finally {
            conn?.disconnect()
        }
    }
}
