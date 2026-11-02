package com.example.looma.ui.markdown

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import com.example.looma.domain.markdown.WikiLinks

/**
 * 把已经渲染好的段落文本叠上双链标注。
 *
 * 只加样式与链接，**不改字**：字符串长度和字符位置都不动，
 * 所以复选框写回用的原文坐标不受影响。
 *
 * `[[..]]` 在渲染文本里逐字保留（渲染库不认识这个语法），
 * 因此在渲染坐标里直接找就行，不需要从原文坐标换算过来；
 * [codeBackground] 用来跳过行内代码，那里的 `[[..]]` 只是示例。
 */
internal fun AnnotatedString.withWikiLinks(
    codeBackground: Color,
    isResolved: (String) -> Boolean,
    onClick: (String) -> Unit,
    resolvedStyle: SpanStyle,
    missingStyle: SpanStyle,
): AnnotatedString {
    if ("[[" !in text) return this

    val excluded = spanStyles
        .filter { it.item.background == codeBackground }
        .map { it.start until it.end }
    val spans = WikiLinks.occurrences(text, excluded = excluded)
    if (spans.isEmpty()) return this

    return buildAnnotatedString {
        append(this@withWikiLinks)
        spans.forEachIndexed { index, span ->
            val style = if (isResolved(span.target)) resolvedStyle else missingStyle
            val start = span.range.first
            val end = span.range.last + 1
            addStyle(style, start, end)
            addLink(
                LinkAnnotation.Clickable(
                    tag = "wiki:${span.target}:$index",
                    styles = TextLinkStyles(style = style),
                    linkInteractionListener = { onClick(span.target) },
                ),
                start,
                end,
            )
        }
    }
}
