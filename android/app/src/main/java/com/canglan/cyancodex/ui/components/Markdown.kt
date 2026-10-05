package com.canglan.cyancodex.ui.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.canglan.cyancodex.ai.CommandCheck

/**
 * 轻量 **Markdown → AnnotatedString** 渲染。
 *
 * ## 为什么要手写
 *
 * AI 那一整块的原则是**零新依赖**（见外发包 README："没有引入任何新的第三方依赖"）。
 * 而 AI 回复里真正会出现的语法就那几种 —— 为了它们引一个全量 Markdown 解析器是杀鸡用牛刀，
 * 还会把 `multiplatform-markdown-renderer` 这类库连同它的一串传递依赖带进来。
 *
 * ## 支持
 *
 * | 语法 | 渲染 |
 * |---|---|
 * | `**粗体**` / `__粗体__` | 加粗 |
 * | `*斜体*` / `_斜体_` | 斜体 |
 * | `` `行内代码` `` | 等宽 + 淡背景 |
 * | ` ```代码块``` ` | 等宽 + 淡背景（逐行） |
 * | `#` / `##` / `###` 标题 | 加粗 + 放大 |
 * | `- ` / `* ` / `+ ` 列表 | 换成 `• ` |
 * | `> ` 引用 | 灰色斜体 + 竖线 |
 *
 * **不做**：链接（要可点击得额外处理，AI 回答里现在也是纯文本，用户看得懂）、图片、表格、嵌套列表。
 * 不认识的语法**原样显示** —— 宁可难看，也不能把内容吃掉。
 *
 * ⚠️ 触发场景很实在：模型经常输出 `**没有找到 Java 版专属的更新预告**`，
 * 改之前用户看到的就是字面的星号（用户截图反馈过）。
 */
@Composable
fun MarkdownText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyMedium,
    color: Color = Color.Unspecified,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
) {
    // 代码块/行内代码的底色与前景 —— 从主题取, 深浅色模式都能看
    val codeBg = MaterialTheme.colorScheme.surfaceVariant
    val codeFg = MaterialTheme.colorScheme.onSurfaceVariant
    val quoteFg = MaterialTheme.colorScheme.onSurfaceVariant

    // ★ remember: 解析是纯字符串处理, 但消息列表每帧都会重组 —— 不缓存会白白重跑
    val annotated = remember(text, codeBg, codeFg, quoteFg, style.fontSize) {
        renderMarkdown(text, codeBg, codeFg, quoteFg, style)
    }

    Text(
        text = annotated,
        modifier = modifier,
        style = style,
        color = color,
        maxLines = maxLines,
        overflow = overflow,
    )
}

/** 行内样式：粗体 / 斜体 / 行内代码。★ 粗体的分支必须排在斜体前面，否则 `**a**` 会被 `*` 先吃掉 */
private val INLINE = Regex("""\*\*(.+?)\*\*|__(.+?)__|`([^`]+)`|\*(.+?)\*|_(.+?)_""")

/** 行首列表符号（`- ` / `* ` / `+ `）。`*` 单独留着会和斜体正则打架，统一换成 `•` */
private val BULLET = Regex("""^(\s*)[-*+]\s+(.*)$""")

/** 标题：`#` 到 `######` */
private val HEADING = Regex("""^(#{1,6})\s+(.*)$""")

/** 引用：`> ` */
private val QUOTE = Regex("""^(\s*)>\s?(.*)$""")

/**
 * 把 Markdown 文本转成 [AnnotatedString]。
 *
 * 逐行处理：先判代码块（``` 之间的内容**不做任何行内解析** —— 代码里的 `*` 不是斜体），
 * 再判标题 / 列表 / 引用，剩下的走行内解析。
 *
 * ⚠️ 行与行之间用 `if (i > 0) append('\n')` 拼 —— **不要**每行都 append 换行再回头删最后一个。
 *    `AnnotatedString.Builder` 没有稳定的 `get(index)`/`delete` 语义可用，那种写法会踩 API 差异。
 */
