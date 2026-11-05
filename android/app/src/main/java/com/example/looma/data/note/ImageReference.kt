package com.example.looma.data.note

import com.example.looma.domain.markdown.ImageSource
import java.io.File
import java.net.URLDecoder
import java.nio.file.Files

private val LOCAL_IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "webp", "gif", "bmp")

internal fun resolveImageSource(vaultRoot: String, noteFile: String, reference: String?): ImageSource? {
    val raw = reference?.substringBefore('|')?.trim()?.removeSurrounding("<", ">")
        ?.takeIf { it.isNotBlank() } ?: return null
    if (raw.startsWith("https://", ignoreCase = true)) return ImageSource.Remote(raw)
    if (raw.startsWith("http://", ignoreCase = true)) return null
    val src = runCatching {
        URLDecoder.decode(raw.substringBefore('#').replace("+", "%2B"), "UTF-8")
    }.getOrNull()?.takeIf { it.isNotBlank() } ?: return null
    if (src.startsWith('/') || ':' in src || '\\' in src || '\u0000' in src) return null

    return runCatching {
        val root = File(vaultRoot).canonicalFile
        fun local(candidate: File): ImageSource.Local? = runCatching {
            val canonical = candidate.canonicalFile
            canonical.takeIf {
                it.toPath().startsWith(root.toPath()) && it.isFile &&
                    it.extension.lowercase() in LOCAL_IMAGE_EXTENSIONS
            }?.absolutePath?.let(ImageSource::Local)
        }.getOrNull()
        val direct = listOf(File(File(noteFile).parentFile, src), File(root, src))
            .firstNotNullOfOrNull(::local)
        if (direct != null || '/' in src || File(src).extension.lowercase() !in LOCAL_IMAGE_EXTENSIONS) {
            return@runCatching direct
        }
        // Filename-only Obsidian attachments may live anywhere in the vault.
        // Files.walk does not follow directory symlinks; canonical containment still guards files.
        Files.walk(root.toPath()).use { paths ->
            paths.filter { path ->
                path.fileName?.toString()?.equals(src, ignoreCase = true) == true &&
                    root.toPath().relativize(path).none { it.toString() in setOf(".trash", ".obsidian", ".git") }
            }.iterator().asSequence().map { it.toFile() }.toList()
                .sortedWith(compareBy<File>(
                    { if (it.name == src) 0 else 1 },
                    { it.relativeTo(root).invariantSeparatorsPath },
                ))
                .firstNotNullOfOrNull(::local)
        }
    }.getOrNull()
}
