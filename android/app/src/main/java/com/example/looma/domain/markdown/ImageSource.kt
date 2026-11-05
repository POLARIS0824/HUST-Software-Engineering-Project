package com.example.looma.domain.markdown

/**
 * Obsidian 的图片尺寸后缀：`300`、`300x200`、`x200`，只接受正整数。
 * 两个维度都可以为 null，但不会出现两个都为 null 的实例（解析器返回 null）。
 */
data class ImageSize(val width: Int?, val height: Int?) {
    /** 规范化后的后缀，用于把尺寸重新拼回引用 */
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

/**
 * 把引用拆成目标和可选的尺寸：只有后缀能被 [ImageSize.parse] 接受时才剥离，
 * 否则整条引用原样保留，`a|b.png` 这类合法文件名不会被截断。
 */
internal fun splitImageSize(reference: String): Pair<String, ImageSize?> {
    val separator = reference.lastIndexOf('|')
    if (separator <= 0) return reference to null
    val size = ImageSize.parse(reference.substring(separator + 1)) ?: return reference to null
    return reference.substring(0, separator) to size
}

/** 正文里一条图片引用解析之后的结果 */
sealed interface ImageSource {
    val size: ImageSize?

    /** Vault 内的本地文件，[path] 是绝对路径 */
    data class Local(val path: String, override val size: ImageSize? = null) : ImageSource

    /** 图床或其他在线地址 */
    data class Remote(val url: String, override val size: ImageSize? = null) : ImageSource
}

/** 解析图片引用需要的两个坐标：库根和当前笔记的绝对路径 */
data class ImageContext(
    val vaultRoot: String,
    val noteFile: String,
)
