package com.canglan.cyancodex.ui.liquidglass

/*
 * 搬运自官方示例工程 (逐字搬运, 仅 2 处改动):
 *   https://github.com/Kyant0/AndroidLiquidGlass
 *   app/src/commonMain/kotlin/com/kyant/backdrop/catalog/components/LiquidBottomTabs.kt
 * 许可: Apache License 2.0
 *
 * 改动 1: package 改为本包名
 * 改动 2: 新增 isLightTheme 参数 —— 官方内部写死 `!isSystemInDarkTheme()`,
 *         但本 App 有"强制浅色/强制深色"设置, 必须由调用方传入才不会与主题打架。
 *         默认值仍是官方的 `!isSystemInDarkTheme()`, 不传参时行为与官方一致。
 * 改动 3: 新增 accentColor 参数 —— 官方写死蓝色, 本 App 绿色主题下会显得突兀。
 *         默认值 = 官方原值, 不传参时行为与官方一致。
 * 改动 4 (2026-09-26, 修 bug): 给"采样层"(那条 alpha=0 的 Row) 补上与可见层**完全相同**的
 *         layerBlock。官方只给可见层传了 layerBlock, 导致按压/拖动时可见层被横向拉伸而副本没有,
 *         药丸里的着色图标与真实图标错位 —— 就是用户报的"拖动时有双重的图标"。
 *         详见下面 layerBlock 那处的注释。
 * 改动 5 (2026-09-26, 修 bug): 采样层的 LocalLiquidBottomTabScale 固定为 1f。
 *         官方是 lerp(1f, 1.2f, pressProgress), 只作用于采样层 → 副本图标比真实图标大 20%,
 *         同样表现为"两个图标"。官方 demo 页签是纯文字所以看不出来, 本工程是"图标+文字"。
 *
 * ★ 这份代码里几个"正品"的关键点(上一版自己写的时候全漏了):
 *   1. 形状用 com.kyant.shapes.Capsule() —— 连续曲率(Continuous)胶囊, 不是 RoundedCornerShape(50%)
 *   2. 选中药丸用 rememberCombinedBackdrop(backdrop, tabsBackdrop) —— **二次折射**:
 *      tabsBackdrop 录的是那条 alpha=0 的内层 Row(它自己也做了一次 lens),
 *      所以药丸折射的是"已经被折射过一次的玻璃", 边缘才会出现有层次的畸变
 *   3. DampedDragAnimation: 按压弹簧放大(pressedScale=78/56)、拖拽跟手、速度追踪
 *   4. InteractiveHighlight: 按下时手指位置一团 AGSL 柔光
 *   5. 整条玻璃在按压时会横向拉伸: layerBlock 里 scaleX = lerp(1, 1 + 16dp/width, progress)
 *   6. 静止态: 药丸的 highlight/shadow/innerShadow/lens 全乘 pressProgress → 都是 0,
 *      只剩一层 10% 的表面色。**凸起感来自边缘折射, 不是来自白色填充** ——
 *      这正是"液态玻璃"和"贴一块半透明色块"的区别。
 */

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.spring
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastCoerceIn
import androidx.compose.ui.util.fastRoundToInt
import androidx.compose.ui.util.lerp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberCombinedBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.Shadow
import com.kyant.shapes.Capsule
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.sign

