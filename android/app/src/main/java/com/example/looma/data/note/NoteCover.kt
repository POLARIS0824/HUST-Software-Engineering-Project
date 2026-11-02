package com.example.looma.data.note

import java.io.File

/**
 * 把 Markdown 里的图片引用解析成绝对路径，供卡片渲染封面。
 *
 * 只接受 Vault 内真实存在的图片文件：引用来自正文，可能带 `..` 或绝对路径，
 * 所以先 canonical 再判断是否还在 Vault 里。
 */
internal fun resolveCover(root: File, noteFile: File, reference: String?): String? {
    val src = reference?.substringBefore('|')?.trim()?.replace("%20", " ") ?: return null
    if (src.isBlank() || src.startsWith('/') || "://" in src || '\\' in src) return null
    val allowed = setOf("png", "jpg", "jpeg", "webp")
    return listOf(File(noteFile.parentFile, src), File(root, src))
        .firstNotNullOfOrNull { candidate ->
            runCatching {
                val canonical = candidate.canonicalFile
                val inside = canonical.toPath().startsWith(root.canonicalFile.toPath())
                canonical.takeIf { inside && it.isFile && it.extension.lowercase() in allowed }
                    ?.absolutePath
            }.getOrNull()
        }
}
