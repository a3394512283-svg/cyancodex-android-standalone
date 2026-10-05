package com.canglan.cyancodex.ai

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import com.canglan.cyancodex.data.Attachment
import java.io.InputStream

/**
 * ★★ **AI 上传文件的读取与裁剪** (2026-10-05)。
 *
 * 用户原话:「对了，顺便给AI加一个上传文件功能」。
 *
 * ## 设计要点
 *
 * 1. **只吃文本文件**。模型这条通道是纯文本 —— 图片/压缩包/base64 塞进去既烧 token
 *    又读不懂, 所以这里**按扩展名 + MIME 双判**, 不认识的当场返回可读的错误,
 *    让 UI 弹给用户看, 而不是发出去等模型说"我看不到附件"。
 * 2. **硬上限 [MAX_BYTES]**。一次读几十 MB 进去会 OOM, 也会把 API 的上下文顶爆。
 *    超了**截断并在气泡上标出来** —— 假装完整是最坏的做法。
 * 3. **按字节读, 不按字符**。UTF-8 下一个汉字 3 字节, 用字符数当上限会让中文文件
 *    实际读进来三倍于预期。这里读满 [MAX_BYTES] 字节再解码。
 * 4. **解码失败不报错, 兜成替换字符**。MC 的 .mcfunction 大多是 UTF-8, 但用户可能
 *    从 Windows 拷来 GBK 文件 —— 严格解码会整个失败, 宽松解码至少能看个大概。
 *
 * ⛔ 本文件**不做**网络、不碰 Session —— 纯函数, 方便日后单测与复用。
 */
object AiAttach {

    /** 单个文件最多读多少字节。60 KB ≈ 2 万汉字, 对"贴一段配置/日志/函数"够用, 又不至于顶爆上下文。 */
    const val MAX_BYTES = 60 * 1024

    /** 一次最多挂几个文件。再多就问用户合并一下, 别把 prompt 撑爆。 */
    const val MAX_FILES = 3

    /**
     * 允许的扩展名（小写, 不带点）。
     *
     * ★ 白名单而非黑名单: 黑名单永远漏(总能想出新的二进制格式), 白名单漏了也只是
     *   "一个文本文件传不了", 用户会自己改扩展名 —— 风险方向完全不同。
     */
    private val ALLOWED_EXT = setOf(
        "txt", "md", "markdown", "json", "json5", "yml", "yaml", "toml", "ini", "cfg", "conf", "properties",
        "mcfunction", "log", "csv", "tsv", "xml", "html", "htm", "kt", "java", "js", "ts", "py", "sh", "bat", "ps1",
        "sql", "gradle", "kts", "snbt", "nbt.txt", "lang", "loot_table", "recipe",
    )

    /** 读取结果 —— 成功给 [Attachment], 失败给 [error] 文案（UI 直接弹）。 */
    data class Result(val ok: Attachment?, val error: String?)

    /**
     * 从 SAF Uri 读取一个文件为 [Attachment]。
     *
     * ⛔ 全程 runCatching —— SAF 的 Uri 权限可能已失效(用户重启后 / 文件被删),
     *   任何一步都可能抛。宁可返回一句"读取失败", 也不能让整个发送流程崩掉。
     */
    fun read(resolver: ContentResolver, uri: Uri): Result = runCatching {
        val name = displayName(resolver, uri)
        val size = fileSize(resolver, uri)
        val ext = name.substringAfterLast('.', "").lowercase()

        // ① 先按扩展名白名单拦一道(最稳, 不依赖 provider 给的 MIME 是否靠谱)
        if (ext.isNotBlank() && ext !in ALLOWED_EXT) {
            return Result(null, "「$name」不是文本文件（仅支持 .txt/.mcfunction/.json/.md/.log/.csv 等）")
        }
        // ② MIME 再兜一道: 有些文件没扩展名, 只能靠 MIME。非 text/* 且不是白名单 → 拒。
        val mime = runCatching { resolver.getType(uri) }.getOrNull().orEmpty()
        if (ext.isBlank() && mime.isNotBlank() && !mime.startsWith("text/") &&
            mime != "application/json" && !mime.contains("xml")
        ) {
            return Result(null, "「$name」类型为 $mime，仅支持文本文件")
        }

        // ③ 读字节, 封顶 MAX_BYTES。多读 1 字节用来判断"是不是真被截了"。
        val (bytes, truncated) = resolver.openInputStream(uri)?.use { readCapped(it, MAX_BYTES) }
            ?: return Result(null, "无法打开「$name」（权限可能已失效，请重新选择）")

        // ④ 宽松解码: 先按 UTF-8; 出现替换字符说明大概不是 UTF-8, 退回 GBK 再试一次。
        var text = String(bytes, Charsets.UTF_8)
        if (text.contains('\uFFFD')) {
            val gbk = runCatching { String(bytes, charset("GBK")) }.getOrNull()
            // 取"替换字符更少"的那个 —— 谁解得干净用谁, 不猜编码来源。
            if (gbk != null && gbk.count { it == '\uFFFD' } < text.count { it == '\uFFFD' }) text = gbk
        }

        if (text.isBlank()) return Result(null, "「$name」是空的")

        Result(Attachment(name = name, size = size, content = text, truncated = truncated), null)
    }.getOrElse { e ->
        Result(null, "读取文件失败：${e.message ?: e.javaClass.simpleName}")
    }

