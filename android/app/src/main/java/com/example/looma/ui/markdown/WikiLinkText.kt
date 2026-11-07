package com.example.looma.ui.markdown

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import com.example.looma.domain.markdown.WikiLinks

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
