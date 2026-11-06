package com.example.looma.ui.markdown

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.example.looma.ui.theme.LoomaTokens
import com.example.looma.ui.theme.LoomaPreviewTypography
import com.example.looma.domain.markdown.WikiImageFlavour
import com.mikepenz.markdown.compose.Markdown
import com.mikepenz.markdown.model.markdownAnnotator
import org.intellij.markdown.MarkdownElementTypes

@Composable
fun MarkdownPreview(
    markdown: String,
    modifier: Modifier = Modifier,
    typography: LoomaPreviewTypography? = null,
    textColor: Color = LoomaTokens.colors.title,
) {
    if (markdown.isBlank()) return
    val theme = loomaMarkdownTheme(
        base = typography?.body ?: LoomaTokens.type.cardBody,
        compact = true,
        preview = typography,
        textColor = textColor,
    )
    val flavour = remember { WikiImageFlavour() }
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
        flavour = flavour,
        annotator = previewMarkdownAnnotator,
        colors = theme.colors,
        typography = theme.typography,
        padding = theme.padding,
        components = components,
        modifier = modifier.fillMaxWidth(),
    )
}

internal val previewMarkdownAnnotator =
    markdownAnnotator(config = loomaAnnotatorConfig) { _, node ->
        node.type == MarkdownElementTypes.IMAGE
}