fun renderMarkdown(
    src: String,
    codeBg: Color,
    codeFg: Color,
    quoteFg: Color,
    style: TextStyle,
): AnnotatedString = buildAnnotatedString {
    if (src.isEmpty()) return@buildAnnotatedString

    // ⚠️ 必须兜底: TextUnit.Unspecified 参与乘法会直接抛
    //    ("Cannot perform operation for Unspecified type"), 而调用方传自定义 TextStyle 时它常常是空的。
    //    这里用 isSp 判而不是 takeOrElse —— 后者是 `androidx.compose.ui.unit` 的顶层扩展, 要多一个 import。
    val baseSize = if (style.fontSize.isSp) style.fontSize else 14.sp

    var inCode = false

    src.split('\n').forEachIndexed { i, line ->
        if (i > 0) append('\n')

        // ---- 代码块围栏 ----
        if (line.trimStart().startsWith("```")) {
            inCode = !inCode
            return@forEachIndexed
        }
        if (inCode) {
            // 代码块里只加样式, 不做任何语法解析
            withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = codeBg, color = codeFg)) {
                append(line)
            }
            return@forEachIndexed
        }

        // ---- 标题 ----
        val h = HEADING.find(line)
        if (h != null) {
            val scale = when (h.groupValues[1].length) {
                1 -> 1.35f
                2 -> 1.22f
                3 -> 1.12f
                else -> 1.05f
            }
            withStyle(SpanStyle(fontWeight = FontWeight.Bold, fontSize = baseSize * scale)) {
                appendInline(h.groupValues[2], codeBg, codeFg)
            }
            return@forEachIndexed
        }

        // ---- 引用 ----
        val q = QUOTE.find(line)
        if (q != null) {
            append(q.groupValues[1])
            withStyle(SpanStyle(color = quoteFg, fontStyle = FontStyle.Italic)) {
                append("│ ")
                appendInline(q.groupValues[2], codeBg, codeFg)
            }
            return@forEachIndexed
        }

        // ---- 无序列表 ----
        val b = BULLET.find(line)
        if (b != null) {
            // ⚠️ 不要写成 `append(a).append(b)` 链式 —— AnnotatedString.Builder 同时实现了 Appendable,
            //    Kotlin 会解析到返回 Appendable 的那个重载, 后面的 `.append` 就 "receiver type mismatch"。
            append(b.groupValues[1])
            append("• ")
            appendInline(b.groupValues[2], codeBg, codeFg)
            return@forEachIndexed
        }

        // ---- 普通行 ----
        appendInline(line, codeBg, codeFg)
    }
}

/** 行内解析：把 [s] 按 [INLINE] 拆开, 逐段 append 并套上对应样式 */
private fun AnnotatedString.Builder.appendInline(
    s: String,
    codeBg: Color,
    codeFg: Color,
) {
    var last = 0
    for (m in INLINE.findAll(s)) {
        if (m.range.first > last) append(s.substring(last, m.range.first))
        val g = m.groupValues
        when {
            g[1].isNotEmpty() -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(g[1]) }
            g[2].isNotEmpty() -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(g[2]) }
            g[3].isNotEmpty() -> withStyle(
                SpanStyle(fontFamily = FontFamily.Monospace, background = codeBg, color = codeFg)
            ) { append(g[3]) }
            g[4].isNotEmpty() -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(g[4]) }
            g[5].isNotEmpty() -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(g[5]) }
        }
        last = m.range.last + 1
    }
    if (last < s.length) append(s.substring(last))
}

// =====================================================================================
//  代码块 → 独立卡片 (2026-10-01)
// =====================================================================================

/**
 * 一段内容: 普通 Markdown 文本, 或一个 ``` 围栏代码块。
 *
 * 抽成 sealed interface 是为了让 UI 层能**按块布局** —— 普通文本走 [MarkdownText],
 * 代码块走带标题栏和按钮的独立卡片。见 [MarkdownBlocks]。
 */
sealed interface MdBlock {
    data class Prose(val text: String) : MdBlock
    data class Code(val info: String, val body: String) : MdBlock
}

