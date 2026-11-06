package com.example.looma.ui.image

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import com.example.looma.domain.markdown.ImageSource
import java.io.File

@Composable
internal fun LoomaAsyncImage(
    source: ImageSource,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Fit,
    contentDescription: String? = null,
) {
    AsyncImage(
        model = rememberImageRequest(source),
        contentDescription = contentDescription,
        modifier = modifier,
        contentScale = contentScale,
    )
}

@Composable
internal fun rememberImageRequest(source: ImageSource, maxEdgePx: Int? = null): ImageRequest {
    val context = LocalPlatformContext.current
    return remember(context, source, maxEdgePx) {
        ImageRequest.Builder(context)
            .data(source.coilModel())
            .apply { maxEdgePx?.let { size(it) } }
            .build()
    }
}

private fun ImageSource.coilModel(): Any = when (this) {
    is ImageSource.Local -> File(path)
    is ImageSource.Remote -> url
}
