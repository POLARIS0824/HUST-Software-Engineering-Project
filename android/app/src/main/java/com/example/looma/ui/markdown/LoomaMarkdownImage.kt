package com.example.looma.ui.markdown

import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import com.example.looma.domain.markdown.splitImageSize
import com.mikepenz.markdown.compose.LocalImageTransformer
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
    LocalImageTransformer.current.transform(target)?.let { imageData ->
        val alt = label.takeIf { it.isNotBlank() }
        ImageAltTooltip(alt) {
            Image(
                painter = imageData.painter,
                contentDescription = alt ?: imageData.contentDescription,
                modifier = imageData.modifier,
                alignment = imageData.alignment,
                contentScale = imageData.contentScale,
                alpha = imageData.alpha,
                colorFilter = imageData.colorFilter,
            )
        }
    }
}
