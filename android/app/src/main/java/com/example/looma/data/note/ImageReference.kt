package com.example.looma.data.note

import com.example.looma.domain.markdown.ImageSource
import java.io.File

private val LOCAL_IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "webp", "gif", "bmp")

/**
 * 把 Markdown 里的图片引用解析成可加载的来源。
 *
 * 相对路径先按笔记所在目录找，再按库根找；绝对路径、反斜杠和 `..` 逃逸一律拒绝，
 * 本地文件必须真的落在库内、扩展名像图片。http(s) 引用原样当远程地址。
 */
internal fun resolveImageSource(vaultRoot: String, noteFile: String, reference: String?): ImageSource? {
    val raw = reference
        ?.substringBefore('|')
        ?.trim()
        ?.removePrefix("<")
        ?.removeSuffix(">")
        ?.takeIf { it.isNotBlank() }
        ?: return null
    val src = raw.replace("%20", " ")
    // targetSdk 37 默认禁止明文流量，http 图片必然加载失败，不如直接拒绝
    if (src.startsWith("https://", ignoreCase = true)) return ImageSource.Remote(src)
    if (src.startsWith("http://", ignoreCase = true)) return null
    if (src.startsWith('/') || "://" in src || '\\' in src) return null

    val root = File(vaultRoot).canonicalFile
    return listOf(File(File(noteFile).parentFile, src), File(root, src))
        .firstNotNullOfOrNull { candidate ->
            runCatching {
                val canonical = candidate.canonicalFile
                val inside = canonical.toPath().startsWith(root.toPath())
                canonical
                    .takeIf { inside && it.isFile && it.extension.lowercase() in LOCAL_IMAGE_EXTENSIONS }
                    ?.absolutePath
                    ?.let(ImageSource::Local)
            }.getOrNull()
        }
}
