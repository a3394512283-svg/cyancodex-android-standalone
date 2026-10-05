package com.canglan.cyancodex.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SelectableChipColors
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.canglan.cyancodex.ui.liquidGlassActive
import com.canglan.cyancodex.ui.liquidglass.LiquidButton
import com.canglan.cyancodex.ui.liquidglass.LiquidToggle
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.rememberCanvasBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight

/*
 * ⚠️ 本文件只保留"玻璃**容器**"。按钮类不要在这里加 ——
 *    官方示例工程已经提供了正品 LiquidButton，就在 ui/liquidglass/ 下（逐字搬运，Apache-2.0）。
 *    自写的圆形/胶囊按钮已于 2026-09-24 全部替换为官方 LiquidButton 并删除：
 *      · AI 页发送键   → ui/liquidglass/LiquidButton（size 44dp 正圆 + tint 强调色）
 *      · 指令库玻璃 FAB → ui/liquidglass/LiquidButton（size 56dp 正圆 + surfaceColor 中性玻璃色）
 *    官方组件带 DampedDragAnimation（按压弹簧）+ InteractiveHighlight（跟手光斑）+ tanh 位移阻尼，
 *    这些是"看着像玻璃"和"真的是液态玻璃"的差别所在，自己写很难补齐。
 *
 *    之所以还留一个容器：官方没有对应物 —— LiquidBottomTabs 是底栏、LiquidButton 内部是
 *    带 clickable 的 Row（塞 TextField 会被抢焦点）。"页面内浮动玻璃条（输入框 + 按钮）"
 *    只能自己拼容器，但效果参数照抄官方。
 */

/**
 * 液态玻璃的表面色。
 *
 * 必须用**中性色**, 不能用 colorScheme.surface —— 它是品牌色(青绿)派生出来的,
 * 叠加后会整片染色, 玻璃"透出后面内容"的观感就没了。
 * 数值照抄官方示例: 深色 #121212@40% / 浅色 #FAFAFA@40%。
 */
@Composable
fun glassSurfaceColor(): Color {
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    return if (dark) Color(0xFF121212).copy(alpha = 0.40f)
    else Color(0xFFFAFAFA).copy(alpha = 0.40f)
}

/**
 * ★★ 玻璃态的文字必须与 Material 原版**逐项一致** —— 只换表面材质, 不动排版与配色。
 *
 * 2026-09-26 用户报「设置页一开液态玻璃, 字体大小出现问题, 颜色也出现了问题」,
 * 根因就是缺了这一步。两件事:
 *
 * 【字号】Material3 的 `Button` / `OutlinedButton` / `TextButton` / `FilterChip` 内部都会
 *   `ProvideTextStyle(typography.labelLarge)`, 而官方 `LiquidButton` 只是一个普通 `Row`,
 *   **不提供任何文字样式** → 玻璃态的文字掉回 `LocalTextStyle`(Material3 默认 bodyLarge, 16sp),
 *   比原版 labelLarge(14sp) 大一号, 字距也从 0.1sp 变成 0.5sp。
 *   实测(1080x2400 @420dpi, 字符串「清除本地缓存」): 墨迹 **214x34px → 254x40px**, 行宽 +19%。
 *
 * 【字色】Material3 1.4.0 里 `OutlinedButton` / `TextButton` / 未选中 `FilterChip` 的字色令牌
 *   都是 **OnSurfaceVariant**, 不是 Primary(已反编译 tokens 核实:
 *   `OutlinedButtonTokens.LabelTextColor` / `TextButtonTokens.LabelColor` /
 *   `FilterChipTokens.UnselectedLabelTextColor` 都指向 ColorSchemeKeyTokens.OnSurfaceVariant)。
 *   之前玻璃态一律给 Primary, 于是「清除本地缓存」「选择图片」这些描边按钮一开玻璃就从灰变绿。
 *
 * @param contentColor 必须与对应 Material 组件**解析出来**的 contentColor 完全一致
 */
@Composable
private fun GlassText(contentColor: Color, content: @Composable () -> Unit) {
    CompositionLocalProvider(
        LocalContentColor provides contentColor,
        LocalTextStyle provides MaterialTheme.typography.labelLarge,
    ) { content() }
}

