package com.example.looma.data.note

import android.util.AtomicFile
import java.io.File
import java.io.IOException

/** 2 MiB 上限：正文进内存与索引，超大文件按不可读处理而不是拖垮扫描 */
internal const val MAX_MARKDOWN_BYTES = 2L * 1024 * 1024

/** 读正文；扫描与单篇读取共用同一套上限和 BOM 处理 */
internal fun readMarkdown(file: File): String {
    if (file.length() > MAX_MARKDOWN_BYTES) throw IOException("note file is too large: ${file.name}")
    return AtomicFile(file).openRead().bufferedReader(Charsets.UTF_8).use { it.readText() }
        .removePrefix("\uFEFF")
}
