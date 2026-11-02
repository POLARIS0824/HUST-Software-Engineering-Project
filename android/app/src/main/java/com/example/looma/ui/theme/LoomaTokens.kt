package com.example.looma.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

@Immutable
data class LoomaColors(
    val page: Color,
    val card: Color,
    val sheet: Color,
    val searchField: Color,
    val title: Color,
    val body: Color,
    val muted: Color,
    val accent: Color,
    val accentContainer: Color,
    val avatarRing: Color,
    val chip: Color,
    val onChip: Color,
    val divider: Color,
    val error: Color,
)

/** 首页的排版刻度，字号与行高集中在这里调整 */
@Immutable
data class LoomaTypography(
    val sectionTitle: TextStyle,
    val count: TextStyle,
    val cardTitle: TextStyle,
    val cardBody: TextStyle,
    val cardMeta: TextStyle,
    val folderName: TextStyle,
    val breadcrumb: TextStyle,
    val sheetTitle: TextStyle,
    val sheetOption: TextStyle,
    val searchText: TextStyle,
    val emptyState: TextStyle,
    val avatar: TextStyle,
)

fun loomaColors(scheme: ColorScheme): LoomaColors = LoomaColors(
    page = scheme.surfaceContainerLow,
    card = scheme.surfaceBright,
    sheet = scheme.surfaceContainerLow,
    searchField = scheme.surfaceBright,
    title = scheme.onSurface,
    body = scheme.onSurfaceVariant,
    muted = scheme.onSurfaceVariant,
    accent = scheme.primary,
    accentContainer = scheme.primaryContainer,
    avatarRing = scheme.primary,
    chip = scheme.secondaryContainer,
    onChip = scheme.onSecondaryContainer,
    divider = scheme.outlineVariant,
    error = scheme.error,
)

fun loomaTypography(base: Typography): LoomaTypography = LoomaTypography(
    sectionTitle = base.titleMedium.metric(size = 16, line = 24, weight = FontWeight.SemiBold),
    count = base.labelMedium.metric(size = 12, line = 16),
    cardTitle = base.titleMedium.metric(size = 16, line = 22, weight = FontWeight.SemiBold),
    cardBody = base.bodySmall.metric(size = 12, line = 18),
    cardMeta = base.labelMedium.metric(size = 12, line = 16),
    folderName = base.titleSmall.metric(size = 15, line = 20, weight = FontWeight.Medium),
    breadcrumb = base.bodyMedium.metric(size = 13, line = 18),
    sheetTitle = base.titleLarge.metric(size = 22, line = 28, weight = FontWeight.Medium),
    sheetOption = base.bodyLarge.metric(size = 16, line = 22),
    searchText = base.bodyLarge.metric(size = 16, line = 22),
    emptyState = base.bodySmall.metric(size = 14, line = 20),
    avatar = base.titleMedium.metric(size = 16, line = 20, weight = FontWeight.SemiBold),
)

private fun TextStyle.metric(size: Int, line: Int, weight: FontWeight? = null): TextStyle =
    copy(
        fontSize = size.sp,
        lineHeight = line.sp,
        fontWeight = weight ?: fontWeight,
    )

/** 默认值只服务于没有 LoomaTheme 包裹的 Preview 与测试 */
val LocalLoomaColors = staticCompositionLocalOf { loomaColors(lightColorScheme()) }
val LocalLoomaTypography = staticCompositionLocalOf { loomaTypography(Typography()) }

object LoomaTokens {
    val colors: LoomaColors
        @Composable @ReadOnlyComposable get() = LocalLoomaColors.current

    val type: LoomaTypography
        @Composable @ReadOnlyComposable get() = LocalLoomaTypography.current
}