/**
 * 按 ``` 围栏把文本切成块。
 *
 * ⚠️ **未闭合的围栏按代码块收尾**, 不能丢 —— 模型流式输出被打断、或者它忘了收尾时,
 *    后面那些指令如果被当成普通段落渲染, 用户就复制不到了。
 *
 * ⚠️ 语言标记取整行(`info`), 不在这里拆 —— 拆法(空格分隔, 第二个词是版本号)属于
 *    「指令格式约定」的一部分, 放在 [CodeBlockCard] 里, 与 `AiClient.FORMAT_PROMPT` 对着看。
 */
fun splitCodeBlocks(src: String): List<MdBlock> {
    val out = ArrayList<MdBlock>()
    val prose = StringBuilder()
    val code = StringBuilder()
    var info: String? = null

    fun flushProse() {
        val t = prose.toString().trim('\n')
        if (t.isNotBlank()) out.add(MdBlock.Prose(t))
        prose.setLength(0)
    }

    fun flushCode() {
        val t = code.toString().trim('\n')
        if (t.isNotBlank()) out.add(MdBlock.Code(info.orEmpty(), t))
        code.setLength(0)
        info = null
    }

    src.split('\n').forEach { line ->
        if (line.trimStart().startsWith("```")) {
            if (info == null) {
                flushProse()
                info = line.trimStart().removePrefix("```").trim()
            } else {
                flushCode()
            }
            return@forEach
        }
        if (info == null) prose.append(line).append('\n') else code.append(line).append('\n')
    }
    if (info != null) flushCode() else flushProse()
    return out
}

/**
 * Markdown 渲染的**分块版** —— 正文用它, 代码块变成独立卡片。
 *
 * 与 [MarkdownText] 的关系: 没有代码块时两者渲染结果**完全一致**(只有一个 Prose 块),
 * 所以正文可以无脑换过来, 不必先判断"这段有没有代码块"。
 *
 * @param onUpload 点「上传到指令库」的回调: (指令正文, 标题, 适用版本)。
 *                 为 null 时**不显示上传按钮** —— 折叠的思考过程里不需要这个入口。
 * @param selectable ★ **允许长按选中文本** (2026-10-05)。true 时:
 *                - 正文段落套 [SelectionContainer] —— 可拖选任意片段;
 *                - 代码块**关掉横向滚动**并允许换行 —— 这是必须的取舍, 见下。
 *
 *                ⚠️ **为什么代码块要放弃横向滚动**: `SelectionContainer` 靠**长按拖拽**起选,
 *                   而 `horizontalScroll` 也吃横向拖拽手势 —— 两个抢同一个手势的结果是
 *                   "想选字却在滚代码"。用户要的是**能选中代码块内容**("指令代码块也支持选择"),
 *                   所以选择模式下必须让出滚动, 改成 `softWrap=true` 自动换行。
 *                   代价: 一条长指令会折成几行 —— 但既然是在"选文本", 折行不影响选,
 *                   反而比横向滚着选更好操作。
 *                ⚠️ 默认 **false**: 非选择模式下保留横向滚动(正常阅读时更舒服),
 *                   只在用户从长按菜单选了「选择文本」时才切进来。
 */
@Composable
fun MarkdownBlocks(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyMedium,
    onUpload: ((cmd: String, title: String, mc: String) -> Unit)? = null,
    selectable: Boolean = false,
) {
    val blocks = remember(text) { splitCodeBlocks(text) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        blocks.forEach { b ->
            when (b) {
                is MdBlock.Prose -> MarkdownText(b.text, style = style)
                is MdBlock.Code -> CodeBlockCard(b, style, onUpload, selectable)
            }
        }
    }
}

/**
 * 代码块卡片: 标题栏(标签 + 版本 + 复制 + 上传) + 等宽正文。
 *
 * ## 「这是不是一条指令」怎么判
 *
 * 两个判据取**或**: ① 围栏语言标记以 `mc` 开头; ② 正文里过半数非空行以 `/` 开头
 * (见 [CommandCheck.looksLikeCommands])。
 * 取或是刻意的 —— 模型不一定每次都乖乖写 `mc`(它可能写 ``` 或者 ```mcfunction),
 * 但只要内容确实是指令, 用户就该看到「上传」按钮。
 *
 * @param selectable 见 [MarkdownBlocks] —— 选择模式下关横向滚动、改自动换行。
 */
