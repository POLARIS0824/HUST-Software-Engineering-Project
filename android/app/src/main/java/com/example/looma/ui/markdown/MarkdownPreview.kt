package com.example.looma.ui.markdown

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import com.example.looma.ui.theme.LoomaTokens
import com.mikepenz.markdown.compose.Markdown

/**
 * 卡片里的只读 Markdown 预览。
 *
 * 和 [MarkdownBody] 共用一套样式，只是基准字号换成卡片正文、间距压小、不加载图片，
 * 并且复选框只读。内容为空时不渲染。
 */
@Composable
fun MarkdownPreview(
    markdown: String,
    modifier: Modifier = Modifier,
    textStyle: TextStyle = LoomaTokens.type.cardBody,
) {
    if (markdown.isBlank()) return
    val theme = loomaMarkdownTheme(base = textStyle, compact = true)
    val dividerColor = LoomaTokens.colors.divider
    val components = remember(theme, dividerColor) {
        loomaMarkdownComponents(
            theme = theme,
            compact = true,
            dividerColor = dividerColor,
            showImages = false,
        )
    }

    Markdown(
        content = markdown,
        colors = theme.colors,
        typography = theme.typography,
        padding = theme.padding,
        components = components,
        modifier = modifier.fillMaxWidth(),
    )
}
