package com.example.looma.feature.home.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import com.example.looma.domain.markdown.ImageSource
import com.example.looma.ui.image.LoomaAsyncImage

@Composable
internal fun NoteCoverImage(path: String, modifier: Modifier = Modifier) {
    LoomaAsyncImage(
        source = ImageSource.Local(path),
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = modifier,
    )
}
