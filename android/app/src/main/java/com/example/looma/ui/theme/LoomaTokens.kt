package com.example.looma.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

@Immutable
data class LoomaColors(
    val page: Color,
    val noteCanvas: Color,
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

@Immutable
data class LoomaTypography(
    val heroTitle: TextStyle,
    val pageTitle: TextStyle,
    val sectionTitle: TextStyle,
    val count: TextStyle,
    val cardTitle: TextStyle,
    val cardBody: TextStyle,
    val cardMeta: TextStyle,
    val homeNoteTitle: TextStyle,
    val homeNotePreview: LoomaPreviewTypography,
    val folderName: TextStyle,
    val breadcrumb: TextStyle,
    val body: TextStyle,
    val supporting: TextStyle,
    val avatar: TextStyle,
    val noteTitle: TextStyle,
    val noteBody: TextStyle,
    val noteHeadings: LoomaNoteHeadings,
    val noteCode: TextStyle,
)

@Immutable
data class LoomaNoteHeadings(
    val h1: TextStyle,
    val h2: TextStyle,
    val h3: TextStyle,
    val h4: TextStyle,
    val h5: TextStyle,
    val h6: TextStyle,
)

@Immutable
data class LoomaPreviewTypography(
    val body: TextStyle,
    val headings: LoomaNoteHeadings,
    val code: TextStyle,
)

fun loomaColors(scheme: ColorScheme): LoomaColors = LoomaColors(
    page = scheme.surfaceContainerLow,
    noteCanvas = if (scheme.surface.luminance() > 0.5f) Color.White else scheme.surface,
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
    heroTitle = base.headlineLargeEmphasized.copy(fontWeight = FontWeight(BRAND_HERO_WEIGHT)),
    pageTitle = base.titleLargeEmphasized,
    sectionTitle = base.titleMediumEmphasized,
    count = base.labelMediumEmphasized.copy(fontFeatureSettings = TABULAR_FIGURES),
    cardTitle = base.titleMedium,
    cardBody = base.bodySmall,
    cardMeta = base.labelMedium.copy(fontFeatureSettings = TABULAR_FIGURES),
    homeNoteTitle = base.titleMediumEmphasized.copy(
        fontSize = 18.sp,
        lineHeight = 26.sp,
        fontWeight = FontWeight.SemiBold,
    ),
    homeNotePreview = LoomaPreviewTypography(
        body = base.bodyLarge,
        headings = LoomaNoteHeadings(
            h1 = base.titleMediumEmphasized,
            h2 = base.bodyLargeEmphasized,
            h3 = base.bodyLargeEmphasized,
            h4 = base.bodyMediumEmphasized,
            h5 = base.bodyMediumEmphasized,
            h6 = base.bodySmall,
        ),
        code = base.bodyMedium.copy(fontFamily = FontFamily.Monospace),
    ),
    folderName = base.titleSmall,
    breadcrumb = base.bodyMedium,
    body = base.bodyLarge,
    supporting = base.bodyMedium,
    avatar = base.titleMediumEmphasized,
    noteTitle = base.headlineMediumEmphasized,
    noteBody = base.bodyLarge.copy(lineHeight = BODY_LINE_HEIGHT.sp),
    noteHeadings = LoomaNoteHeadings(
        h1 = base.headlineMediumEmphasized,
        h2 = base.headlineSmallEmphasized,
        h3 = base.titleLargeEmphasized,
        h4 = base.titleMediumEmphasized,
        h5 = base.titleSmallEmphasized,
        h6 = base.titleSmall,
    ),
    noteCode = base.bodyMedium.copy(fontFamily = FontFamily.Monospace, lineHeight = BODY_LINE_HEIGHT.sp),
)

internal const val TABULAR_FIGURES = "\"tnum\" 1"

private const val BRAND_HERO_WEIGHT = 750

internal const val BODY_LINE_HEIGHT = 26

val LocalLoomaColors = staticCompositionLocalOf { loomaColors(lightColorScheme()) }
val LocalLoomaTypography = staticCompositionLocalOf { loomaTypography(Typography()) }

object LoomaTokens {
    val colors: LoomaColors
        @Composable @ReadOnlyComposable get() = LocalLoomaColors.current

    val type: LoomaTypography
        @Composable @ReadOnlyComposable get() = LocalLoomaTypography.current
}
