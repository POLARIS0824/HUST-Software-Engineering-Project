package com.example.looma.data.note

import android.util.AtomicFile
import java.io.File
import java.io.IOException

internal const val MAX_MARKDOWN_BYTES = 2L * 1024 * 1024

internal fun readMarkdown(file: File): String {
    if (file.length() > MAX_MARKDOWN_BYTES) throw IOException("note file is too large: ${file.name}")
    return AtomicFile(file).openRead().bufferedReader(Charsets.UTF_8).use { it.readText() }
        .removePrefix("\uFEFF")
}
