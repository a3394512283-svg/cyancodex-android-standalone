package com.canglan.cyancodex.ai

import com.canglan.cyancodex.data.AiConv
import com.canglan.cyancodex.data.AiMode
import com.canglan.cyancodex.data.Session
import com.canglan.cyancodex.data.UiMsg
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlin.random.Random

/**
 * 本地 AI 多会话存储。
 *
 * 存储位置: `Session.aiConvs` (List<AiConv> 的 JSON) + `Session.aiCurrentConv`。
 *
 * 迁移: 老版本只有一个全局 `ai_messages`。首次访问时 (ai_convs 这个键还没写过),
 * 把老消息整体包成一条会话「历史对话」并设为当前 —— 用户升级后**不会丢历史**。
 */
object AiConvStore {

    private val gson = Gson()
    private val convType = object : TypeToken<MutableList<AiConv>>() {}.type
    private val msgType = object : TypeToken<MutableList<UiMsg>>() {}.type

    /** 新会话的占位标题; 用户发了第一条消息后自动替换成消息摘要 */
    const val DEFAULT_TITLE = "新对话"

    private fun newId(): String =
        System.currentTimeMillis().toString(36) + "-" + Random.nextInt(0x1000, 0x10000).toString(16)

    /** 会话标题: 取第一条用户消息的前 20 字 */
    fun autoTitle(messages: List<UiMsg>): String {
        val first = messages.firstOrNull { it.role == "user" && it.content.isNotBlank() }?.content
            ?.replace('\n', ' ')?.trim().orEmpty()
        if (first.isEmpty()) return DEFAULT_TITLE
        return if (first.length <= 20) first else first.take(20) + "…"
    }

    /**
     * 读全部会话 (按最近更新倒序)。副作用只有"首次迁移", 之后是纯读。
     */
    fun list(): List<AiConv> = load().sortedByDescending { it.updatedAt }

    private fun load(): MutableList<AiConv> {
        val arr: MutableList<AiConv> = try {
            gson.fromJson(Session.aiConvs, convType) ?: mutableListOf()
        } catch (e: Exception) {
            mutableListOf()
        }
        if (Session.hasAiConvs()) return arr

        // ---- 首次迁移: 老单会话 → 一条会话 ----
        val legacy: List<UiMsg> = try {
            gson.fromJson(Session.aiMessages, msgType) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
        val now = System.currentTimeMillis()
        val conv = AiConv(
            id = newId(),
            title = if (legacy.isEmpty()) DEFAULT_TITLE else autoTitle(legacy),
            createdAt = now,
            updatedAt = now,
            messages = legacy,
        )
        Session.aiCurrentConv = conv.id
        write(mutableListOf(conv))
        return mutableListOf(conv)
    }

    private fun write(list: List<AiConv>) {
        Session.aiConvs = gson.toJson(list)
    }

    fun currentId(): String = Session.aiCurrentConv

    fun current(): AiConv? {
        val id = currentId()
        return list().firstOrNull { it.id == id }
    }

    /** 保证有一个可用的当前会话 (会话被删光 / 首次进入时自动建一个) */
    fun ensureCurrent(): AiConv {
        val all = load()
        all.firstOrNull { it.id == Session.aiCurrentConv }?.let { return it }
        if (all.isNotEmpty()) {
            val pick = all.maxByOrNull { it.updatedAt }!!
            Session.aiCurrentConv = pick.id
            return pick
        }
        return create()
    }

    /**
     * 新建一条会话并切过去。
     *
     * @param mode ★ **会话模式** (2026-10-05) —— 见 [com.canglan.cyancodex.data.AiMode]。
     *             不传 = [AiMode.DEFAULT]（指令编写）。
     */
    fun create(title: String = DEFAULT_TITLE, mode: String = AiMode.DEFAULT): AiConv {
        val now = System.currentTimeMillis()
        val conv = AiConv(
            id = newId(), title = title, createdAt = now, updatedAt = now,
            mode = AiMode.of(mode),
        )
        write(load() + conv)
        Session.aiCurrentConv = conv.id
        return conv
    }

    /** 改某条会话的模式 (新建面板之外也可能从会话设置里改) */
    fun setMode(id: String, mode: String) {
        write(load().map { if (it.id == id) it.copy(mode = AiMode.of(mode)) else it })
    }

    /** 取某条会话的模式 (找不到 = [AiMode.DEFAULT]) */
    fun modeOf(id: String): String = AiMode.of(load().firstOrNull { it.id == id }?.mode)

    fun setCurrent(id: String) {
        Session.aiCurrentConv = id
    }

    fun rename(id: String, title: String) {
        val t = title.trim().ifBlank { DEFAULT_TITLE }
        write(load().map { if (it.id == id) it.copy(title = t) else it })
    }

    /** 删除会话; 返回删除后应切换到的会话 id (空串 = 一条都不剩, 由调用方决定是否新建) */
    fun delete(id: String): String {
        val rest = load().filterNot { it.id == id }
        write(rest)
        if (Session.aiCurrentConv != id) return Session.aiCurrentConv
        val next = rest.maxByOrNull { it.updatedAt }?.id.orEmpty()
        Session.aiCurrentConv = next
        return next
    }

    /** 覆盖某条会话的消息 (同时刷新 updatedAt, 并在标题还是占位时自动起名) */
    fun setMessages(id: String, messages: List<UiMsg>) {
        val now = System.currentTimeMillis()
        write(load().map { c ->
            if (c.id != id) c
            else c.copy(
                messages = messages,
                updatedAt = now,
                title = if (c.title == DEFAULT_TITLE || c.title.isBlank()) autoTitle(messages) else c.title,
            )
        })
    }
}
