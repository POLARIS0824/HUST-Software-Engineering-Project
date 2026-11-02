package com.example.looma.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.example.looma.data.settings.ThemePreset

/**
 * 预设主题的调色板。
 *
 * 每套主题只给一个种子色，其余色位按 Material 的色调刻度推导（主色 40、容器 90、文字 10 等），
 * 色相跟随种子色，饱和度按角色递减，所以换一套主题时整屏都跟着变，而不是只换一个按钮。
 * 深色主题把明度刻度翻到 80 / 30 / 90，保持同一套色相关系。
 *
 * 文字色不是硬编码的黑白：每个前景色都按 WCAG 对比度挑，色相偏亮的种子（比如青绿）也能通过
 * [PalettesTest] 的 4.5:1 检查。
 */
internal fun presetScheme(preset: ThemePreset, dark: Boolean): ColorScheme {
    val seed = preset.seed ?: return if (dark) LoomaDarkColors else LoomaLightColors
    val hue = seed.hue()
    val saturation = seed.saturation().coerceIn(0.18f, 0.75f)
    return if (dark) darkScheme(hue, saturation) else lightScheme(hue, saturation)
}

private val NearBlack = Color(0xFF141218)

private fun lightScheme(hue: Float, saturation: Float): ColorScheme {
    val secondaryHue = (hue + 18f).mod(360f)
    val tertiaryHue = (hue + 62f).mod(360f)
    val primary = tone(hue, saturation, 0.40f)
    val primaryContainer = tone(hue, saturation * 0.90f, 0.90f)
    val secondary = tone(secondaryHue, saturation * 0.55f, 0.40f)
    val tertiary = tone(tertiaryHue, saturation * 0.85f, 0.40f)
    val error = tone(4f, 0.70f, 0.41f)
    val surface = neutral(hue, 0.985f)
    return lightColorScheme(
        primary = primary,
        onPrimary = bestOn(primary),
        primaryContainer = primaryContainer,
        onPrimaryContainer = bestOn(primaryContainer, tinted = 0.12f, hue = hue, saturation = saturation),
        inversePrimary = tone(hue, saturation, 0.80f),
        secondary = secondary,
        onSecondary = bestOn(secondary),
        secondaryContainer = tone(secondaryHue, saturation * 0.55f, 0.90f),
        onSecondaryContainer = bestOn(
            tone(secondaryHue, saturation * 0.55f, 0.90f),
            tinted = 0.12f,
            hue = secondaryHue,
            saturation = saturation * 0.55f,
        ),
        tertiary = tertiary,
        onTertiary = bestOn(tertiary),
        tertiaryContainer = tone(tertiaryHue, saturation * 0.85f, 0.90f),
        onTertiaryContainer = bestOn(
            tone(tertiaryHue, saturation * 0.85f, 0.90f),
            tinted = 0.12f,
            hue = tertiaryHue,
            saturation = saturation * 0.85f,
        ),
        background = surface,
        onBackground = neutral(hue, 0.10f),
        surface = surface,
        onSurface = neutral(hue, 0.10f),
        surfaceVariant = neutral(hue, 0.90f),
        onSurfaceVariant = neutral(hue, 0.30f),
        surfaceTint = primary,
        inverseSurface = neutral(hue, 0.20f),
        inverseOnSurface = neutral(hue, 0.95f),
        outline = neutral(hue, 0.50f),
        outlineVariant = neutral(hue, 0.80f),
        surfaceBright = neutral(hue, 1f),
        surfaceDim = neutral(hue, 0.87f),
        surfaceContainerLowest = neutral(hue, 1f),
        surfaceContainerLow = neutral(hue, 0.965f),
        surfaceContainer = neutral(hue, 0.94f),
        surfaceContainerHigh = neutral(hue, 0.915f),
        surfaceContainerHighest = neutral(hue, 0.89f),
        error = error,
        onError = bestOn(error),
        errorContainer = tone(4f, 0.90f, 0.92f),
        onErrorContainer = bestOn(tone(4f, 0.90f, 0.92f), tinted = 0.12f, hue = 4f, saturation = 0.8f),
        scrim = Color.Black,
    )
}

