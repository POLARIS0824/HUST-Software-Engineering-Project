package com.example.looma.domain.markdown

data class ImageSize(val width: Int?, val height: Int?) {
    val suffix: String
        get() = when {
            width != null && height != null -> "${width}x$height"
            width != null -> width.toString()
            height != null -> "x$height"
            else -> ""
        }

    companion object {
        fun parse(text: String): ImageSize? {
            val value = text.trim()
            val separator = value.indexOf('x')
            if (separator < 0) return positive(value)?.let { ImageSize(it, null) }
            val left = value.substring(0, separator).trim()
            val right = value.substring(separator + 1).trim()
            if (left.isEmpty()) return positive(right)?.let { ImageSize(null, it) }
            if (right.isEmpty()) return null
            val width = positive(left) ?: return null
            val height = positive(right) ?: return null
            return ImageSize(width, height)
        }

        private fun positive(text: String): Int? = text.toIntOrNull()?.takeIf { it > 0 }
    }
}

internal fun splitImageSize(reference: String): Pair<String, ImageSize?> {
    val separator = reference.lastIndexOf('|')
    if (separator <= 0) return reference to null
    val size = ImageSize.parse(reference.substring(separator + 1)) ?: return reference to null
    return reference.substring(0, separator) to size
}

sealed interface ImageSource {
    val size: ImageSize?

    data class Local(val path: String, override val size: ImageSize? = null) : ImageSource

    data class Remote(val url: String, override val size: ImageSize? = null) : ImageSource
}

data class ImageContext(
    val vaultRoot: String,
    val noteFile: String,
)
