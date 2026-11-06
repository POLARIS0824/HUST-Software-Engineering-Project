package com.example.looma.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight

private const val HEADING_WIDTH = 90f
private const val TEXT_WIDTH = 100f
private const val HEADING_ROUND = 80f
private const val TEXT_ROUND = 0f

internal fun TextStyle.loomaFlex(fontId: Int?, heading: Boolean = false): TextStyle {
    val weight = fontWeight?.weight ?: 400
    if (fontId == null) return copy(fontFamily = FontFamily.SansSerif)

    fun flexFont(axisWeight: Int, style: FontStyle, italic: Boolean): Font = Font(
        resId = fontId,
        weight = FontWeight(axisWeight),
        style = style,
        variationSettings = FontVariation.Settings(
            FontVariation.weight(axisWeight),
            FontVariation.width(if (heading) HEADING_WIDTH else TEXT_WIDTH),
            FontVariation.opticalSizing(fontSize),
            FontVariation.Setting("ROND", if (heading) HEADING_ROUND else TEXT_ROUND),
            FontVariation.Setting("slnt", if (italic) -10f else 0f),
        ),
    )

    val weights = if (weight >= 700) listOf(weight) else listOf(weight, 700)
    val fonts = buildList {
        for (axisWeight in weights) {
            add(flexFont(axisWeight, FontStyle.Normal, italic = false))
            add(flexFont(axisWeight, FontStyle.Italic, italic = true))
        }
    }
    return copy(fontFamily = FontFamily(*fonts.toTypedArray()))
}

@Composable
fun rememberLoomaTypography(): Typography {
    val context = LocalContext.current
    val id = remember(context) {
        context.resources.getIdentifier("google_sans_flex", "font", context.packageName)
    }.takeIf { it != 0 }
    return remember(id) {
        val base = Typography()
        base.copy(
            displayLarge = base.displayLarge.loomaFlex(id, heading = true),
            displayMedium = base.displayMedium.loomaFlex(id, heading = true),
            displaySmall = base.displaySmall.loomaFlex(id, heading = true),
            headlineLarge = base.headlineLarge.loomaFlex(id, heading = true),
            headlineMedium = base.headlineMedium.loomaFlex(id, heading = true),
            headlineSmall = base.headlineSmall.loomaFlex(id, heading = true),
            titleLarge = base.titleLarge.loomaFlex(id),
            titleMedium = base.titleMedium.loomaFlex(id),
            titleSmall = base.titleSmall.loomaFlex(id),
            bodyLarge = base.bodyLarge.loomaFlex(id),
            bodyMedium = base.bodyMedium.loomaFlex(id),
            bodySmall = base.bodySmall.loomaFlex(id),
            labelLarge = base.labelLarge.loomaFlex(id),
            labelMedium = base.labelMedium.loomaFlex(id),
            labelSmall = base.labelSmall.loomaFlex(id),
            displayLargeEmphasized = base.displayLargeEmphasized.loomaFlex(id, heading = true),
            displayMediumEmphasized = base.displayMediumEmphasized.loomaFlex(id, heading = true),
            displaySmallEmphasized = base.displaySmallEmphasized.loomaFlex(id, heading = true),
            headlineLargeEmphasized = base.headlineLargeEmphasized.loomaFlex(id, heading = true),
            headlineMediumEmphasized = base.headlineMediumEmphasized.loomaFlex(id, heading = true),
            headlineSmallEmphasized = base.headlineSmallEmphasized.loomaFlex(id, heading = true),
            titleLargeEmphasized = base.titleLargeEmphasized.loomaFlex(id),
            titleMediumEmphasized = base.titleMediumEmphasized.loomaFlex(id),
            titleSmallEmphasized = base.titleSmallEmphasized.loomaFlex(id),
            bodyLargeEmphasized = base.bodyLargeEmphasized.loomaFlex(id),
            bodyMediumEmphasized = base.bodyMediumEmphasized.loomaFlex(id),
            bodySmallEmphasized = base.bodySmallEmphasized.loomaFlex(id),
            labelLargeEmphasized = base.labelLargeEmphasized.loomaFlex(id),
            labelMediumEmphasized = base.labelMediumEmphasized.loomaFlex(id),
            labelSmallEmphasized = base.labelSmallEmphasized.loomaFlex(id),
        )
    }
}