/**
 * 液态玻璃容器 —— 一块任意形状的玻璃, 里面放什么由调用方决定。
 * 典型用途: 页面底部的**浮动输入条**(输入框 + 发送按钮)。
 *
 * ⚠️ 它是"浮层", 不是 Scaffold 的 bottomBar。
 * 玻璃采的是它**下面**的真实像素; 挂在 Scaffold.bottomBar 里时 Scaffold 会把 body 顶上去,
 * 玻璃正下方只剩 Scaffold 的纯色底 —— 纯色模糊后还是纯色, 折射也无从产生位移,
 * 玻璃就退化成"一块半透明矩形"。这跟底部导航栏当初踩的是同一个坑, 修法也一样:
 * 让内容一路排到屏幕底(列表末尾补出本组件的高度), 玻璃才真的压在内容上。
 *
 * 调用方接线姿势 (采样源必须**排除本组件自己**):
 * ```
 * val barBackdrop = rememberLayerBackdrop()
 * Box(Modifier.fillMaxSize()) {
 *     LazyColumn(Modifier.fillMaxSize().layerBackdrop(barBackdrop)) { ... }   // 只录内容
 *     GlassSurface(
 *         backdrop = barBackdrop,
 *         modifier = Modifier.align(Alignment.BottomCenter),
 *     ) { ...输入框 + 发送按钮... }
 * }
 * ```
 * 之所以强调"排除自己": layerBackdrop 录的是节点**全部**绘制内容, drawBackdrop 又取**上一帧**
 * 的纹理 —— 玻璃若在采样节点内, 就会采到上一帧的自己, 滚动时表现为拖影(无限镜)。
 */
@Composable
fun GlassSurface(
    backdrop: LayerBackdrop,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(28.dp),
    surface: Color = glassSurfaceColor(),
    contentAlignment: Alignment = Alignment.Center,
    refractionHeight: Dp = 24.dp,
    refractionAmount: Dp = 24.dp,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier
            .drawBackdrop(
                backdrop = backdrop,
                shape = { shape },
                effects = {
                    vibrancy()
                    blur(8.dp.toPx())
                    lens(refractionHeight.toPx(), refractionAmount.toPx())
                },
                highlight = { Highlight.Default },
                onDrawSurface = { drawRect(surface) },
            ),
        contentAlignment = contentAlignment,
        content = content,
    )
}

/**
 * 流式布局里的玻璃按钮 —— 采样源是**页面底色**(`rememberCanvasBackdrop`), 不是页面内容。
 *
 * ⚠️ 为什么不用页面内容当采样源: 这类按钮嵌在 `Column` / `LazyColumn` 流里, 和页面内容同属一个
 * 节点, 没法把"按钮自己"排除出采样范围 → 会采到上一帧的自己(静止怪像 + 滚动拖影)。
 * 纯色采样没有这个问题, 代价是 `lens` 折射没有梯度可折(纯色模糊后还是纯色), 只剩模糊 + 高光 + 表面色。
 * 这个取舍与 [GlassSwitch] 一致, 也正是官方 `ToggleContent` 在纯色卡片下的做法。
 *
 * 玻璃开启时用官方 [LiquidButton] 正品(按压弹簧 + 跟手光斑 + tanh 位移阻尼);
 * 关闭时回落 Material3 [Button] / [OutlinedButton], 与原版逐字一致。
 *
 * 2026-09-26 用户要求"部分页面有单独的按钮, 也要液态玻璃" —— 指令详情页 / 设置页的
 * 购买、收藏、点赞、清除缓存、选择图片等流式按钮统一走这个入口。
 *
 * 参数刻意与 Material3 的 [Button] / [OutlinedButton] **保持兼容**(`onClick` / `modifier` /
 * `enabled` / `colors` 同名同义), 这样全项目可以把 `Button(` / `OutlinedButton(` 机械替换过来:
 *   · `Button(...)`        → `GlassActionButton(filled = true, ...)`
 *   · `OutlinedButton(...)` → `GlassActionButton(...)`   (默认就是描边)
 *
 * ⚠️ `colors` **只透传给非玻璃回落**, 玻璃路径读不了它 ——
 *    Material3 的 `ButtonColors.containerColor(enabled)` / `contentColor(enabled)` 被标了
 *    `internal`, 本模块访问不到(编译报 "Cannot access ... it is internal")。
 *    所以"退出登录"(error 红) / "已关注"(surfaceVariant 灰) 这类**定制配色**的按钮,
 *    除了 `colors` 还要再显式给一份 [glassTint] / [glassContentColor],
 *    否则玻璃态会掉回默认配色(实心=主色底/onPrimary 字, 描边=玻璃底/onSurfaceVariant 字)。
 *
 * @param filled true = 实心主色按钮(对应 `Button`); false = 描边按钮(对应 `OutlinedButton`)
 * @param glassTint 玻璃态实心按钮的底色; 不传则用主色
 * @param glassContentColor 玻璃态的字色; 不传则实心取 onPrimary / 描边取 onSurfaceVariant
 *        (两者都照 Material3 1.4.0 的令牌: FilledButtonTokens.LabelTextColor=OnPrimary,
 *         OutlinedButtonTokens.LabelTextColor=OnSurfaceVariant)
 */
