package com.example.looma.ui.markdown

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.sp
import com.example.looma.R
import com.example.looma.domain.wiki.WikiContext
import com.example.looma.ui.theme.LoomaTokens
import com.example.looma.ui.theme.LoomaTypography
import com.example.looma.ui.theme.LoomaPreviewTypography
import com.mikepenz.markdown.annotator.annotatorSettings
import com.mikepenz.markdown.annotator.buildMarkdownAnnotatedString
import com.mikepenz.markdown.compose.components.CurrentComponentsBridge
import com.mikepenz.markdown.compose.components.MarkdownComponent
import com.mikepenz.markdown.compose.components.MarkdownComponentModel
import com.mikepenz.markdown.compose.components.MarkdownComponents
import com.mikepenz.markdown.compose.LocalMarkdownInlineContent
import com.mikepenz.markdown.compose.LocalMarkdownPadding
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.compose.elements.MarkdownBulletList
import com.mikepenz.markdown.compose.elements.MarkdownOrderedList
import com.mikepenz.markdown.compose.elements.MarkdownText
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import com.mikepenz.markdown.model.MarkdownColors
import com.mikepenz.markdown.model.MarkdownPadding
import com.mikepenz.markdown.model.MarkdownTypography
import com.mikepenz.markdown.model.markdownAnnotatorConfig
import com.mikepenz.markdown.model.markdownInlineContent
import com.mikepenz.markdown.model.markdownPadding
import org.intellij.markdown.IElementType
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.findChildOfType
import org.intellij.markdown.ast.getTextInNode

@Immutable
data class LoomaMarkdownTheme(
    val colors: MarkdownColors,
    val typography: MarkdownTypography,
    val padding: MarkdownPadding,
)

@Immutable
internal data class LoomaMarkdownScale(
    val h1: TextStyle,
    val h2: TextStyle,
    val h3: TextStyle,
    val h4: TextStyle,
    val h5: TextStyle,
    val h6: TextStyle,
    val code: TextStyle,
    val inlineCode: TextStyle,
)

internal fun loomaMarkdownScale(
    type: LoomaTypography,
    compact: Boolean = false,
    preview: LoomaPreviewTypography? = null,
): LoomaMarkdownScale = LoomaMarkdownScale(
    h1 = if (compact) preview?.headings?.h1 ?: type.cardTitle else type.noteHeadings.h1,
    h2 = if (compact) preview?.headings?.h2 ?: type.folderName else type.noteHeadings.h2,
    h3 = if (compact) preview?.headings?.h3 ?: type.folderName else type.noteHeadings.h3,
    h4 = if (compact) preview?.headings?.h4 ?: type.folderName else type.noteHeadings.h4,
    h5 = if (compact) preview?.headings?.h5 ?: type.folderName else type.noteHeadings.h5,
    h6 = if (compact) preview?.headings?.h6 ?: type.folderName else type.noteHeadings.h6,
    code = if (compact) preview?.code ?: type.cardBody.copy(fontFamily = type.noteCode.fontFamily) else type.noteCode,
    inlineCode = if (compact) preview?.code ?: type.cardBody.copy(fontFamily = type.noteCode.fontFamily) else type.noteCode,
)

@Composable
fun loomaMarkdownTheme(
    base: TextStyle,
    compact: Boolean = false,
    preview: LoomaPreviewTypography? = null,
    textColor: Color = LoomaTokens.colors.title,
): LoomaMarkdownTheme {
    val colors = LoomaTokens.colors
    val type = LoomaTokens.type
    val scale = remember(type, compact, preview) { loomaMarkdownScale(type, compact, preview) }
    return LoomaMarkdownTheme(
        colors = markdownColor(
            text = textColor,
            codeBackground = colors.chip.copy(alpha = 0.6f),
            inlineCodeBackground = colors.chip.copy(alpha = 0.6f),
            dividerColor = colors.divider,
            tableBackground = colors.card,
            highlightText = colors.onChip,
            highlightBackground = colors.chip,
        ),
        typography = markdownTypography(
            text = base.copy(color = textColor),
            paragraph = base.copy(color = textColor),
            list = base.copy(color = textColor),
            ordered = base.copy(color = textColor),
            bullet = base.copy(color = textColor),
            quote = base.copy(fontStyle = FontStyle.Italic, color = textColor),
            code = scale.code.copy(color = textColor),
            inlineCode = scale.inlineCode.copy(color = textColor),
            table = base.copy(color = textColor),
            h1 = scale.h1.copy(color = textColor),
            h2 = scale.h2.copy(color = textColor),
            h3 = scale.h3.copy(color = textColor),
            h4 = scale.h4.copy(color = textColor),
            h5 = scale.h5.copy(color = textColor),
            h6 = scale.h6.copy(color = colors.muted),
        ),
        padding = markdownPadding(
            block = if (compact) 4.dp else 10.dp,
            list = if (compact) 2.dp else 4.dp,
            listItemTop = if (compact) 1.dp else 2.dp,
            listItemBottom = if (compact) 1.dp else 2.dp,
            listIndent = if (compact) 12.dp else 16.dp,
            codeBlock = PaddingValues(if (compact) 6.dp else 12.dp),
            blockQuote = PaddingValues(horizontal = if (compact) 8.dp else 12.dp),
        ),
    )
}

