package com.canglan.cyancodex.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.withStyle

/**
 * 基岩版指令语法高亮 (配色参考 BNM)
 * 规则: 指令名/选择器/数字坐标/字符串/NBT花括号
 */
object BedrockHighlight {

    // 深色(命令卡片深色底)
    private val CmdDark = Color(0xFF82B1FF)   // 指令名 - 蓝
    private val SelDark = Color(0xFFCE93D8)   // 选择器 - 紫
    private val NumDark = Color(0xFFFFB74D)   // 数字/坐标 - 橙
    private val StrDark = Color(0xFF81C784)   // 字符串 - 绿
    private val BrcDark = Color(0xFFF48FB1)   // NBT/花括号 - 粉
    private val TexDark = Color(0xFFE7F2EA)

    // 浅色
    private val CmdLight = Color(0xFF1565C0)
    private val SelLight = Color(0xFF8E24AA)
    private val NumLight = Color(0xFFE65100)
    private val StrLight = Color(0xFF2E7D32)
    private val BrcLight = Color(0xFFC2185B)

    private val tokenRe = Regex(
        """("(?:\\.|[^"\\])*")|(@[a-zA-Z]+(?:\[[^\]]*\])?)|(/(?:[A-Za-z_][A-Za-z0-9_]*))|([~^]?-?\d+(?:\.\d+)?)|([{}\[\]()])|(\^)"""
    )

    fun highlight(text: String, dark: Boolean = true): AnnotatedString {
        val cmd = if (dark) CmdDark else CmdLight
        val sel = if (dark) SelDark else SelLight
        val num = if (dark) NumDark else NumLight
        val str = if (dark) StrDark else StrLight
        val brc = if (dark) BrcDark else BrcLight

        val builder = AnnotatedString.Builder()
        var last = 0
        for (m in tokenRe.findAll(text)) {
            if (m.range.first > last) {
                builder.append(text.substring(last, m.range.first))
            }
            val color = when {
                m.groups[1] != null -> str
                m.groups[2] != null -> sel
                m.groups[3] != null -> cmd
                m.groups[4] != null -> num
                else -> brc
            }
            builder.withStyle(SpanStyle(color = color)) { append(m.value) }
            last = m.range.last + 1
        }
        if (last < text.length) builder.append(text.substring(last))
        return builder.toAnnotatedString()
    }

    /** 根据主题色判断当前是否深色模式 */
    fun isDarkSurface(surface: Color): Boolean = surface.luminance() < 0.5f
}