@Composable
fun GlassActionButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: ButtonColors? = null,
    filled: Boolean = false,
    glassTint: Color = Color.Unspecified,
    glassContentColor: Color = Color.Unspecified,
    glassHeight: Dp = 40.dp,
    glassPadding: Dp = 24.dp,
    content: @Composable RowScope.() -> Unit,
) {
    if (!liquidGlassActive) {
        // 非玻璃: 参数原样透传给 Material 原版, 外观逐像素一致
        if (!filled) {
            OutlinedButton(
                onClick = onClick, modifier = modifier, enabled = enabled,
                colors = colors ?: ButtonDefaults.outlinedButtonColors(),
                content = content,
            )
        } else {
            Button(
                onClick = onClick, modifier = modifier, enabled = enabled,
                colors = colors ?: ButtonDefaults.buttonColors(),
                content = content,
            )
        }
    } else {
        val bg = MaterialTheme.colorScheme.background
        // ⚠️ 实心按钮的"底色"取的是 tint(由 glassTint 传入), **不是**字色 ——
        //    Material 的 Button 是 containerColor 打底 + contentColor 上字, 两者分开。
        //    踩过: 一开始想从 colors 里取 containerColor, 但那是 internal; 退而取 contentColor
        //    又会把 `buttonColors(containerColor = error)` 的"退出登录"染成 onError(白)。
        val tint = when {
            !filled -> Color.Unspecified
            glassTint.isSpecified -> glassTint
            else -> MaterialTheme.colorScheme.primary
        }
        val contentColor = when {
            glassContentColor.isSpecified -> glassContentColor
            // 实心: FilledButtonTokens.ContainerColor = Primary, LabelTextColor = OnPrimary (已核实)
            filled -> MaterialTheme.colorScheme.onPrimary
            // 描边: OutlinedButtonTokens.LabelTextColor = **OnSurfaceVariant**, 不是 Primary。
            // 踩过: 这里原本写 Primary, 于是「清除本地缓存」「选择图片」「管理快捷指令」这些
            // 原版灰字按钮一开玻璃全变绿 —— 用户报的"颜色出现问题"就是它。
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        }
        LiquidButton(
            onClick = { if (enabled) onClick() },
            backdrop = rememberCanvasBackdrop { drawRect(bg) },
            modifier = modifier
                .height(glassHeight)
                .alpha(if (enabled) 1f else 0.38f),
            height = glassHeight,
            contentPadding = glassPadding,
            tint = tint,
            surfaceColor = if (!filled) glassSurfaceColor() else Color.Unspecified,
            // 采样源是 rememberCanvasBackdrop 铺的纯色 → blur/lens 恒等, 关掉省着色器
            flatSource = true,
        ) {
            // LiquidButton 不是 Material 组件: 既不设 LocalContentColor 也不设 LocalTextStyle,
            // 两样都要自己补齐, 否则实心按钮会变"蓝底+默认深色字", 且字号整体偏大(见 GlassText)。
            GlassText(contentColor) { content() }
        }
    }
}

/**
 * 分段选择器 —— 玻璃开启时用官方 [LiquidButton] 做胶囊, 否则回落 Material3 [FilterChip]。
 *
 * 用途: 设置页的「外观」(跟随系统/白天/夜晚) 与「主题配色」(品牌默认/莫奈取色/自定义颜色),
 * 以及指令编辑页里"每个属性一个颜色"的方块/连锁/红石选择器。
 * 采样源同 [GlassActionButton] —— 纯色卡片底, 避免拖影。
 * 选中态 = 玻璃染强调色, 未选中 = 中性玻璃, 与按钮体系一致。
 *
 * ⚠️ 同 [GlassActionButton]: `colors` 只透传给非玻璃回落。Material3 的
 *    `SelectableChipColors.labelColor(enabled, selected)` 是 `internal`, 玻璃路径读不到,
 *    所以定制配色的 chip 要额外显式给 [glassAccent]。
 *
 * @param glassAccent 选中态的**容器**色(不是字色); 不传则用原版 FilterChip 的
 *        `secondaryContainer`(已反编译核实 FilterChipTokens.FlatSelectedContainerColor)
 */
