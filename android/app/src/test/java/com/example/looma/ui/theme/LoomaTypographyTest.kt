package com.example.looma.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.font.FontWeight
import org.junit.Assert.assertEquals
import org.junit.Test

class LoomaTypographyTest {

    private val base = Typography()
    private val tokens = loomaTypography(base)

    @Test
    fun `tokens project the m3 roles`() {
        assertEquals(base.titleLargeEmphasized, tokens.pageTitle)
        assertEquals(base.titleMediumEmphasized, tokens.sectionTitle)
        assertEquals(base.titleMediumEmphasized, tokens.avatar)
        assertEquals(base.titleMedium, tokens.cardTitle)
        assertEquals(base.titleSmall, tokens.folderName)
        assertEquals(base.bodyLarge, tokens.body)
        assertEquals(base.bodyMedium, tokens.breadcrumb)
        assertEquals(base.bodyMedium, tokens.supporting)
        assertEquals(base.bodySmall, tokens.cardBody)
    }

    @Test
    fun `counters keep their role weight and ask for tabular figures`() {
        assertEquals(base.labelMediumEmphasized.fontWeight, tokens.count.fontWeight)
        assertEquals(TABULAR_FIGURES, tokens.count.fontFeatureSettings)
        assertEquals(base.labelMedium.fontWeight, tokens.cardMeta.fontWeight)
        assertEquals(TABULAR_FIGURES, tokens.cardMeta.fontFeatureSettings)
    }

    @Test
    fun `hero title keeps the brand weight on top of the m3 role`() {
        assertEquals(base.headlineLargeEmphasized.fontSize, tokens.heroTitle.fontSize)
        assertEquals(base.headlineLargeEmphasized.lineHeight, tokens.heroTitle.lineHeight)
        assertEquals(FontWeight(750), tokens.heroTitle.fontWeight)
    }

    @Test
    fun `font family layer never invents a weight`() {
        val styles = listOf(
            base.displayLargeEmphasized,
            base.titleLargeEmphasized,
            base.titleMediumEmphasized,
            base.bodyLargeEmphasized,
            base.labelMediumEmphasized,
            base.titleMedium,
            base.bodyLarge,
        )
        styles.forEach { style ->
            assertEquals(
                "loomaFlex changed the weight of ${style.fontSize}",
                style.fontWeight,
                style.loomaFlex(fontId = null).fontWeight,
            )
        }
    }
}
