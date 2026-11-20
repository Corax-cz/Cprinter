package com.cawzen.printserver.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * 配色。
 *
 * 底色是浅灰机壳，卡片是**白**的 —— 只有状态屏那条是深色。之前的版本把每张
 * 卡片都做成深灰，一屏里黑黑白白反复切，眼睛没有落点；现在深色只出现在一个
 * 地方（LCD 屏），它反而是最醒目的焦点，界面也干净。
 *
 * **颜色只用来表达状态**：绿=就绪、黄=警告、红=错误、灰=未连接/停用。
 * 装饰性的彩色一律不用，否则「红色」到底表示出错还是「主题色」就分不清了。
 */
@Immutable
data class Palette(
    val dark: Boolean,
    val bg: Color,
    val onBg: Color,
    val onBgDim: Color,
    /** 卡片底色。 */
    val panel: Color,
    /** 卡片内更浅一层（分段控件、输入框、次级按钮）。 */
    val panelAlt: Color,
    val onPanel: Color,
    val onPanelDim: Color,
    /** 1dp 描边/分隔线。 */
    val panelHair: Color,
    /** LCD 屏底。 */
    val lcdBg: Color,
    /** LCD 屏上的正常文字色（不是状态色）。 */
    val lcdFg: Color,
    val lcdFgDim: Color,
    val ok: Color,
    val warn: Color,
    val bad: Color,
    val idle: Color,
    /** 强调色（主按钮）。刻意不用彩色。 */
    val accent: Color,
    val onAccent: Color,
)

private val Light = Palette(
    dark = false,
    bg = Color(0xFFF3F4F6),
    onBg = Color(0xFF14171C),
    onBgDim = Color(0xFF6B7480),
    panel = Color(0xFFFFFFFF),
    panelAlt = Color(0xFFF1F3F6),
    onPanel = Color(0xFF14171C),
    onPanelDim = Color(0xFF6B7480),
    panelHair = Color(0xFFE3E7EC),
    lcdBg = Color(0xFF10161A),
    lcdFg = Color(0xFFE6EEE9),
    lcdFgDim = Color(0xFF86949B),
    ok = Color(0xFF0E9F52),
    warn = Color(0xFFB86E00),
    bad = Color(0xFFD22D20),
    idle = Color(0xFF98A2B3),
    accent = Color(0xFF1B2028),
    onAccent = Color(0xFFFFFFFF),
)

private val Dark = Palette(
    dark = true,
    bg = Color(0xFF0C0F13),
    onBg = Color(0xFFE9EFF6),
    onBgDim = Color(0xFF8B97A5),
    panel = Color(0xFF171B21),
    panelAlt = Color(0xFF222932),
    onPanel = Color(0xFFEDF2F8),
    onPanelDim = Color(0xFF8E9AA8),
    panelHair = Color(0xFF272E38),
    lcdBg = Color(0xFF05080A),
    lcdFg = Color(0xFFE7EFEA),
    lcdFgDim = Color(0xFF84929A),
    ok = Color(0xFF3BD37E),
    warn = Color(0xFFF0A93C),
    bad = Color(0xFFFF6B5E),
    idle = Color(0xFF6D7885),
    accent = Color(0xFFE9EFF6),
    onAccent = Color(0xFF12161B),
)

val LocalPalette = staticCompositionLocalOf { Light }

/** 短别名，每个 composable 里少写一行。 */
val cp: Palette
    @Composable get() = LocalPalette.current

/**
 * 状态色在 LCD 深底上的版本。
 *
 * 同一个绿在白卡上要够深（否则字发虚），在深底上又要够亮（否则像故障灯灭着）。
 * 用 lerp 往白里掺，比维护第二套色表省事，也不会两处改不同步。
 */
@Composable
fun Color.onLcd(): Color = lerp(this, Color.White, if (cp.dark) 0.10f else 0.38f)

@Composable
fun Tone.onLcd(): Color = color().onLcd()

private val CprinterTypography = Typography(
    headlineLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 28.sp,
        letterSpacing = (-0.4).sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        letterSpacing = (-0.2).sp,
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 18.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 15.sp,
    ),
    bodyLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 15.sp),
    bodyMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 13.5.sp),
    bodySmall = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 12.sp, lineHeight = 17.sp),
    labelLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 11.5.sp,
        letterSpacing = 0.3.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 10.sp,
        letterSpacing = 1.2.sp,
    ),
)

@Composable
fun CprinterTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val p = if (dark) Dark else Light
    val scheme = if (dark) {
        darkColorScheme(
            primary = p.accent,
            onPrimary = p.onAccent,
            background = p.bg,
            onBackground = p.onBg,
            surface = p.panel,
            onSurface = p.onPanel,
            surfaceVariant = p.panelAlt,
            onSurfaceVariant = p.onPanelDim,
            error = p.bad,
            onError = Color.White,
            outline = p.panelHair,
        )
    } else {
        lightColorScheme(
            primary = p.accent,
            onPrimary = p.onAccent,
            background = p.bg,
            onBackground = p.onBg,
            surface = p.panel,
            onSurface = p.onPanel,
            surfaceVariant = p.panelAlt,
            onSurfaceVariant = p.onPanelDim,
            error = p.bad,
            onError = Color.White,
            outline = p.panelHair,
        )
    }
    CompositionLocalProvider(LocalPalette provides p) {
        MaterialTheme(colorScheme = scheme, typography = CprinterTypography, content = content)
    }
}
