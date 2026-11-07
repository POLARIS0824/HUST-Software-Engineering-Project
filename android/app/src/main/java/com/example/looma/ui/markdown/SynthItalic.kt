package com.example.looma.ui.markdown

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextGeometricTransform

private val ItalicSkew = TextGeometricTransform(skewX = -0.2f)

internal fun AnnotatedString.withSynthItalic(): AnnotatedString {
    if (spanStyles.none { it.item.fontStyle == FontStyle.Italic }) return this
    val styles = spanStyles.map { range ->
        if (range.item.fontStyle == FontStyle.Italic) {
            range.copy(
                item = range.item.merge(SpanStyle(textGeometricTransform = ItalicSkew)),
            )
        } else {
            range
        }
    }
    return AnnotatedString(text = text, spanStyles = styles, paragraphStyles = paragraphStyles)
}
