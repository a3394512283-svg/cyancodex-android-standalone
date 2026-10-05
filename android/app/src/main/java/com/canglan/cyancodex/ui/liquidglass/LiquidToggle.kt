package com.canglan.cyancodex.ui.liquidglass

/*
 * 搬运自官方示例工程 (改动见下):
 *   https://github.com/Kyant0/AndroidLiquidGlass
 *   app/src/commonMain/kotlin/com/kyant/backdrop/catalog/components/LiquidToggle.kt
 * 许可: Apache License 2.0
 *
 * 胶囊玻璃开关, 官方尺寸 64dp × 28dp 轨道 + 40dp × 24dp 滑块。
 * 特点:
 *   - 轨道是**单独一层 backdrop**(layerBackdrop 录自己的纯色), 滑块把它当"背景"采样
 *     —— 所以滑块压过轨道时, 看到的是被放大的轨道色, 而不是简单叠一层
 *   - 滑块按下时会横向拉宽(scaleX 2/3→0.75)再弹回, 这是官方"果冻"手感的来源
 *   - 拖拽直接改 fraction(按 20dp 折算位移), 松手按 0.5 阈值吸附
 *
 * 改动 1: 强调色提成参数
 * 改动 3: 采样源是不是平底色(flatSource) —— 平底色下 blur/lens 是恒等变换, 跳过省着色器
 * 改动 4 (2026-09-26): 修"开关点不动/开了再关后开不回来"。
 *   原因: dampedDragAnimation 用 remember(animationScope) 只创建一次, 它构造时
 *   传进去的 onDragStopped 永远捕获**首次组合**那份 selected / onSelect 闭包。
 *   当 selected = { checked } (每次重组都是新 lambda) 时, 这份闭包里的 checked
 *   永远是启动时的值 —— 表现: 启动时 ON, 点一下变 OFF (✓), 再点一下 selected()
 *   还是返回 true (过期!) → fraction 永远停在 0f → 开不回来。
 *   拖拽走的是 didDrag 分支 (用 targetValue, 不读 selected()), 所以拖拽正常;
 *   点击走的是 else 分支 (读 selected()) → 卡死在初始值。
 *   修: 用 rememberUpdatedState 包一层, lambda 里调用时实时读最新闭包。
 *   LaunchedEffect 的 key 也从 selected (每次新实例) 改成 currentSelected (稳定 State 对象),
 *   否则 effect 会跟着每次重组重启, 跟 LiquidBottomTabs 的调用契约坑同性质。
 *   默认值 = 官方原值, 不传参时行为与官方一致。
 */

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastCoerceIn
import androidx.compose.ui.util.lerp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberBackdrop
import com.kyant.backdrop.backdrops.rememberCombinedBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.Shadow
import com.kyant.shapes.Capsule
import kotlinx.coroutines.flow.collectLatest

