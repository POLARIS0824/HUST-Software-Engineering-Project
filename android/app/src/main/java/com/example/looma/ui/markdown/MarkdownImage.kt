package com.example.looma.ui.markdown

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.layout.ContentScale
import com.example.looma.domain.markdown.ImageSource
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.mikepenz.markdown.model.ImageData
import com.mikepenz.markdown.model.ImageTransformer

/**
 * 图片引用的解析入口，由 feature 层按当前笔记提供。
 * 默认返回 null：没有库上下文时（预览、测试）一张图都不加载。
 */
val LocalImageSourceResolver = staticCompositionLocalOf<(String) -> ImageSource?> { { null } }

private const val MAX_EDGE_PX = 1600
private const val CACHE_SIZE_KB = 64 * 1024
private const val MAX_DOWNLOAD_BYTES = 12 * 1024 * 1024
private const val TIMEOUT_MS = 10_000

/** 进程级缓存，按来源取键：同一个文件换笔记不该重解码 */
private val imageCache = object : LruCache<String, Bitmap>(CACHE_SIZE_KB) {
    override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
}

/**
 * 把渲染库的图片插槽接到真实的解码器上。
 *
 * 本地文件走 BitmapFactory，http(s) 走 HttpURLConnection，都在 IO 线程上。
 * 失败时返回 null，坏引用只是不显示，不会拖垮整篇正文。
 */
internal class LoomaImageTransformer : ImageTransformer {

    @Composable
    override fun transform(link: String): ImageData? {
        val resolver = LocalImageSourceResolver.current
        // 解析也要进 IO：canonicalFile 会摸文件系统，不能放在 composition 里
        val loaded by produceState<Bitmap?>(null, link, resolver) {
            value = withContext(Dispatchers.IO) { resolver(link)?.let(::loadImage) }
        }
        val bitmap = loaded ?: return null
        return ImageData(
            painter = BitmapPainter(bitmap.asImageBitmap()),
            modifier = Modifier.fillMaxWidth(),
            contentScale = ContentScale.FillWidth,
        )
    }
}

private val ImageSource.cacheKey: String
    get() = when (this) {
        is ImageSource.Local -> "file:$path"
        is ImageSource.Remote -> "url:$url"
    }

private fun loadImage(source: ImageSource): Bitmap? {
    imageCache.get(source.cacheKey)?.let { return it }
    return runCatching {
        when (source) {
            is ImageSource.Local -> decodeFile(source.path)
            is ImageSource.Remote -> decodeBytes(download(source.url))
        }?.also { imageCache.put(source.cacheKey, it) }
    }.getOrNull()
}

private fun decodeFile(path: String): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    val options = BitmapFactory.Options().apply { inSampleSize = sampleSizeFor(bounds) }
    return BitmapFactory.decodeFile(path, options)
}

private fun decodeBytes(bytes: ByteArray): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    val options = BitmapFactory.Options().apply { inSampleSize = sampleSizeFor(bounds) }
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
}

/** 只缩不放大：小图保持原尺寸，大图按 2 的幂降到 [MAX_EDGE_PX] 以内 */
private fun sampleSizeFor(bounds: BitmapFactory.Options): Int {
    var sample = 1
    while (bounds.outWidth / sample > MAX_EDGE_PX || bounds.outHeight / sample > MAX_EDGE_PX) {
        sample *= 2
    }
    return sample
}

private fun download(url: String): ByteArray {
    val connection = URL(url).openConnection() as HttpURLConnection
    return try {
        connection.connectTimeout = TIMEOUT_MS
        connection.readTimeout = TIMEOUT_MS
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("Accept", "image/*")
        connection.inputStream.use { stream ->
            val buffer = ByteArray(16 * 1024)
            val out = ByteArrayOutputStream()
            var total = 0
            while (true) {
                val read = stream.read(buffer)
                if (read <= 0) break
                total += read
                check(total <= MAX_DOWNLOAD_BYTES) { "image exceeds $MAX_DOWNLOAD_BYTES bytes" }
                out.write(buffer, 0, read)
            }
            out.toByteArray()
        }
    } finally {
        connection.disconnect()
    }
}
