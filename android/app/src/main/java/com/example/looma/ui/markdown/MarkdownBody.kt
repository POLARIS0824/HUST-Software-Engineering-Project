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
    val renderBody = remember(document.body) { preserveDisplayMathParagraphs(document.body) }
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

    val transformer = remember { CoilImageTransformer() }
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
        retainState = true,
        modifier = modifier.fillMaxWidth(),
    )
}