@Composable
fun LiquidToggle(
    selected: () -> Boolean,
    onSelect: (Boolean) -> Unit,
    backdrop: Backdrop,
    modifier: Modifier = Modifier,
    // 改动 1: 强调色提成参数。默认值 = 官方原值。
    accentColor: Color = if (!isSystemInDarkTheme()) Color(0xFF34C759) else Color(0xFF30D158),
    // 改动 3 (2026-09-26): 采样源是不是"平底色"。默认 false = 官方原样。
    // 详见下面 effects 与 knobBackdrop 两处的注释。
    flatSource: Boolean = false,
) {
    val isLightTheme = !isSystemInDarkTheme()
    val trackColor =
        if (isLightTheme) Color(0xFF787878).copy(0.2f)
        else Color(0xFF787880).copy(0.36f)

    val density = LocalDensity.current
    val isLtr = LocalLayoutDirection.current == LayoutDirection.Ltr
    val dragWidth = with(density) { 20f.dp.toPx() }
    val animationScope = rememberCoroutineScope()
    var didDrag by remember { mutableStateOf(false) }
    var fraction by remember { mutableFloatStateOf(if (selected()) 1f else 0f) }
    // ★ 改动 4 (2026-09-26): 修"开关点不动/开了再关后开不回来"。
    //   dampedDragAnimation 用 remember(animationScope) 只创建一次, 它构造时传进去的
    //   onDragStopped lambda 永远捕获**首次组合**那份 selected / onSelect 闭包。
    //   当 selected = { checked } (每次重组都是新实例) 时, 这份闭包里的 checked
    //   永远是启动时的值 —— 表现: 启动时 ON, 点一下变 OFF (✓), 再点一下
    //   selected() 还是返回 true (过期!) → fraction = 0f → 永远开不回来。
    //   拖拽走的是 didDrag 分支 (用 targetValue, 不读 selected()), 所以拖拽正常;
    //   但点击走的是 else 分支 (读 selected()) → 卡死在初始值。
    //
    //   用 rememberUpdatedState 包一层, lambda 里读 currentSelected.value / currentOnSelect.value(...)
    //   就是**调用时**实时拿, 而不是构造时定格。
    //   (currentSelected 同时也是下面 LaunchedEffect 的 key —— selected() 每次重组都是新 lambda,
    //    直接当 key 会让 effect 反复重启。)
    val currentSelected = rememberUpdatedState(selected)
    val currentOnSelect = rememberUpdatedState(onSelect)
    val dampedDragAnimation = remember(animationScope) {
        DampedDragAnimation(
            animationScope = animationScope,
            initialValue = fraction,
            valueRange = 0f..1f,
            visibilityThreshold = 0.001f,
            initialScale = 1f,
            pressedScale = 1.5f,
            onDragStarted = {},
            onDragStopped = {
                if (didDrag) {
                    fraction = if (targetValue >= 0.5f) 1f else 0f
                    currentOnSelect.value.invoke(fraction == 1f)
                    didDrag = false
                } else {
                    fraction = if (currentSelected.value.invoke()) 0f else 1f
                    currentOnSelect.value.invoke(fraction == 1f)
                }
            },
            onDrag = { _, dragAmount ->
                if (!didDrag) {
                    didDrag = dragAmount.x != 0f
                }
                val delta = dragAmount.x / dragWidth
                fraction =
                    if (isLtr) (fraction + delta).fastCoerceIn(0f, 1f)
                    else (fraction - delta).fastCoerceIn(0f, 1f)
            }
        )
    }
    LaunchedEffect(dampedDragAnimation) {
        snapshotFlow { fraction }
            .collectLatest { fraction ->
                dampedDragAnimation.updateValue(fraction)
            }
    }
    // key 用 currentSelected (稳定的 State 对象) —— selected() 每次重组是新实例,
    // 直接 LaunchedEffect(selected) 会反复重启 effect, 跟 LiquidBottomTabs 的坑同性质。
    LaunchedEffect(currentSelected) {
        snapshotFlow { currentSelected.value.invoke() }
            .collectLatest { isSelected ->
                val target = if (isSelected) 1f else 0f
                if (target != fraction) {
                    fraction = target
                    dampedDragAnimation.animateToValue(target)
                }
            }
    }

    val trackBackdrop = rememberLayerBackdrop()

    Box(
        modifier,
        contentAlignment = Alignment.CenterStart
    ) {
        Box(
            Modifier
                .layerBackdrop(trackBackdrop)
                .clip(Capsule())
                .drawBehind {
                    val fraction = dampedDragAnimation.value
                    drawRect(lerp(trackColor, accentColor, fraction))
                }
                .size(64f.dp, 28f.dp)
        )

        Box(
            Modifier
                .graphicsLayer {
                    val fraction = dampedDragAnimation.value
                    val padding = 2f.dp.toPx()
                    translationX =
                        if (isLtr) lerp(padding, padding + dragWidth, fraction)
                        else lerp(-padding, -(padding + dragWidth), fraction)
                }
                .semantics {
                    role = Role.Switch
                }
                .then(dampedDragAnimation.modifier)
                .drawBackdrop(
                    backdrop = rememberCombinedBackdrop(
                        backdrop,
                        rememberBackdrop(trackBackdrop) { drawBackdrop ->
                            val progress = dampedDragAnimation.pressProgress
                            val scaleX = lerp(2f / 3f, 0.75f, progress)
                            val scaleY = lerp(0f, 0.75f, progress)
                            scale(scaleX, scaleY) {
                                drawBackdrop()
                            }
                        }
                    ),
                    shape = { Capsule() },
                    effects = {
                        // ★ 改动 3 (2026-09-26): 采样源是**平底色**时, blur / lens 在数学上
                        //   都是**恒等变换** —— 常量模糊后还是常量, 折射位移采到的也还是同一个常量。
                        //   但两者**照样会跑一遍着色器**: blur 是 RenderEffect,
                        //   lens 是 AGSL RuntimeShader(软件渲染下极慢, 且带 chromaticAberration)。
                        //   本 App 的开关全都长在 `SectionCard`(纯色 surface)里, 采样源 =
                        //   `rememberCanvasBackdrop { drawRect(卡片色) }` + 轨道那块**纯色胶囊**,
                        //   所以这两项纯属空转。
                        //   实测(1080x2400 @420dpi, SwiftShader 软件渲染): 玻璃开启后主线程 ANR,
                        //   栈停在 android.graphics.HardwareRenderer.syncAndDrawFrame —— 等渲染线程画完一帧。
                        //   设置页一屏就有 20+ 个开关, 关掉这两项外观**逐像素不变**。
                        //   调用方传 flatSource=false 时保持官方原样。
                        if (!flatSource) {
                            val progress = dampedDragAnimation.pressProgress
                            blur(8f.dp.toPx() * (1f - progress))
                            lens(
                                5f.dp.toPx() * progress,
                                10f.dp.toPx() * progress,
                                chromaticAberration = true
                            )
                        }
                    },
                    highlight = {
                        val progress = dampedDragAnimation.pressProgress
                        Highlight.Ambient.copy(
                            width = Highlight.Ambient.width / 1.5f,
                            blurRadius = Highlight.Ambient.blurRadius / 1.5f,
                            alpha = progress
                        )
                    },
                    shadow = {
                        Shadow(
                            radius = 4f.dp,
                            color = Color.Black.copy(alpha = 0.05f)
                        )
                    },
                    innerShadow = {
                        val progress = dampedDragAnimation.pressProgress
                        InnerShadow(
                            radius = 4f.dp * progress,
                            alpha = progress
                        )
                    },
                    layerBlock = {
                        scaleX = dampedDragAnimation.scaleX
                        scaleY = dampedDragAnimation.scaleY
                        val velocity = dampedDragAnimation.velocity / 50f
                        scaleX /= 1f - (velocity * 0.75f).fastCoerceIn(-0.2f, 0.2f)
                        scaleY *= 1f - (velocity * 0.25f).fastCoerceIn(-0.2f, 0.2f)
                    },
                    onDrawSurface = {
                        val progress = dampedDragAnimation.pressProgress
                        drawRect(Color.White.copy(alpha = 1f - progress))
                    }
                )
                .size(40f.dp, 24f.dp)
        )
    }
}
