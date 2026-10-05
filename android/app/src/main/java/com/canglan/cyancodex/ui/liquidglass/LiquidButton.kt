package com.canglan.cyancodex.ui.liquidglass

/*
 * 搬运自官方示例工程 (改动见下):
 *   https://github.com/Kyant0/AndroidLiquidGlass
 *   app/src/commonMain/kotlin/com/kyant/backdrop/catalog/components/LiquidButton.kt
 * 许可: Apache License 2.0
 *
 * 胶囊玻璃按钮, 默认 48dp 高。特点:
 *   - lens(12dp, 24dp): 折射比底栏(24,24)更"扁", 所以按钮边缘的畸变更锐利
 *   - layerBlock 里用 tanh 做位移阻尼: 手指拖动时按钮会跟手漂移, 松手弹回
 *   - tint 用 BlendMode.Hue 着色(保亮度只改色相), 比直接盖一层颜色更"玻璃"
 *
 * 改动 (仅 1 处):
 *   官方把高度和左右内边距**写死**在 Modifier 链尾部 (.height(48dp).padding(horizontal=16dp)),
 *   于是传 Modifier.size(44.dp) 也做不出正圆 —— 后面那句 .height() 会覆盖掉 size 的高度。
 *   这里把它们提成参数 height / contentPadding, **默认值 48dp / 16dp 与官方完全一致**,
 *   不传参时行为逐字节等同官方。本 App 的圆形发送键/悬浮按钮靠传 size + 这两个参数实现。
 */

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastCoerceAtMost
import androidx.compose.ui.util.lerp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.shapes.Capsule
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tanh

@Composable
fun LiquidButton(
    onClick: () -> Unit,
    backdrop: Backdrop,
    modifier: Modifier = Modifier,
    isInteractive: Boolean = true,
    tint: Color = Color.Unspecified,
    surfaceColor: Color = Color.Unspecified,
    height: Dp = 48f.dp,
    contentPadding: Dp = 16f.dp,
    flatSource: Boolean = false,
    content: @Composable RowScope.() -> Unit
) {
    val animationScope = rememberCoroutineScope()

    val interactiveHighlight = remember(animationScope) {
        InteractiveHighlight(
            animationScope = animationScope
        )
    }

    Row(
        modifier
            .drawBackdrop(
                backdrop = backdrop,
                shape = { Capsule() },
                effects = {
                    vibrancy()
                    // ★★ 本工程新增(默认 false = 与官方逐字节一致)。
                    //
                    // 采样源如果是**平底色**(调用方用 `rememberCanvasBackdrop { drawRect(纯色) }`
                    // 铺一块单色), 那么 blur 与 lens 在数学上都是**恒等变换**:
                    //   · 常量模糊之后还是同一个常量;
                    //   · 折射只是按 UV 位移去采样, 常量采样到哪儿都是它自己。
                    // 但这两个效果**照样会真跑一遍着色器** —— blur 是 RenderEffect,
                    // lens 是 AGSL RuntimeShader。在软件渲染(SwiftShader)下 AGSL 极慢。
                    //
                    // 实测(1080x2400 @420dpi 的 AVD, SwiftShader 软件渲染):
                    //   玻璃开启后首页 4 秒只渲染出 **2 帧**(约 0.5 FPS),
                    //   主线程 ANR 栈停在 android.graphics.HardwareRenderer.syncAndDrawFrame
                    //   —— 就是在等渲染线程画完一帧。关掉玻璃后同一手势是 63 帧。
                    //   作为对照, 系统「设置」App 与 Pixel 桌面在这台模拟器上也只有 6~10 FPS,
                    //   所以模拟器本身很慢; 但玻璃确实把成本又放大了一个量级。
                    //
                    // 关掉这两项后外观**逐像素不变**, 却省掉每个玻璃元素 1~2 次着色器 pass。
                    // 全 App 有 150+ 个玻璃按钮/胶囊, 这一项是实打实的。
                    // (采样源是真实页面内容时 —— 底栏、输入条、FAB —— 保持官方原样, 绝不能关)
                    if (!flatSource) {
                        blur(2f.dp.toPx())
                        lens(12f.dp.toPx(), 24f.dp.toPx())
                    }
                },
                layerBlock = if (isInteractive) {
                    {
                        val width = size.width
                        val height = size.height

                        val progress = interactiveHighlight.pressProgress
                        val scale = lerp(1f, 1f + 4f.dp.toPx() / size.height, progress)

                        val maxOffset = size.minDimension
                        val initialDerivative = 0.05f
                        val offset = interactiveHighlight.offset
                        translationX = maxOffset * tanh(initialDerivative * offset.x / maxOffset)
                        translationY = maxOffset * tanh(initialDerivative * offset.y / maxOffset)

                        val maxDragScale = 4f.dp.toPx() / size.height
                        val offsetAngle = atan2(offset.y, offset.x)
                        scaleX =
                            scale +
                                    maxDragScale * abs(cos(offsetAngle) * offset.x / size.maxDimension) *
                                    (width / height).fastCoerceAtMost(1f)
                        scaleY =
                            scale +
                                    maxDragScale * abs(sin(offsetAngle) * offset.y / size.maxDimension) *
                                    (height / width).fastCoerceAtMost(1f)
                    }
                } else {
                    null
                },
                onDrawSurface = {
                    if (tint.isSpecified) {
                        drawRect(tint, blendMode = BlendMode.Hue)
                        drawRect(tint.copy(alpha = 0.75f))
                    }
                    if (surfaceColor.isSpecified) {
                        drawRect(surfaceColor)
                    }
                }
            )
            .clickable(
                interactionSource = null,
                indication = if (isInteractive) null else LocalIndication.current,
                role = Role.Button,
                onClick = onClick
            )
            .then(
                if (isInteractive) {
                    Modifier
                        .then(interactiveHighlight.modifier)
                        .then(interactiveHighlight.gestureModifier)
                } else {
                    Modifier
                }
            )
            .height(height)
            .padding(horizontal = contentPadding),
        horizontalArrangement = Arrangement.spacedBy(8f.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
        content = content
    )
}