@Composable
fun LiquidBottomTabs(
    selectedTabIndex: () -> Int,
    onTabSelected: (index: Int) -> Unit,
    backdrop: Backdrop,
    tabsCount: Int,
    modifier: Modifier = Modifier,
    isLightTheme: Boolean = !isSystemInDarkTheme(),
    // 改动 3: 选中态着色提成参数。
    // 官方写死蓝色(#0088FF / #0091FF); 本 App 是绿色主题, 蓝色会读起来像"另一个 App 的配色",
    // 所以由调用方传入。默认值 = 官方原值, 不传参时行为与官方一致。
    // 它的作用: 给那份 alpha=0 的副本整体着色, 再由选中药丸做折射 —— 于是药丸里的图标/文字
    // 就带上主题色。这是官方表达"选中"的唯一手段(静止态药丸本身只有 10% 黑)。
    accentColor: Color = if (isLightTheme) Color(0xFF0088FF) else Color(0xFF0091FF),
    content: @Composable RowScope.() -> Unit
) {
    val containerColor =
        if (isLightTheme) Color(0xFFFAFAFA).copy(0.4f)
        else Color(0xFF121212).copy(0.4f)

    val tabsBackdrop = rememberLayerBackdrop()

    BoxWithConstraints(
        modifier,
        contentAlignment = Alignment.CenterStart
    ) {
        val density = LocalDensity.current
        val tabWidth = with(density) {
            (constraints.maxWidth.toFloat() - 8f.dp.toPx()) / tabsCount
        }

        val offsetAnimation = remember { Animatable(0f) }
        val panelOffset by remember(density) {
            derivedStateOf {
                val fraction = (offsetAnimation.value / constraints.maxWidth).fastCoerceIn(-1f, 1f)
                with(density) {
                    4f.dp.toPx() * fraction.sign * EaseOut.transform(abs(fraction))
                }
            }
        }

        val isLtr = LocalLayoutDirection.current == LayoutDirection.Ltr
        val animationScope = rememberCoroutineScope()
        var currentIndex by remember(selectedTabIndex) {
            mutableIntStateOf(selectedTabIndex())
        }
        // ★ 改动 4 (2026-09-26): 见 LiquidToggle.kt 同号改动 —— 闭包过期, 改用 rememberUpdatedState
        // 实时读 onTabSelected, 否则 effect 捕获的是**首次组合**那份闭包, 闭包里捕获的
        // currentRoute / shownTabs 全部过期, 出现"拖药丸切页不导航""误把离开 Tab 页当成选首页"。
        val currentOnTabSelected = rememberUpdatedState(onTabSelected)
        val dampedDragAnimation = remember(animationScope) {
            DampedDragAnimation(
                animationScope = animationScope,
                initialValue = selectedTabIndex().toFloat(),
                valueRange = 0f..(tabsCount - 1).toFloat(),
                visibilityThreshold = 0.001f,
                initialScale = 1f,
                pressedScale = 78f / 56f,
                onDragStarted = {},
                onDragStopped = {
                    val targetIndex = targetValue.fastRoundToInt().fastCoerceIn(0, tabsCount - 1)
                    currentIndex = targetIndex
                    animateToValue(targetIndex.toFloat())
                    animationScope.launch {
                        offsetAnimation.animateTo(
                            0f,
                            spring(1f, 300f, 0.5f)
                        )
                    }
                },
                onDrag = { _, dragAmount ->
                    updateValue(
                        (targetValue + dragAmount.x / tabWidth * if (isLtr) 1f else -1f)
                            .fastCoerceIn(0f, (tabsCount - 1).toFloat())
                    )
                    animationScope.launch {
                        offsetAnimation.snapTo(offsetAnimation.value + dragAmount.x)
                    }
                }
            )
        }
        LaunchedEffect(selectedTabIndex) {
            snapshotFlow { selectedTabIndex() }
                .collectLatest { index ->
                    currentIndex = index
                }
        }
        LaunchedEffect(dampedDragAnimation) {
            snapshotFlow { currentIndex }
                .drop(1)
                .collectLatest { index ->
                    dampedDragAnimation.animateToValue(index.toFloat())
                    currentOnTabSelected.value(index)
                }
        }

        val interactiveHighlight = remember(animationScope) {
            InteractiveHighlight(
                animationScope = animationScope,
                position = { size, offset ->
                    Offset(
                        if (isLtr) (dampedDragAnimation.value + 0.5f) * tabWidth + panelOffset
                        else size.width - (dampedDragAnimation.value + 0.5f) * tabWidth + panelOffset,
                        size.height / 2f
                    )
                }
            )
        }

        Row(
            Modifier
                .graphicsLayer {
                    translationX = panelOffset
                }
                .drawBackdrop(
                    backdrop = backdrop,
                    shape = { Capsule() },
                    effects = {
                        vibrancy()
                        blur(8f.dp.toPx())
                        lens(24f.dp.toPx(), 24f.dp.toPx())
                    },
                    layerBlock = {
                        val progress = dampedDragAnimation.pressProgress
                        val scale = lerp(1f, 1f + 16f.dp.toPx() / size.width, progress)
                        scaleX = scale
                        scaleY = scale
                    },
                    onDrawSurface = { drawRect(containerColor) }
                )
                .then(interactiveHighlight.modifier)
                .height(64f.dp)
                .fillMaxWidth()
                .padding(4f.dp),
            verticalAlignment = Alignment.CenterVertically,
            content = content
        )

        CompositionLocalProvider(
            // ⚠️ 本工程修改(非官方原样): 官方是 `lerp(1f, 1.2f, dampedDragAnimation.pressProgress)`,
            // 即拖动/按压时把**药丸里那一层**放大到 1.2 倍。官方 demo 的 tab 内容是纯文字, 放大后
            // 看着像"放大镜"; 本工程是"图标 + 文字"竖排, 放大后与外面那层明显不重合 → 用户报
            // "拖动时有双重的图标"。固定为 1f 后, 药丸移动时内容与外面完全重合, 只变绿 + 出高光。
            LocalLiquidBottomTabScale provides { 1f }
        ) {
            Row(
                Modifier
                    .clearAndSetSemantics {}
                    .alpha(0f)
                    .layerBackdrop(tabsBackdrop)
                    .graphicsLayer {
                        translationX = panelOffset
                    }
                    .drawBackdrop(
                        backdrop = backdrop,
                        shape = { Capsule() },
                        effects = {
                            val progress = dampedDragAnimation.pressProgress
                            vibrancy()
                            blur(8f.dp.toPx())
                            lens(
                                24f.dp.toPx() * progress,
                                24f.dp.toPx() * progress
                            )
                        },
                        highlight = {
                            val progress = dampedDragAnimation.pressProgress
                            Highlight.Default.copy(alpha = progress)
                        },
                        // ★★ 本工程修改(非官方原样 —— 官方缺陷的必要修正, 见文件头"改动 4")
                        // 官方**只**给可见层传了 layerBlock; 而 drawBackdrop 内部会把它展开成
                        //     Modifier.graphicsLayer(layerBlock)
                        // 插在 DrawBackdropElement **之前** —— 也就是说它是套在**整棵子树**上的,
                        // 连 Row 里的图标/文字一起被缩放(TransformOrigin 默认居中)。
                        // 于是按压/拖动时: 可见层图标被横向拉伸(最大 1 + 16dp/width ≈ 1.04),
                        // 而这份 alpha=0 的采样副本没被拉伸。药丸采的是副本 → 副本里的着色图标
                        // 与外面的真实图标**错位**, 看起来就是"拖动时出现两个图标"。
                        // 实测(1080x2400 @420dpi): 好友图标 209px → 201px, 位移 -8px;
                        // 层中心 508.5px, 缩放 1.0267 时预测 508.5+(209-508.5)*1.0267 = 201 ✔
                        // 修法: 给采样层传**完全相同**的 layerBlock, 两层同步缩放, 副本与真实图标重合。
                        layerBlock = {
                            val progress = dampedDragAnimation.pressProgress
                            val scale = lerp(1f, 1f + 16f.dp.toPx() / size.width, progress)
                            scaleX = scale
                            scaleY = scale
                        },
                        onDrawSurface = { drawRect(containerColor) }
                    )
                    .then(interactiveHighlight.modifier)
                    // 上一轮曾把这里改成与可见层相同的 `.height(64f.dp) + .padding(4f.dp)`,
                    // 理由是"两层内容中心相差 4dp"。**那是错的** —— 实测与推演都证明两层等价:
                    //   · 可见层  : 节点 64dp, 四边 padding 4dp → 内容盒 4dp..60dp, 高 56dp
                    //   · 官方采样层: 节点 56dp, 只有水平 padding 4dp, 被 Box 的 CenterStart 居中
                    //                → 节点占 4dp..60dp, 内容盒也是 4dp..60dp, 高 56dp
                    // 两者内容盒逐像素重合, 所以那次改动是**空操作**, 真正的重影原因见上面的 layerBlock。
                    // 这里回退成官方原样, 缩小与上游的差异。
                    .height(56f.dp)
                    .fillMaxWidth()
                    .padding(horizontal = 4f.dp)
                    .graphicsLayer(colorFilter = ColorFilter.tint(accentColor)),
                verticalAlignment = Alignment.CenterVertically,
                content = content
            )
        }

        Box(
            Modifier
                .padding(horizontal = 4f.dp)
                .graphicsLayer {
                    translationX =
                        if (isLtr) dampedDragAnimation.value * tabWidth + panelOffset
                        else size.width - (dampedDragAnimation.value + 1f) * tabWidth + panelOffset
                }
                .then(interactiveHighlight.gestureModifier)
                .then(dampedDragAnimation.modifier)
                .drawBackdrop(
                    backdrop = rememberCombinedBackdrop(backdrop, tabsBackdrop),
                    shape = { Capsule() },
                    effects = {
                        val progress = dampedDragAnimation.pressProgress
                        lens(
                            10f.dp.toPx() * progress,
                            14f.dp.toPx() * progress,
                            chromaticAberration = true
                        )
                    },
                    highlight = {
                        val progress = dampedDragAnimation.pressProgress
                        Highlight.Default.copy(alpha = progress)
                    },
                    shadow = {
                        val progress = dampedDragAnimation.pressProgress
                        Shadow(alpha = progress)
                    },
                    innerShadow = {
                        val progress = dampedDragAnimation.pressProgress
                        InnerShadow(
                            radius = 8f.dp * progress,
                            alpha = progress
                        )
                    },
                    layerBlock = {
                        scaleX = dampedDragAnimation.scaleX
                        scaleY = dampedDragAnimation.scaleY
                        val velocity = dampedDragAnimation.velocity / 10f
                        scaleX /= 1f - (velocity * 0.75f).fastCoerceIn(-0.2f, 0.2f)
                        scaleY *= 1f - (velocity * 0.25f).fastCoerceIn(-0.2f, 0.2f)
                    },
                    onDrawSurface = {
                        val progress = dampedDragAnimation.pressProgress
                        drawRect(
                            if (isLightTheme) Color.Black.copy(0.1f)
                            else Color.White.copy(0.1f),
                            alpha = 1f - progress
                        )
                        drawRect(Color.Black.copy(alpha = 0.03f * progress))
                    }
                )
                .height(56f.dp)
                .fillMaxWidth(1f / tabsCount)
        )
    }
}
