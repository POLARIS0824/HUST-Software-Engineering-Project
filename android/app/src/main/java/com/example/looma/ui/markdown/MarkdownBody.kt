package com.example.looma.ui.markdown

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import com.example.looma.domain.markdown.MarkdownDocument
import com.example.looma.ui.theme.LoomaTokens
import com.mikepenz.markdown.compose.Markdown

/**
 * 全项目渲染笔记正文的唯一入口。
 *
 * 只吃 [MarkdownDocument]，不认识文件、路径和 ViewModel。复选框点击报原文坐标，
 * 由 feature 层决定怎么写回去。样式集中在 [loomaMarkdownTheme]，换渲染库只改这个包。
 */
@Composable
fun MarkdownBody(
    document: MarkdownDocument,
    onCheckboxToggle: (position: Int, checked: Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val currentToggle = rememberUpdatedState(onCheckboxToggle)
    val theme = loomaMarkdownTheme(base = LoomaTokens.type.markdownBody)
    val dividerColor = LoomaTokens.colors.divider
    val components = remember(theme, dividerColor, document.body, document.bodyStart) {
        loomaMarkdownComponents(
            theme = theme,
            compact = false,
            dividerColor = dividerColor,
            bodyStart = document.bodyStart,
            onCheckboxToggle = { position, checked -> currentToggle.value(position, checked) },
        )
    }

    Markdown(
        content = document.body,
        colors = theme.colors,
        typography = theme.typography,
        padding = theme.padding,
        components = components,
        // 勾选复选框会整段重解析；保留上一帧内容，页面不会闪成空白、滚动位置也不会被拉回顶部
        retainState = true,
        modifier = modifier.fillMaxWidth(),
    )
}
