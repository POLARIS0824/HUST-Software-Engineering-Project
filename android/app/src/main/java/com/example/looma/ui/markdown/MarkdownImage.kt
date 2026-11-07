package com.example.looma.ui.markdown

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.compose.rememberAsyncImagePainter
import com.example.looma.domain.markdown.ImageSize
import com.example.looma.domain.markdown.ImageSource
import com.example.looma.ui.image.rememberImageRequest
import com.mikepenz.markdown.model.ImageData
import com.mikepenz.markdown.model.ImageTransformer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

val LocalImageSourceResolver = staticCompositionLocalOf<(String) -> ImageSource?> { { null } }

private const val INLINE_MAX_EDGE_PX = 1600

@Composable
internal fun rememberImageSource(link: String): ImageSource? {
    val resolver = LocalImageSourceResolver.current
    val resolved by produceState<ImageSource?>(null, link, resolver) {
        value = withContext(Dispatchers.IO) { resolver(link) }
    }
    return resolved
}

internal class CoilImageTransformer : ImageTransformer {

    @Composable
    override fun transform(link: String): ImageData? {
        val source = rememberImageSource(link) ?: return null
        val painter = rememberAsyncImagePainter(rememberImageRequest(source, INLINE_MAX_EDGE_PX))
        return ImageData(
            painter = painter,
            modifier = source.size.imageModifier(),
            contentScale = source.size.imageContentScale(),
        )
    }
}

internal fun ImageSize?.imageModifier(): Modifier {
    val width = this?.width
    val height = this?.height
    return when {
        width != null && height != null -> Modifier.size(width.dp, height.dp)
        width != null -> Modifier.width(width.dp)
        height != null -> Modifier.height(height.dp)
        else -> Modifier.fillMaxWidth()
    }
}

internal fun ImageSize?.imageContentScale(): ContentScale {
    val width = this?.width
    val height = this?.height
    return when {
        width != null && height != null -> ContentScale.FillBounds
        height != null -> ContentScale.FillHeight
        else -> ContentScale.FillWidth
    }
}