internal val loomaAnnotatorConfig = markdownAnnotatorConfig(eolAsNewLine = true)

internal fun loomaMarkdownComponents(
    theme: LoomaMarkdownTheme,
    compact: Boolean,
    dividerColor: Color,
    onCheckboxToggle: ((position: Int, checked: Boolean) -> Unit)? = null,
    bodyStart: Int = 0,
    showImages: Boolean = true,
    wiki: WikiContext? = null,
    onWikiLinkClick: (String) -> Unit = {},
    resolvedLink: SpanStyle = SpanStyle(),
    missingLink: SpanStyle = SpanStyle(),
    math: MathRenderState? = null,
): MarkdownComponents {
    val checkSize = if (compact) 14.dp else 18.dp
    val checkGap = if (compact) 6.dp else 9.dp
    val markerColumn = checkSize + checkGap
    val headingBottom = if (compact) 0.dp else 2.dp
    val links: WikiAnnotator? = wiki?.let { context ->
        val codeBackground = theme.colors.inlineCodeBackground
        { text: AnnotatedString ->
            text.withWikiLinks(
                codeBackground = codeBackground,
                isResolved = { target -> context.resolve(target) != null },
                onClick = onWikiLinkClick,
                resolvedStyle = resolvedLink,
                missingStyle = missingLink,
            )
        }
    }

    return markdownComponents(
        heading1 = heading(theme.typography.h1, MarkdownTokenTypes.ATX_CONTENT, if (compact) 10.dp else 20.dp, headingBottom, null, links, math),
        heading2 = heading(theme.typography.h2, MarkdownTokenTypes.ATX_CONTENT, if (compact) 8.dp else 16.dp, if (compact) 0.dp else 6.dp, if (compact) null else dividerColor, links, math),
        heading3 = heading(theme.typography.h3, MarkdownTokenTypes.ATX_CONTENT, if (compact) 6.dp else 12.dp, headingBottom, null, links, math),
        heading4 = heading(theme.typography.h4, MarkdownTokenTypes.ATX_CONTENT, if (compact) 4.dp else 8.dp, headingBottom, null, links, math),
        heading5 = heading(theme.typography.h5, MarkdownTokenTypes.ATX_CONTENT, if (compact) 4.dp else 8.dp, headingBottom, null, links, math),
        heading6 = heading(theme.typography.h6, MarkdownTokenTypes.ATX_CONTENT, if (compact) 4.dp else 8.dp, headingBottom, null, links, math),
        setextHeading1 = heading(theme.typography.h1, MarkdownTokenTypes.SETEXT_CONTENT, if (compact) 10.dp else 20.dp, headingBottom, null, links, math),
        setextHeading2 = heading(theme.typography.h2, MarkdownTokenTypes.SETEXT_CONTENT, if (compact) 8.dp else 16.dp, if (compact) 0.dp else 6.dp, if (compact) null else dividerColor, links, math),
        unorderedList = { model ->
            MarkdownBulletList(
                content = model.content,
                node = model.node,
                style = theme.typography.bullet,
                markerModifier = { Modifier.width(markerColumn) },
                listModifier = { Modifier.weight(1f) },
            )
        },
        orderedList = { model ->
            MarkdownOrderedList(
                content = model.content,
                node = model.node,
                style = theme.typography.ordered,
                markerModifier = { Modifier.width(markerColumn) },
                listModifier = { Modifier.weight(1f) },
            )
        },
        paragraph = { model -> LoomaMarkdownParagraph(model, links, math) },
        checkbox = { model ->
            CheckboxMarker(
                checked = model.isChecked(),
                lineHeight = lineHeightOf(theme.typography.list),
                size = checkSize,
                gap = checkGap,
                onToggle = onCheckboxToggle?.let { toggle ->
                    { checked: Boolean -> toggle(bodyStart + model.node.startOffset, checked) }
                },
            )
        },
        image = if (showImages) { model -> LoomaMarkdownImage(model) } else { _ -> },
        inlineImage = if (showImages) CurrentComponentsBridge.inlineImage else { _ -> },
    )
}

