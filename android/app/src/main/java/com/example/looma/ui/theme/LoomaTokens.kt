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

/**
 * 首页与核心界面的排版刻度。
 *
 * 优先使用 Material 3 角色；为笔记内容调整的字号、行高和字重在对应语义下注明。
 */
@Immutable
data class LoomaTypography(
    /**
     * headline large emphasized 叠品牌字重。
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
    /** 首页网格笔记标题。基于 title medium emphasized；18sp/26sp、Semibold 是参考图的设计取值。 */
    val homeNoteTitle: TextStyle,
    /** 首页网格正文、Markdown 标题和代码；与 Recent 的紧凑预览分开。 */
    val homeNotePreview: LoomaPreviewTypography,
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
    /** headline medium emphasized，笔记独立标题，编辑与阅读共用。 */
    val noteTitle: TextStyle,
    /** body large，笔记正文与阅读段落共用。26sp 行高是 Looma 的阅读选择。 */
    val noteBody: TextStyle,
    /** Markdown 文档层级，不用于页面导航标题。 */
    val noteHeadings: LoomaNoteHeadings,
    /** body medium，等宽代码，保留正文阅读行高。 */
    val noteCode: TextStyle,
)

@Immutable
data class LoomaNoteHeadings(
    /** 文档一级标题。阅读用 headline medium emphasized；网格摘录用 title medium emphasized。 */
    val h1: TextStyle,
    /** 文档二级标题。阅读用 headline small emphasized；网格摘录用 body large emphasized。 */
    val h2: TextStyle,
    /** 文档三级标题。阅读用 title large emphasized；网格摘录用 body large emphasized。 */
    val h3: TextStyle,
    /** 文档四级标题。阅读用 title medium emphasized；网格摘录用 body medium emphasized。 */
    val h4: TextStyle,
    /** 文档五级标题。阅读用 title small emphasized；网格摘录用 body medium emphasized。 */
    val h5: TextStyle,
    /** 文档六级标题。阅读用 title small；网格摘录用 body small，均用次要文字颜色。 */
    val h6: TextStyle,
)

@Immutable
data class LoomaPreviewTypography(
    /** body large，首页可扫读的内容摘录。 */
    val body: TextStyle,
    /** 摘录内的文档层级，全部服从卡片正文的阅读密度。 */
    val headings: LoomaNoteHeadings,
    /** body medium，等宽代码；不使用页面正文的长阅读行高。 */
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
