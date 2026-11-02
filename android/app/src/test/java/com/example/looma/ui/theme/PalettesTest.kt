package com.example.looma.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.example.looma.data.settings.ThemePreset
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 预设主题不能只是好看：文字和底色必须够对比。
 *
 * 用 WCAG 的相对亮度比做检查，正文目标 4.5:1，主色按钮上的文字至少 3:1。
 */
class PalettesTest {

    private val presets = ThemePreset.entries.filter { it.seed != null }

    @Test
    fun `light presets keep readable contrast`() {
        presets.forEach { preset ->
            val scheme = presetScheme(preset, dark = false)
            assertContrast(preset, "onSurface", scheme.surface, scheme.onSurface, 4.5)
            assertContrast(preset, "onPrimary", scheme.primary, scheme.onPrimary, 3.0)
            assertContrast(
                preset,
                "onPrimaryContainer",
                scheme.primaryContainer,
                scheme.onPrimaryContainer,
                4.5,
            )
        }
    }

    @Test
    fun `dark presets keep readable contrast`() {
        presets.forEach { preset ->
            val scheme = presetScheme(preset, dark = true)
            assertContrast(preset, "onSurface", scheme.surface, scheme.onSurface, 4.5)
            assertContrast(preset, "onPrimary", scheme.primary, scheme.onPrimary, 3.0)
            assertContrast(
                preset,
                "onPrimaryContainer",
                scheme.primaryContainer,
                scheme.onPrimaryContainer,
                4.5,
            )
        }
    }

    private fun assertContrast(
        preset: ThemePreset,
        role: String,
        background: Color,
        foreground: Color,
        minimum: Double,
    ) {
        val ratio = contrast(background, foreground)
        assertTrue(
            "$preset $role contrast was $ratio, expected at least $minimum",
            ratio >= minimum,
        )
    }

    private fun contrast(a: Color, b: Color): Double {
        val first = a.luminance() + 0.05
        val second = b.luminance() + 0.05
        return if (first > second) first / second else second / first
    }
}
