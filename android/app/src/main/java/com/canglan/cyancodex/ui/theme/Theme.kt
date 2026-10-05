package com.canglan.cyancodex.ui.theme

import android.os.Build
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import com.canglan.cyancodex.ui.AppState

// 沧蓝工坊 CyanCodex 主题: 草绿 + 金色 (MC 风格), 支持 白天/夜晚
val GrassGreen = Color(0xFF4ADE80)
val GrassGreenDark = Color(0xFF16A34A)
val Gold = Color(0xFFFBBF24)
val SkyBlue = Color(0xFF38BDF8)
val DeepBg = Color(0xFF0E1512)
val Surface = Color(0xFF16221C)
val SurfaceHigh = Color(0xFF1E2D24)
val ErrorRed = Color(0xFFF87171)
val TextPrimary = Color(0xFFE7F2EA)
val TextSecondary = Color(0xFF9DB4A5)

private val DarkColors = darkColorScheme(
    primary = GrassGreen,
    onPrimary = Color(0xFF052E16),
    primaryContainer = Color(0xFF14532D),
    onPrimaryContainer = GrassGreen,
    secondary = Gold,
    onSecondary = Color(0xFF451A03),
    secondaryContainer = Color(0xFF78350F),
    onSecondaryContainer = Gold,
    tertiary = SkyBlue,
    background = DeepBg,
    onBackground = TextPrimary,
    surface = Surface,
    onSurface = TextPrimary,
    surfaceVariant = SurfaceHigh,
    onSurfaceVariant = TextSecondary,
    error = ErrorRed,
    onError = Color(0xFF2D0A0A),
)

private val LightColors = lightColorScheme(
    primary = GrassGreenDark,
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFDCFCE7),
    onPrimaryContainer = Color(0xFF052E16),
    secondary = Color(0xFFB45309),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFFEF3C7),
    onSecondaryContainer = Color(0xFF451A03),
    tertiary = SkyBlue,
    background = Color(0xFFF6F8F6),
    onBackground = Color(0xFF16221C),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF16221C),
    surfaceVariant = Color(0xFFE4EDE7),
    onSurfaceVariant = Color(0xFF4E6156),
    error = Color(0xFFB3261E),
    onError = Color(0xFFFFFFFF),
)

/** 系统是否支持莫奈取色 (Material You / 动态取色): Android 12 (API 31) 及以上 */
fun dynamicColorSupported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

/**
 * 由单个主色生成整套配色 (自定义颜色用)。
 * 用 seed 的色相为主轴, 按明度/饱和度派生出 Material3 需要的各个色槽, 保证明暗两套都协调可读。
 */
fun colorSchemeFromSeed(seed: Color, dark: Boolean): ColorScheme {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(seed.toArgb(), hsv)
    val h = hsv[0]
    val s = hsv[1].coerceIn(0.30f, 0.85f)

    fun tone(value: Float, sat: Float = s, hue: Float = h): Color =
        Color(android.graphics.Color.HSVToColor(floatArrayOf(hue, sat.coerceIn(0f, 1f), value.coerceIn(0f, 1f))))

    return if (dark) {
        darkColorScheme(
            primary = tone(0.72f),
            onPrimary = tone(0.16f, sat = 0.35f),
            primaryContainer = tone(0.32f),
            onPrimaryContainer = tone(0.90f),
            secondary = tone(0.66f, s * 0.65f),
            onSecondary = tone(0.16f, s * 0.35f),
            secondaryContainer = tone(0.28f, s * 0.70f),
            onSecondaryContainer = tone(0.88f, s * 0.60f),
            tertiary = tone(0.74f, s * 0.60f, (h + 60f) % 360f),
            background = tone(0.08f, s * 0.30f),
            onBackground = tone(0.92f, s * 0.20f),
            surface = tone(0.12f, s * 0.32f),
            onSurface = tone(0.92f, s * 0.20f),
            surfaceVariant = tone(0.19f, s * 0.36f),
            onSurfaceVariant = tone(0.70f, s * 0.28f),
            error = ErrorRed,
            onError = Color(0xFF2D0A0A),
        )
    } else {
        lightColorScheme(
            primary = tone(0.42f),
            onPrimary = Color(0xFFFFFFFF),
            primaryContainer = tone(0.88f, s * 0.50f),
            onPrimaryContainer = tone(0.24f),
            secondary = tone(0.46f, s * 0.65f),
            onSecondary = Color(0xFFFFFFFF),
            secondaryContainer = tone(0.90f, s * 0.45f),
            onSecondaryContainer = tone(0.26f, s * 0.60f),
            tertiary = tone(0.44f, s * 0.60f, (h + 60f) % 360f),
            background = tone(0.97f, s * 0.12f),
            onBackground = tone(0.16f, s * 0.35f),
            surface = Color(0xFFFDFDFD),
            onSurface = tone(0.16f, s * 0.35f),
            surfaceVariant = tone(0.92f, s * 0.20f),
            onSurfaceVariant = tone(0.42f, s * 0.30f),
            error = Color(0xFFB3261E),
            onError = Color(0xFFFFFFFF),
        )
    }
}

@Composable
fun CyanCodexTheme(
    darkTheme: Boolean = true,
    dynamicColor: Boolean = false,
    customSeed: Color? = null,
    content: @Composable () -> Unit,
) {
    val base = when {
        // 莫奈取色: 系统支持(Android 12+)才用, 否则自动回落到品牌配色
        dynamicColor && dynamicColorSupported() -> {
            val ctx = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        }
        // 自定义主色
        customSeed != null -> colorSchemeFromSeed(customSeed, darkTheme)
        // 品牌默认
        else -> if (darkTheme) DarkColors else LightColors
    }
    // ★ 自定义背景图片启用时, 把 background 改成"全透明但**保留 RGB**"。
    //
    // 为什么要动它: 各页面的 Scaffold 默认容器色就是 `colorScheme.background`, 它是不透明的,
    // 会把 MainActivity 最底层那张全局背景图整个盖住 —— 这正是以前"背景图只看得见于主页"
    // 的原因(主页是在自己的内容层里又画了一遍)。
    //
    // 为什么用 `copy(alpha = 0f)` 而不是 `Color.Transparent`: 必须保留 RGB ——
    // `glassSurfaceColor()`、顶栏返回键/action 的图标色都靠 `background.luminance()` 判断深浅;
    // `Color.Transparent` 的 luminance 是 0(黑), 会让浅色主题被误判成深色, 玻璃和图标全变色。
    val hasBg = AppState.backgroundImage.isNotBlank()
    val scheme = if (hasBg) base.copy(background = base.background.copy(alpha = 0f)) else base
    MaterialTheme(
        colorScheme = scheme,
        typography = MaterialTheme.typography,
        content = content,
    )
}
