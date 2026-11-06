package com.example.looma.feature.home.components

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val MAX_EDGE_PX = 800
private const val CACHE_SIZE_KB = 32 * 1024

private val coverCache = object : LruCache<String, Bitmap>(CACHE_SIZE_KB) {
    override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
}

@Composable
internal fun NoteCoverImage(path: String, modifier: Modifier = Modifier) {
    val bitmap by produceState<ImageBitmap?>(coverCache.get(path)?.asImageBitmap(), path) {
        if (value == null) {
            value = withContext(Dispatchers.IO) { loadCover(path) }?.asImageBitmap()
        }
    }
    bitmap?.let { image ->
        Image(
            bitmap = image,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = modifier,
        )
    }
}

private fun loadCover(path: String): Bitmap? {
    coverCache.get(path)?.let { return it }
    return runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / sample > MAX_EDGE_PX || bounds.outHeight / sample > MAX_EDGE_PX) {
            sample *= 2
        }
        BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
            ?.also { coverCache.put(path, it) }
    }.getOrNull()
}