@Composable
private fun CodeBlockCard(
    block: MdBlock.Code,
    style: TextStyle,
    onUpload: ((cmd: String, title: String, mc: String) -> Unit)?,
    selectable: Boolean = false,
) {
    val ctx = LocalContext.current
    // 语言标记按空格拆: 第一个词是语言, 第二个词是适用版本 —— 与 AiClient.FORMAT_PROMPT 的约定一致
    val tokens = block.info.split(' ', '\t').filter { it.isNotBlank() }
    val lang = tokens.firstOrNull().orEmpty().lowercase()
    val ver = tokens.getOrNull(1).orEmpty()

    val isCmd = lang.startsWith("mc") || CommandCheck.looksLikeCommands(block.body)

    // `# 标题` 是 App 自己的指令 TXT 导入约定 (见 Common.kt 的 parseCommandTxt), 这里沿用同一套:
    // 第一行 `# xxx` 当标题, 其余以 / 开头的行才是指令本体。
    val title = remember(block.body) {
        block.body.lineSequence().map { it.trim() }
            .firstOrNull { it.startsWith("#") }
            ?.removePrefix("#")?.trim().orEmpty()
    }
    val cmdBody = remember(block.body) {
        block.body.split('\n').map { it.trim() }
            .filter { it.startsWith("/") }
            .joinToString("\n")
            .ifBlank { block.body.trim() }
    }

    val label = when {
        isCmd -> if (ver.isNotBlank()) "指令 · $ver" else "指令"
        lang.isNotBlank() -> lang
        else -> "代码"
    }

    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
            Row(
                Modifier.fillMaxWidth().padding(start = 10.dp, end = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    if (isCmd) Icons.Default.Terminal else Icons.Default.Code, null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(13.dp),
                )
                Spacer(Modifier.width(5.dp))
                Text(
                    label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                // 「上传到指令库」—— 跳编辑器并把指令/版本/标题预填好。
                // 只在确认是指令、且调用方给了回调时出现 (思考过程里就不给)。
                if (isCmd && onUpload != null) {
                    IconButton(
                        onClick = { onUpload(cmdBody, title, ver) },
                        modifier = Modifier.size(32.dp),
                    ) {
                        Icon(
                            Icons.Default.FileUpload, "上传到指令库",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
                IconButton(
                    onClick = { copyToClipboard(ctx, if (isCmd) cmdBody else block.body, "已复制") },
                    modifier = Modifier.size(32.dp),
                ) {
                    Icon(
                        Icons.Default.ContentCopy, "复制",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(15.dp),
                    )
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            // ★ 选择模式 vs 阅读模式的取舍 (2026-10-05):
            //   阅读模式 → 横向滚动 + softWrap=false。长指令**不折行**, 用户复制时不会多带/少带字符。
            //   选择模式 → 关掉横向滚动 + softWrap=true。理由见 [MarkdownBlocks] 的 ⚠️:
            //              横向滚动会和 SelectionContainer 的长按拖拽抢手势。
            //   ⚠️ SelectionContainer 必须**包在** Text 外层(而不是反过来), 否则起选区域只有半个字高。
            if (selectable) {
                Box(Modifier.fillMaxWidth().padding(10.dp)) {
                    SelectionContainer {
                        Text(
                            block.body.trim(),
                            style = style.copy(fontFamily = FontFamily.Monospace, fontSize = 12.sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else {
                // 长指令必须能横向滚动 —— 换行会把一条完整指令拆成好几行, 用户复制时容易多带/少带字符。
                Box(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(10.dp)) {
                    Text(
                        block.body.trim(),
                        style = style.copy(fontFamily = FontFamily.Monospace, fontSize = 12.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        softWrap = false,
                    )
                }
            }
            if (title.isNotBlank()) {
                Text(
                    "# $title",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                    modifier = Modifier.padding(start = 10.dp, end = 10.dp, bottom = 8.dp),
                )
            }
            Spacer(Modifier.height(2.dp))
        }
    }
}
