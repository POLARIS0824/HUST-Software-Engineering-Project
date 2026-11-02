package com.example.looma.ui.markdown

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.style.TextDecoration
import com.example.looma.domain.markdown.MarkdownDocument
import com.example.looma.domain.wiki.WikiContext
import com.example.looma.ui.theme.LoomaTokens
import com.mikepenz.markdown.compose.Markdown

/**
 * 全项目渲染笔记正文的唯一入口。
 *
 * 只吃 [MarkdownDocument]，不认识文件、路径和 ViewModel。复选框点击报原文坐标，
 * 由 feature 层决定怎么写回去。样式集中在 [loomaMarkdownTheme]，换渲染库只改这个包。
 *
 * [wiki] 只提供"这个目标解析到没有"，点击通过 [onWikiLinkClick] 交回上层；
 * 渲染层既不读索引，也不做导航。
 */
@Composable
fun MarkdownBody(
    document: MarkdownDocument,
    onCheckboxToggle: (position: Int, checked: Boolean) -> Unit,
    modifier: Modifier = Modifier,
    wiki: WikiContext? = null,
    onWikiLinkClick: (target: String) -> Unit = {},
) {
    val currentToggle = rememberUpdatedState(onCheckboxToggle)
    val currentLinkClick = rememberUpdatedState(onWikiLinkClick)
    val theme = loomaMarkdownTheme(base = LoomaTokens.type.markdownBody)
    val dividerColor = LoomaTokens.colors.divider
    val accent = LoomaTokens.colors.accent
    val muted = LoomaTokens.colors.muted
    val components = remember(theme, dividerColor, document.body, document.bodyStart, wiki, accent, muted) {
        loomaMarkdownComponents(
            theme = theme,
            compact = false,
            dividerColor = dividerColor,
            bodyStart = document.bodyStart,
            onCheckboxToggle = { position, checked -> currentToggle.value(position, checked) },
            wiki = wiki,
            onWikiLinkClick = { target -> currentLinkClick.value(target) },
            resolvedLink = SpanStyle(color = accent, textDecoration = TextDecoration.Underline),
            missingLink = SpanStyle(color = muted),
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
