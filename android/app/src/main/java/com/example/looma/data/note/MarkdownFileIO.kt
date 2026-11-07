package com.example.looma.data.note

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING

internal const val MAX_MARKDOWN_BYTES = 2L * 1024 * 1024

internal fun readMarkdown(file: File): String = readMarkdownBytes(file).toString(Charsets.UTF_8)

internal fun readMarkdownBytes(file: File): ByteArray = readBytesBounded(file, MAX_MARKDOWN_BYTES)

internal fun readBytesBounded(file: File, maxBytes: Long): ByteArray {
    if (file.length() > maxBytes) throw NoteTooLargeException(file.name, maxBytes)
    return file.inputStream().use { input ->
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0L
        while (true) {
            val count = input.read(buffer, 0, minOf(buffer.size.toLong(), maxBytes - total + 1).toInt())
            if (count == -1) break
            total += count
            if (total > maxBytes) throw NoteTooLargeException(file.name, maxBytes)
            output.write(buffer, 0, count)
        }
        output.toByteArray()
    }
}

internal fun markdownBytes(text: String): ByteArray = text.toByteArray(Charsets.UTF_8).also {
    if (it.size > MAX_MARKDOWN_BYTES) throw NoteTooLargeException("note", MAX_MARKDOWN_BYTES)
}

internal fun writeMarkdown(file: File, text: String, beforeReplace: () -> Unit = {}) {
    writeBytesAtomically(file, markdownBytes(text), beforeReplace)
}

internal fun writeBytesAtomically(file: File, bytes: ByteArray, beforeReplace: () -> Unit = {}) {
    val destination = file.absoluteFile.toPath()
    val temporary = Files.createTempFile(destination.parent, ".looma-", ".tmp")
    try {
        FileOutputStream(temporary.toFile()).use { output ->
            output.write(bytes)
            output.fd.sync()
        }
        beforeReplace()
        Files.move(temporary, destination, ATOMIC_MOVE, REPLACE_EXISTING)
    } catch (failure: Exception) {
        try {
            Files.deleteIfExists(temporary)
        } catch (cleanupFailure: Exception) {
            failure.addSuppressed(cleanupFailure)
        }
        throw failure
    }
}

class NoteTooLargeException(name: String, val maxBytes: Long) :
    IOException("file is too large: $name (limit $maxBytes bytes)")
