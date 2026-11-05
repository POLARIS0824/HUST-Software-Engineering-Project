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

/**
 * 渲染库的图片元素，替身版本：尺寸写在 alt 里（`![图|300](a.png)`）时，
 * 把它挪到 link 上交给自己那一侧的解析器，alt 本身去掉尺寸再展示。
 */
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
