package com.example.looma.ui.markdown

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextGeometricTransform

/**
 * 斜体的补足。
 *
 * 中文字形来自系统回退字体，那份字体没有斜体字形，`fontStyle = Italic` 对它完全没有效果。
 * SpanStyle 在当前的 Compose 里带 textGeometricTransform，所以给已有的斜体区间补一个
 * 绘制期倾斜，中英文都能看出斜体。
 */
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