private typealias WikiAnnotator = (AnnotatedString) -> AnnotatedString

private fun heading(
    style: TextStyle,
    contentChildType: IElementType,
    top: Dp,
    bottom: Dp,
    borderColor: Color?,
    links: WikiAnnotator?,
    math: MathRenderState?,
): MarkdownComponent = { model ->
    val settings = annotatorSettings()
    val annotated = remember(model.content, model.node, settings, links, math) {
        val child = model.node.findChildOfType(contentChildType) ?: model.node
        buildAnnotatedString {
            buildMarkdownAnnotatedString(model.content, child, settings)
        }.withSynthItalic().let { links?.invoke(it) ?: it }
    }
    val headingFontSize = if (style.fontSize.isSpecified) style.fontSize else math?.fontSize
    val content: @Composable () -> Unit = {
        MarkdownText(
            content = annotated,
            node = model.node,
            style = style,
            modifier = Modifier
                .padding(top = top, bottom = bottom)
                .then(
                    if (borderColor != null) {
                        Modifier.drawBehind {
                            drawLine(
                                color = borderColor,
                                start = Offset(0f, size.height),
                                end = Offset(size.width, size.height),
                                strokeWidth = 1.dp.toPx(),
                            )
                        }
                    } else {
                        Modifier
                    },
                ),
            sourceContent = model.content,
        )
    }
    MathInlineScope(
        math = math,
        fontSize = headingFontSize,
        range = model.node.startOffset until model.node.endOffset,
        content = content,
    )
}

@Composable
private fun MathInlineScope(
    math: MathRenderState?,
    fontSize: TextUnit?,
    range: IntRange,
    content: @Composable () -> Unit,
) {
    if (math == null || fontSize == null || !math.hasInlineMath(range)) {
        content()
        return
    }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val available = if (maxWidth != Dp.Infinity) {
            with(LocalDensity.current) { maxWidth.toPx().toSp() }
        } else {
            null
        }
        val inline = rememberMathInlineContent(
            state = math,
            fontSize = fontSize,
            range = range,
            maxWidth = available,
        )
        if (inline.isEmpty()) {
            content()
        } else {
            CompositionLocalProvider(
                LocalMarkdownInlineContent provides markdownInlineContent(inline),
            ) {
                content()
            }
        }
    }
}

@Composable
private fun LoomaMarkdownParagraph(
    model: MarkdownComponentModel,
    links: WikiAnnotator?,
    math: MathRenderState?,
) {
    val block = math?.blockDisplay(model.node)
    if (block != null) {
        MathBlock(block, math.fontSize, PaddingValues(vertical = LocalMarkdownPadding.current.block))
        return
    }
    val settings = annotatorSettings()
    val annotated = remember(model.content, model.node, settings, links, math) {
        buildAnnotatedString {
            buildMarkdownAnnotatedString(model.content, model.node, settings)
        }.withSynthItalic().let { links?.invoke(it) ?: it }
    }
    if (annotated.isEmpty()) return
    MathInlineScope(
        math = math,
        fontSize = math?.fontSize,
        range = model.node.startOffset until model.node.endOffset,
    ) {
        MarkdownText(
            content = annotated,
            node = model.node,
            style = model.typography.paragraph,
            sourceContent = model.content,
        )
    }
}

private fun MarkdownComponentModel.isChecked(): Boolean =
    node.getTextInNode(content).contains("[x]")

@Composable
private fun CheckboxMarker(
    checked: Boolean,
    lineHeight: Dp,
    size: Dp,
    gap: Dp,
    onToggle: ((Boolean) -> Unit)?,
) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(percent = 30)
    Row(
        modifier = Modifier.heightIn(min = lineHeight),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val boxModifier = Modifier
            .size(size)
            .clip(shape)
            .border(1.5.dp, if (checked) colors.primary else colors.outline, shape)
            .background(if (checked) colors.primary else Color.Transparent, shape)
            .then(
                if (onToggle != null) {
                    Modifier.toggleable(value = checked, role = Role.Checkbox, onValueChange = onToggle)
                } else {
                    Modifier
                },
            )
        Box(boxModifier, contentAlignment = Alignment.Center) {
            if (checked) {
                Icon(
                    painter = painterResource(R.drawable.ic_check),
                    contentDescription = null,
                    tint = colors.onPrimary,
                    modifier = Modifier.size(size * 0.72f),
                )
            }
        }
        Spacer(Modifier.width(gap))
    }
}

@Composable
private fun lineHeightOf(style: TextStyle): Dp {
    val resolved = if (style.lineHeight.isSp) style.lineHeight.value else style.fontSize.value * 1.5f
    return with(LocalDensity.current) { resolved.sp.toDp() }
}