private fun darkScheme(hue: Float, saturation: Float): ColorScheme {
    val secondaryHue = (hue + 18f).mod(360f)
    val tertiaryHue = (hue + 62f).mod(360f)
    val primary = tone(hue, saturation, 0.80f)
    val primaryContainer = tone(hue, saturation, 0.30f)
    val secondary = tone(secondaryHue, saturation * 0.55f, 0.80f)
    val tertiary = tone(tertiaryHue, saturation * 0.85f, 0.80f)
    val error = tone(4f, 0.70f, 0.80f)
    val surface = neutral(hue, 0.06f)
    return darkColorScheme(
        primary = primary,
        onPrimary = bestOn(primary),
        primaryContainer = primaryContainer,
        onPrimaryContainer = bestOn(primaryContainer, tinted = 0.90f, hue = hue, saturation = saturation),
        inversePrimary = tone(hue, saturation, 0.40f),
        secondary = secondary,
        onSecondary = bestOn(secondary),
        secondaryContainer = tone(secondaryHue, saturation * 0.55f, 0.30f),
        onSecondaryContainer = bestOn(
            tone(secondaryHue, saturation * 0.55f, 0.30f),
            tinted = 0.90f,
            hue = secondaryHue,
            saturation = saturation * 0.55f,
        ),
        tertiary = tertiary,
        onTertiary = bestOn(tertiary),
        tertiaryContainer = tone(tertiaryHue, saturation * 0.85f, 0.30f),
        onTertiaryContainer = bestOn(
            tone(tertiaryHue, saturation * 0.85f, 0.30f),
            tinted = 0.90f,
            hue = tertiaryHue,
            saturation = saturation * 0.85f,
        ),
        background = surface,
        onBackground = neutral(hue, 0.92f),
        surface = surface,
        onSurface = neutral(hue, 0.92f),
        surfaceVariant = neutral(hue, 0.30f),
        onSurfaceVariant = neutral(hue, 0.82f),
        surfaceTint = primary,
        inverseSurface = neutral(hue, 0.90f),
        inverseOnSurface = neutral(hue, 0.20f),
        outline = neutral(hue, 0.60f),
        outlineVariant = neutral(hue, 0.35f),
        surfaceBright = neutral(hue, 0.22f),
        surfaceDim = neutral(hue, 0.04f),
        surfaceContainerLowest = neutral(hue, 0.04f),
        surfaceContainerLow = neutral(hue, 0.09f),
        surfaceContainer = neutral(hue, 0.12f),
        surfaceContainerHigh = neutral(hue, 0.15f),
        surfaceContainerHighest = neutral(hue, 0.20f),
        error = error,
        onError = bestOn(error),
        errorContainer = tone(4f, 0.70f, 0.30f),
        onErrorContainer = bestOn(tone(4f, 0.70f, 0.30f), tinted = 0.90f, hue = 4f, saturation = 0.7f),
        scrim = Color.Black,
    )
}

/**
 * 在给定底色上挑一个够清楚的前景色。
 *
 * 先试同色相的深/浅文字，达不到 4.5:1 就退到黑或白；
 * 任何底色对黑或白至少有一个 ≥ 4.5，所以这个函数总有解。
 */
private fun bestOn(
    background: Color,
    tinted: Float? = null,
    hue: Float = 0f,
    saturation: Float = 0f,
): Color {
    if (tinted != null) {
        val candidate = tone(hue, saturation, tinted)
        // 留一点余量：色相接近青色时，同色相文字刚好卡在 4.5 的边缘
        if (contrast(background, candidate) >= 5.0) return candidate
    }
    // 纯黑和纯白里总有一个达到 4.58:1，所以兜底一定满足对比度要求
    return listOf(Color.White, NearBlack, Color.Black)
        .maxBy { contrast(background, it) }
}

private fun contrast(a: Color, b: Color): Double {
    val first = a.luminance() + 0.05
    val second = b.luminance() + 0.05
    return if (first > second) first / second else second / first
}

/** 有彩色：色相不动的明度台阶 */
private fun tone(hue: Float, saturation: Float, lightness: Float): Color =
    Color.hsl(hue, saturation, lightness)

/** 中性色：保留一点种子色相，界面才有主题感而不是纯灰 */
private fun neutral(hue: Float, lightness: Float): Color =
    Color.hsl(hue, (0.10f * (1f - lightness) + 0.04f), lightness)

private fun Color.hue(): Float = rgbToHsl(red, green, blue).first
private fun Color.saturation(): Float = rgbToHsl(red, green, blue).second

/** 种子色可能不是纯色相，转换到 HSL 才好在同一色相上取明度台阶 */
private fun rgbToHsl(r: Float, g: Float, b: Float): Pair<Float, Float> {
    val max = maxOf(r, g, b)
    val min = minOf(r, g, b)
    val delta = max - min
    val lightness = (max + min) / 2f
    val saturation = if (delta == 0f) {
        0f
    } else {
        delta / (1f - kotlin.math.abs(2f * lightness - 1f))
    }
    val hue = when {
        delta == 0f -> 0f
        max == r -> 60f * (((g - b) / delta).mod(6f))
        max == g -> 60f * (((b - r) / delta) + 2f)
        else -> 60f * (((r - g) / delta) + 4f)
    }
    return hue.mod(360f) to saturation.coerceIn(0f, 1f)
}