    /**
     * 读满 [cap] 字节为止；再多读 1 字节探测"后面还有没有"。
     * @return Pair(实际字节, 是否被截断)
     */
    private fun readCapped(input: InputStream, cap: Int): Pair<ByteArray, Boolean> {
        val buf = ByteArray(cap + 1)
        var off = 0
        while (off < buf.size) {
            val n = input.read(buf, off, buf.size - off)
            if (n <= 0) break
            off += n
        }
        val truncated = off > cap
        return Pair(if (truncated) buf.copyOf(cap) else buf.copyOf(off), truncated)
    }

    /** 问 provider 要显示名; 拿不到就从 Uri 路径里猜一个。 */
    private fun displayName(resolver: ContentResolver, uri: Uri): String {
        runCatching {
            resolver.query(uri, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && c.moveToFirst()) {
                    val v = c.getString(idx)
                    if (!v.isNullOrBlank()) return v
                }
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: "文件"
    }

    /** 文件大小（字节）; 拿不到返回 0（UI 会显示成 0 B, 不影响功能）。 */
    private fun fileSize(resolver: ContentResolver, uri: Uri): Long {
        runCatching {
            resolver.query(uri, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(OpenableColumns.SIZE)
                if (idx >= 0 && c.moveToFirst() && !c.isNull(idx)) return c.getLong(idx)
            }
        }
        return 0L
    }

    /** 人类可读大小 —— 给 UI 气泡上的小字用。 */
    fun humanSize(bytes: Long): String = when {
        bytes <= 0L -> ""
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
        else -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
    }

    /**
     * 把附件拼进**用户消息正文**。
     *
     * ★ 为什么拼进 content 而不是另开字段送模型: 两条 AI 通道(自备 API 直连 / 官方代理)
     *   都只认 `ChatMsg(role, content)`(见 AiClient 的 msgs 组装与 AiProxyAgent 的
     *   `ChatMsg(it.role, it.content)`)。拼进 content 是**一处改动两条通道同时生效**,
     *   而且历史里自然带着文件内容, 后续轮次模型仍看得见。
     *
     * ⛔ 拼出来的这段会**落盘进会话 JSON** —— 所以这里用"围栏"格式, 让模型明确知道
     *   哪段是文件、哪段是用户的话, 免得模型把代码文件当成用户下的指令。
     */
    fun composePrompt(userText: String, atts: List<Attachment>): String {
        if (atts.isEmpty()) return userText
        val sb = StringBuilder()
        atts.forEach { a ->
            sb.append("【附件：").append(a.name)
            if (a.size > 0) sb.append("，").append(humanSize(a.size))
            sb.append(if (a.truncated) "，已截断" else "").append("】\n")
            sb.append("```\n")
            sb.append(a.content.trimEnd())
            sb.append("\n```\n\n")
        }
        if (userText.isNotBlank()) sb.append(userText)
        else sb.append("（请根据上面的附件内容回答 / 分析）")
        return sb.toString()
    }

    /**
     * ★★ **从落盘的正文里剥出"用户自己写的那句话"** —— 只给**气泡显示**用 (2026-10-05)。
     *
     * 为什么需要: [composePrompt] 把附件正文拼进了 content(那是**发给模型**的东西),
     * 但气泡上要是把整个文件内容原样渲染出来, 用户发个 20KB 的日志, 屏幕就被淹了 ——
     * 而且文件内容会走 Markdown 渲染, 里面若含 ``` 或 # 整个气泡排版都会乱。
     *
     * 所以显示时**只留最后那段用户原话**; 附件本身用上面那排「📎 文件名」小胶囊表达。
     *
     * ⛔ 只在**有附件**时调用 —— 没附件时 content 就是原文, 直接显示即可(这里是恒等变换)。
     */
    fun displayText(content: String): String {
        if (!content.startsWith("【附件：")) return content
        // 附件段与用户段的分界: 最后一个附件代码块结束的 ``` 之后。
        // 附件段格式固定为 `【附件：…】\n```\n…\n```\n\n`, 所以找最后那个 "```\n\n" 即可。
        val marker = "```\n\n"
        val cut = content.lastIndexOf(marker)
        if (cut < 0) return content
        val tail = content.substring(cut + marker.length)
        return tail.ifBlank { "" }        // 纯附件无文字时返回空 —— 气泡只显示胶囊, 不显示引导语
    }
}