@Composable
fun GlassFilterChip(
    selected: Boolean,
    onClick: () -> Unit,
    label: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: SelectableChipColors? = null,
    glassAccent: Color = Color.Unspecified,
) {
    if (!liquidGlassActive) {
        // 非玻璃: 参数原样透传, 外观逐像素一致
        FilterChip(
            selected = selected, onClick = onClick, label = label, modifier = modifier,
            enabled = enabled, colors = colors ?: FilterChipDefaults.filterChipColors(),
        )
    } else {
        val cardColor = MaterialTheme.colorScheme.surface
        // 原版 FilterChip 的选中容器是 secondaryContainer(浅色主题下是米黄), **不是**主色。
        // 踩过: 这里原本染主色 + 白字, 一开玻璃 chip 就从"米黄底棕字"变成"绿底白字" ——
        // 用户报的"颜色出现问题"包含它。
        val accent = if (glassAccent.isSpecified) glassAccent
        else MaterialTheme.colorScheme.secondaryContainer
        val labelColor = if (selected) MaterialTheme.colorScheme.onSecondaryContainer
        else MaterialTheme.colorScheme.onSurfaceVariant
        LiquidButton(
            onClick = { if (enabled) onClick() },
            backdrop = rememberCanvasBackdrop { drawRect(cardColor) },
            modifier = modifier.height(32.dp).alpha(if (enabled) 1f else 0.38f),
            height = 32.dp,
            contentPadding = 14.dp,
            tint = if (selected) accent else Color.Unspecified,
            surfaceColor = if (selected) Color.Unspecified else glassSurfaceColor(),
            // 采样源是 rememberCanvasBackdrop 铺的纯色 → blur/lens 恒等, 关掉省着色器
            flatSource = true,
        ) {
            GlassText(labelColor) { label() }
        }
    }
}

/**
 * 圆形图标按钮 —— 玻璃开启时用官方 [LiquidButton] 做正圆, 否则回落 Material3 [IconButton]。
 *
 * 用途: AI 回复卡片上的「复制」「有用」「没用」这类**没有文字**的小图标按钮。
 * (2026-09-26 用户报「aiagent 功能按钮没有液态玻璃」—— 指的就是这三处。
 *  AI 页的顶栏三个 action / 输入条发送键 / 「保存配置」当时已经是玻璃了, 漏的是卡片上的图标按钮。)
 *
 * ⚠️ 与 [GlassActionButton] / [GlassTextButton] 的关键区别: 这里**不提供 LocalTextStyle**
 *    —— 图标按钮没有文字, 且 `IconButton` 自己也不 `ProvideTextStyle`。
 *    图标色由调用方写在 `Icon(tint = ...)` 上, 玻璃路径原样生效(与 Material 路径一致),
 *    所以「点赞变主色 / 点踩变 error」这类定制色**不用**额外参数。
 *
 * ⚠️ `contentPadding` 必须给 0: [LiquidButton] 会在 Modifier 链尾追加
 *    `.padding(horizontal = contentPadding)`, 而这里的直径只有 30~32dp ——
 *    用默认的 16dp 会得到负的内容宽度(图标被挤没)。图标靠 `Row` 的水平居中摆放。
 *
 * @param size 正圆直径。**必须与调用方原先传给 `IconButton` 的 `Modifier.size(...)` 一致**,
 *        否则会出现"一开玻璃这个按钮就胖了一圈"。原先没有 size 的(默认 48dp)传 48.dp。
 * @param backdropColor 采样源底色 —— 这个按钮**底下是什么颜色**就传什么。
 *        卡片里传 `colorScheme.surface`, 页面底上保持默认的 `colorScheme.background`。
 *        纯色采样没有折射梯度(模糊后还是纯色), 与 [GlassActionButton] 是同一取舍。
 *
 * ⚠️ 与 `Common.kt` 的 `TopBarAction` 长得几乎一样, 但**不要合并**:
 *    顶栏那个用的是 `LocalTopBarBackdrop`(顶栏那一层录的**真实页面内容**), 有折射;
 *    这里长在 LazyColumn 里, 按钮自己在采样节点内部, 只能用纯色 canvas backdrop。
 *    (参数刻意与 [TopBarAction] 对齐: 40dp + contentPadding 0 + 中性表面色。)
 */
