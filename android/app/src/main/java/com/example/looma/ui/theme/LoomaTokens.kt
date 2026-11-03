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

/**
 * 首页与核心界面的排版刻度。
 *
 * 每个字段就是一个 Material 3 角色，这里不再二次调字号、行高和字重；
 * 角色名写在各字段的注释里，方便和 m3.material.io 的类型刻度对表。
 * 需要新样式时先找对应的 M3 角色，找不到再讨论要不要加字段。
 */
@Immutable
data class LoomaTypography(
    /**
     * headline large emphasized 叠品牌字重。唯一一处偏离 M3 的地方：
     * 引导页标题要压得住整屏，M3 的 emphasized 只到 Medium。
     */
    val heroTitle: TextStyle,
    /** title large emphasized */
    val pageTitle: TextStyle,
    /** title medium emphasized */
    val sectionTitle: TextStyle,
    /** label medium emphasized，配等宽数字 */
    val count: TextStyle,
    /** title medium */
    val cardTitle: TextStyle,
    /** body small */
    val cardBody: TextStyle,
    /** label medium，配等宽数字 */
    val cardMeta: TextStyle,
    /** title small */
    val folderName: TextStyle,
    /** body medium，导航路径 */
    val breadcrumb: TextStyle,
    /** body large，正文、搜索输入、列表选项 */
    val body: TextStyle,
    /** body medium，空状态与次要提示 */
    val supporting: TextStyle,
    /** title medium emphasized，头像首字母 */
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
    heroTitle = base.headlineLargeEmphasized.copy(fontWeight = FontWeight(BRAND_HERO_WEIGHT)),
    pageTitle = base.titleLargeEmphasized,
    sectionTitle = base.titleMediumEmphasized,
    count = base.labelMediumEmphasized.copy(fontFeatureSettings = TABULAR_FIGURES),
    cardTitle = base.titleMedium,
    cardBody = base.bodySmall,
    cardMeta = base.labelMedium.copy(fontFeatureSettings = TABULAR_FIGURES),
    folderName = base.titleSmall,
    breadcrumb = base.bodyMedium,
    body = base.bodyLarge,
    supporting = base.bodyMedium,
    avatar = base.titleMediumEmphasized,
)

/** 缺省值在 Android 上按 1 处理，这里写全是为了不依赖那个约定 */
internal const val TABULAR_FIGURES = "\"tnum\" 1"

/** Google Sans Flex 的 wght 轴到 1000，750 比 M3 的 Bold 再重半档 */
private const val BRAND_HERO_WEIGHT = 750

/** 正文行高：编辑态与 Markdown 阅读态共用，切换模式时文字不会上下跳 */
internal const val BODY_LINE_HEIGHT = 26

/** 默认值只服务于没有 LoomaTheme 包裹的 Preview 与测试 */
val LocalLoomaColors = staticCompositionLocalOf { loomaColors(lightColorScheme()) }
val LocalLoomaTypography = staticCompositionLocalOf { loomaTypography(Typography()) }

object LoomaTokens {
    val colors: LoomaColors
        @Composable @ReadOnlyComposable get() = LocalLoomaColors.current

    val type: LoomaTypography
        @Composable @ReadOnlyComposable get() = LocalLoomaTypography.current
}
