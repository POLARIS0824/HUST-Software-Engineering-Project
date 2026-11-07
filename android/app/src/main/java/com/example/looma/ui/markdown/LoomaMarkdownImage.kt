package com.example.looma.ui.markdown

import androidx.compose.runtime.Composable
import com.example.looma.domain.markdown.splitImageSize
import com.example.looma.ui.image.LoomaAsyncImage
import com.mikepenz.markdown.compose.LocalReferenceLinkHandler
import com.mikepenz.markdown.compose.components.MarkdownComponentModel
import com.mikepenz.markdown.compose.elements.ImageAltTooltip
import com.mikepenz.markdown.utils.resolveImageAlt
import com.mikepenz.markdown.utils.resolveImageLink

@Composable
internal fun LoomaMarkdownImage(model: MarkdownComponentModel) {
    val link = model.node.resolveImageLink(model.content, LocalReferenceLinkHandler.current) ?: return
    val (label, size) = splitImageSize(model.node.resolveImageAlt(model.content).orEmpty())
    val target = if (size != null) "$link|${size.suffix}" else link
    val source = rememberImageSource(target) ?: return
    val alt = label.takeIf { it.isNotBlank() }
    ImageAltTooltip(alt) {
        LoomaAsyncImage(
            source = source,
            contentDescription = alt,
            modifier = source.size.imageModifier(),
            contentScale = source.size.imageContentScale(),
        )
    }
}
