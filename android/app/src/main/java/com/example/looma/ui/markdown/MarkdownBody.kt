package com.example.looma.ui.markdown

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.style.TextDecoration
import com.example.looma.domain.markdown.MarkdownDocument
import com.example.looma.domain.markdown.WikiImageFlavour
import com.example.looma.domain.markdown.preserveDisplayMathParagraphs
import com.example.looma.domain.wiki.WikiContext
import com.example.looma.ui.theme.LoomaTokens
import com.mikepenz.markdown.compose.Markdown
import com.mikepenz.markdown.model.markdownInlineContent

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
    val theme = loomaMarkdownTheme(
        base = LoomaTokens.type.noteBody,
    )
    val dividerColor = LoomaTokens.colors.divider
    val accent = LoomaTokens.colors.accent
    val muted = LoomaTokens.colors.muted
    // 等长替换公式内的空白行，段落不再被拆开；原文和复选框坐标保持不变
    val renderBody = remember(document.body) { preserveDisplayMathParagraphs(document.body) }
    // 公式解析只依赖正文和文字颜色；深浅色切换会重新解析展示列表
    val math = rememberMathRenderState(
        body = renderBody,
        fontSize = LoomaTokens.type.noteBody.fontSize,
        color = LoomaTokens.colors.title,
    )
    val annotator = remember(math) { mathAnnotator(math) }
    val inlineContent = markdownInlineContent(rememberMathInlineContent(math, math.fontSize))
    val components = remember(theme, dividerColor, renderBody, document.bodyStart, wiki, accent, muted, math) {
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
            math = math,
        )
    }

    // 渲染库的图片插槽默认是空实现；接上真正的解码器之后正文图片才会出现
    val transformer = remember { LoomaImageTransformer() }
    val flavour = remember { WikiImageFlavour() }
    Markdown(
        content = renderBody,
        flavour = flavour,
        imageTransformer = transformer,
        annotator = annotator,
        inlineContent = inlineContent,
        colors = theme.colors,
        typography = theme.typography,
        padding = theme.padding,
        components = components,
        // 勾选复选框会整段重解析；保留上一帧内容，页面不会闪成空白、滚动位置也不会被拉回顶部
        retainState = true,
        modifier = modifier.fillMaxWidth(),
    )
}
