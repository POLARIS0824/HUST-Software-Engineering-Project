package com.example.looma.ui.markdown

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Checkbox
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import com.example.looma.ui.theme.LoomaTokens
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.compose.elements.MarkdownCheckBox
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import com.mikepenz.markdown.model.markdownPadding

/**
 * 卡片里的只读 Markdown 预览。
 *
 * 和 [MarkdownBody] 一样是全项目调用渲染库的地方，区别是不处理复选框点击、
 * 不加载图片，并把标题和段间距压到卡片能承受的尺度。内容为空时不渲染。
 */
@Composable
fun MarkdownPreview(
    markdown: String,
    modifier: Modifier = Modifier,
    textStyle: TextStyle = LoomaTokens.type.cardBody,
) {
    if (markdown.isBlank()) return
    Markdown(
        content = markdown,
        colors = markdownColor(),
        typography = markdownTypography(
            text = textStyle,
            paragraph = textStyle,
            list = textStyle,
            ordered = textStyle,
            bullet = textStyle,
            quote = textStyle,
            code = textStyle,
            inlineCode = textStyle,
            h1 = LoomaTokens.type.cardTitle,
            h2 = LoomaTokens.type.cardTitle,
            h3 = LoomaTokens.type.cardTitle,
            h4 = LoomaTokens.type.cardTitle,
            h5 = LoomaTokens.type.cardTitle,
            h6 = LoomaTokens.type.cardTitle,
        ),
        padding = markdownPadding(
            block = 4.dp,
            list = 2.dp,
            listItemTop = 0.dp,
            listItemBottom = 0.dp,
        ),
        components = markdownComponents(
            image = { _ -> },
            inlineImage = { _ -> },
            checkbox = { model ->
                MarkdownCheckBox(
                    content = model.content,
                    node = model.node,
                    style = model.typography.text,
                    checkedIndicator = { checked, indicatorModifier ->
                        Checkbox(
                            checked = checked,
                            onCheckedChange = null,
                            modifier = indicatorModifier,
                        )
                    },
                )
            },
        ),
        modifier = modifier.fillMaxWidth(),
    )
}