@Composable
fun GlassIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    size: Dp = 40.dp,
    backdropColor: Color = MaterialTheme.colorScheme.background,
    content: @Composable () -> Unit,
) {
    if (!liquidGlassActive) {
        // 非玻璃: 参数原样透传, 外观逐像素一致
        IconButton(
            onClick = onClick, modifier = modifier.size(size), enabled = enabled, content = content,
        )
    } else {
        LiquidButton(
            onClick = { if (enabled) onClick() },
            backdrop = rememberCanvasBackdrop { drawRect(backdropColor) },
            modifier = modifier.size(size).alpha(if (enabled) 1f else 0.38f),
            height = size,
            contentPadding = 0.dp,
            surfaceColor = glassSurfaceColor(),
            // 采样源是 rememberCanvasBackdrop 铺的纯色 → blur/lens 恒等, 关掉省着色器
            flatSource = true,
        ) { content() }
    }
}

/**
 * 文字按钮 —— 玻璃开启时 [LiquidButton], 否则回落 Material3 [TextButton]。
 *
 * 用途: AI 历史卡片里的「复制回复」等只有文字(+ 前置小图标)的按钮。
 *
 * ⚠️ 字色/字号必须严格对齐 [TextButton] —— 这是"只换表面材质, 不动排版与配色"的一部分:
 *    · 字色 = `onSurfaceVariant`(`TextButtonTokens.LabelColor`, 已反编译核实, **不是** Primary);
 *    · 字号 = `labelLarge`(`LiquidButton` 是普通 `Row`, 不 `ProvideTextStyle`,
 *      不补就掉回 Material3 默认的 bodyLarge 16sp, 比原版大一号 —— 详见 [GlassText])。
 */
@Composable
fun GlassTextButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    backdropColor: Color = MaterialTheme.colorScheme.surface,
    content: @Composable RowScope.() -> Unit,
) {
    if (!liquidGlassActive) {
        TextButton(onClick = onClick, modifier = modifier, enabled = enabled, content = content)
    } else {
        LiquidButton(
            onClick = { if (enabled) onClick() },
            backdrop = rememberCanvasBackdrop { drawRect(backdropColor) },
            modifier = modifier.height(40.dp).alpha(if (enabled) 1f else 0.38f),
            height = 40.dp,
            contentPadding = 12.dp,
            surfaceColor = glassSurfaceColor(),
            // 采样源是 rememberCanvasBackdrop 铺的纯色 → blur/lens 恒等, 关掉省着色器
            flatSource = true,
        ) {
            GlassText(MaterialTheme.colorScheme.onSurfaceVariant) { content() }
        }
    }
}

/**
 * 开关 —— 玻璃开启时用官方 [LiquidToggle]，否则回落到 Material3 [Switch]。
 *
 * 全 App 的开关都走这一个入口, 免得"改一处漏一处"(设置页就有 5 个)。
 *
 * 采样源用 `rememberCanvasBackdrop { drawRect(卡片底色) }` —— 这是**官方做法**:
 * 官方 `ToggleContent` 演示了两种场景, 一种是开关浮在壁纸上(采真实背景),
 * 另一种就是**开关放在纯色卡片里**, 那里用的正是 canvas backdrop。
 * 本 App 的开关都长在 `SectionCard`(containerColor = colorScheme.surface) 里, 属于后者。
 *
 * ⚠️ 不要因为"采不到真实内容"就自己手写开关 —— 官方 `LiquidToggle` 的果冻手感来自
 *    滑块按下时横向拉宽再弹回、以及滑块把轨道当背景二次采样, 自己拼很难补齐。
 */
@Composable
fun GlassSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    if (!liquidGlassActive) {
        // 非玻璃: Material 原生 Switch 自带 enabled 语义(禁用变灰 + 不响应), 直接透传
        Switch(checked = checked, onCheckedChange = onCheckedChange, modifier = modifier, enabled = enabled)
    } else {
        // 开关底下就是卡片纯色, 采样源按卡片底色铺一块即可(与官方 ToggleContent 的第二例一致)
        val cardColor = MaterialTheme.colorScheme.surface
        LiquidToggle(
            // ⚠️ LiquidToggle 没有 enabled 参数(官方原样) —— 与 GlassIconButton 同一套等效禁用:
            //    回调里拦截 + alpha 压暗。别想着"反正 onSelect 里会 return", 那样开关视觉上仍会
            //    随手势滑动, 用户以为改成功了。
            selected = { checked },
            onSelect = { if (enabled) onCheckedChange(it) },
            backdrop = rememberCanvasBackdrop { drawRect(cardColor) },
            modifier = modifier.alpha(if (enabled) 1f else 0.38f),
            // 官方写死 iOS 绿; 本 App 是青绿主题, 换成主色(与底栏/药丸同一处理)
            accentColor = MaterialTheme.colorScheme.primary,
            // 采样源是 rememberCanvasBackdrop 铺的纯色 → blur/lens 恒等, 关掉省着色器
            flatSource = true,
        )
    }
}
