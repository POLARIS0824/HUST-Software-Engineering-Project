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

/**
 * FitLog ui 分支的 Google Sans Flex 字重、字宽、光学尺寸与圆润度设置
 * 没有 google_sans_flex.ttf 时使用系统 SansSerif，不影响编译
 * 将字体文件放进 res/font 后无需修改 Kotlin 源码
 */
private fun TextStyle.loomaFlex(fontId: Int?, emphasized: Boolean = false, heading: Boolean = false): TextStyle {
    val weight = if (emphasized) {
        if (heading) 750 else 700
    } else {
        fontWeight?.weight ?: 400
    }
    if (fontId == null) {
        return copy(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight(weight))
    }

    fun flexFont(axisWeight: Int, style: FontStyle, italic: Boolean): Font = Font(
        resId = fontId,
        weight = FontWeight(axisWeight),
        style = style,
        variationSettings = FontVariation.Settings(
            FontVariation.weight(axisWeight),
            FontVariation.width(if (heading) 90f else 100f),
            FontVariation.opticalSizing(fontSize),
            FontVariation.Setting("ROND", if (heading) 80f else 0f),
            FontVariation.Setting("slnt", if (italic) -10f else 0f),
        ),
    )

    // 单一字重的 FontFamily 会让 Markdown 的 **粗体** / *斜体* span 找不到对应字面，
    // 这里补上 700 与斜体条目，渲染器才会真的换字重、换字形
    val weights = if (weight >= 700) listOf(weight) else listOf(weight, 700)
    val fonts = buildList {
        for (axisWeight in weights) {
            add(flexFont(axisWeight, FontStyle.Normal, italic = false))
            add(flexFont(axisWeight, FontStyle.Italic, italic = true))
        }
    }
    return copy(fontFamily = FontFamily(*fonts.toTypedArray()), fontWeight = FontWeight(weight))
}

/**
 * 只有在编译资源中存在 google_sans_flex 时才装载该字体
 * 使用资源名查询是为了使下载包本身不需要携带字体文件
 */
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
            displayLargeEmphasized = base.displayLargeEmphasized.loomaFlex(id, true, true),
            displayMediumEmphasized = base.displayMediumEmphasized.loomaFlex(id, true, true),
            displaySmallEmphasized = base.displaySmallEmphasized.loomaFlex(id, true, true),
            headlineLargeEmphasized = base.headlineLargeEmphasized.loomaFlex(id, true, true),
            headlineMediumEmphasized = base.headlineMediumEmphasized.loomaFlex(id, true, true),
            headlineSmallEmphasized = base.headlineSmallEmphasized.loomaFlex(id, true, true),
            titleLargeEmphasized = base.titleLargeEmphasized.loomaFlex(id, true),
            titleMediumEmphasized = base.titleMediumEmphasized.loomaFlex(id, true),
            titleSmallEmphasized = base.titleSmallEmphasized.loomaFlex(id, true),
            bodyLargeEmphasized = base.bodyLargeEmphasized.loomaFlex(id, true),
            bodyMediumEmphasized = base.bodyMediumEmphasized.loomaFlex(id, true),
            bodySmallEmphasized = base.bodySmallEmphasized.loomaFlex(id, true),
            labelLargeEmphasized = base.labelLargeEmphasized.loomaFlex(id, true),
            labelMediumEmphasized = base.labelMediumEmphasized.loomaFlex(id, true),
            labelSmallEmphasized = base.labelSmallEmphasized.loomaFlex(id, true),
        )
    }
}
