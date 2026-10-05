package com.canglan.cyancodex.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.canglan.cyancodex.ui.LocalGlassBottomInset
import com.canglan.cyancodex.ui.liquidglass.LiquidButton
import com.kyant.backdrop.backdrops.LayerBackdrop

/*
 * 聊天类页面(聊天室 / 好友私聊)共用的底部输入条。
 *
 * 为什么抽出来: 这两个页面的输入条结构一模一样(OutlinedTextField + Send IconButton,
 * 挂在 Scaffold.bottomBar 的 Surface 里)。2026-09-25 给它们加玻璃时, 如果各写一份,
 * 以后改样式必然漏一处 —— 这里拆成"内容"和"玻璃外壳"两层, 两个页面都调同一份。
 *
 * ⚠️ 玻璃外壳不是 Scaffold 的 bottomBar, 而是浮层 —— 原因见 GlassSurface 的注释:
 *    挂 bottomBar 里时 Scaffold 会把 body 顶上去, 玻璃正下方只剩 Scaffold 纯色底,
 *    玻璃会退化成一块半透明矩形(和当初底部导航栏踩的是同一个坑)。
 *
 * ⚠️ 采样源必须**排除本组件自己**: layerBackdrop 录的是节点全部绘制内容, 输入条若在
 *    被采样的节点内部, 就会采到上一帧的自己 → 静止收敛成怪像 + 滚动拖影。
 *    调用姿势: 内容列挂 layerBackdrop, 输入条是它的**兄弟节点**(见 ChatScreen)。
 */

/**
 * 输入行的**内部内容**(输入框 + 发送键)。玻璃 / 非玻璃两种外观共用这一份。
 *
 * - `glass = false`: 与上游逐字一致(8dp/6dp 内边距 + 常规 OutlinedTextField + IconButton),
 *   用于 Scaffold.bottomBar —— 保证**关掉液态玻璃时外观逐像素不变**。
 * - `glass = true`: 输入框自身边框与底色全透明(交给外面的玻璃胶囊),
 *   发送键换成官方 LiquidButton 正圆, 与 AI 页、指令库 FAB 保持同一套视觉。
 */
@Composable
fun ChatInputRow(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    sendEnabled: Boolean,
    onSend: () -> Unit,
    glass: Boolean,
    backdrop: LayerBackdrop,
    modifier: Modifier = Modifier,
    maxLines: Int = 3,
) {
    if (glass) {
        Row(
            modifier
                .fillMaxWidth()
                .padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = value, onValueChange = onValueChange,
                placeholder = { Text(placeholder) },
                modifier = Modifier.weight(1f),
                maxLines = maxLines,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Color.Transparent,
                    unfocusedBorderColor = Color.Transparent,
                    disabledBorderColor = Color.Transparent,
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    disabledContainerColor = Color.Transparent,
                ),
            )
            // 官方 LiquidButton (逐字搬运见 ui/liquidglass/, Apache-2.0)。
            // 等宽高 + 正圆; 官方没有 enabled 参数, 禁用态靠 isInteractive=false + 自己拦 onClick。
            LiquidButton(
                onClick = { if (sendEnabled) onSend() },
                backdrop = backdrop,
                modifier = Modifier.size(44.dp),
                isInteractive = sendEnabled,
                height = 44.dp,
                contentPadding = 0.dp,
                tint = if (sendEnabled) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.surfaceVariant,
            ) {
                Icon(
                    Icons.Default.Send, "发送",
                    tint = if (sendEnabled) Color.White
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    } else {
        Row(
            modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = value, onValueChange = onValueChange,
                placeholder = { Text(placeholder) },
                modifier = Modifier.weight(1f),
                maxLines = maxLines,
            )
            IconButton(enabled = sendEnabled, onClick = onSend) { Icon(Icons.Default.Send, "发送") }
        }
    }
}

/**
 * 输入条的**玻璃外壳** —— 浮在内容之上, 底部让开底部导航栏。
 *
 * 只在 `glass = true` 时渲染; 非玻璃时调用方仍走 Scaffold.bottomBar(原样)。
 *
 * @param onHeightChanged 把实测高度回填给列表末尾的 contentPadding,
 *   否则最后一条消息会被输入条盖住。**不要硬编码高度** —— 输入框 maxLines 撑高后
 *   硬编码值会静默过期(实验台就踩过这个: 底栏改高后列表末尾多出一截空白)。
 */
@Composable
fun GlassChatInputBar(
    glass: Boolean,
    backdrop: LayerBackdrop,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    sendEnabled: Boolean,
    onSend: () -> Unit,
    onHeightChanged: (Dp) -> Unit,
    modifier: Modifier = Modifier,
    maxLines: Int = 3,
) {
    if (!glass) return
    val density = LocalDensity.current
    GlassSurface(
        backdrop = backdrop,
        shape = RoundedCornerShape(28.dp),
        modifier = modifier
            .fillMaxWidth()
            .onSizeChanged { onHeightChanged(with(density) { it.height.toDp() }) }
            .padding(horizontal = 8.dp)
            .padding(bottom = 8.dp + LocalGlassBottomInset.current),
    ) {
        ChatInputRow(
            value = value, onValueChange = onValueChange,
            placeholder = placeholder,
            sendEnabled = sendEnabled, onSend = onSend,
            glass = true, backdrop = backdrop, maxLines = maxLines,
        )
    }
}
