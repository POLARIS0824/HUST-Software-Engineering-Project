package com.example.looma.data.note

import java.io.File
import java.io.IOException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MarkdownFileIOTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `reading preserves BOM bytes and never recovers an AtomicFile backup`() {
        val file = temporaryFolder.newFile("note.md")
        val bytes = "\uFEFF# 当前\r\n".toByteArray(Charsets.UTF_8)
        file.writeBytes(bytes)
        val backup = File(file.parentFile, "${file.name}.bak").apply { writeText("old backup") }

        assertEquals("\uFEFF# 当前\r\n", readMarkdown(file))
        assertArrayEquals(bytes, readMarkdownBytes(file))
        assertArrayEquals(bytes, file.readBytes())
        assertEquals("old backup", backup.readText())
    }

    @Test
    fun `missing target is not restored from bak on read`() {
        val file = File(temporaryFolder.root, "missing.md")
        val backup = File(file.parentFile, "${file.name}.bak").apply { writeText("backup") }

        assertThrows(IOException::class.java) { readMarkdown(file) }

        assertFalse(file.exists())
        assertEquals("backup", backup.readText())
    }

    @Test
    fun `atomic replacement exposes old bytes until the complete new file is ready`() {
        val file = temporaryFolder.newFile("note.md")
        val old = ByteArray(100_000) { 17 }
        val new = ByteArray(200_000) { 29 }
        file.writeBytes(old)
        val backup = File(file.parentFile, "${file.name}.bak").apply { writeText("unrelated") }

        writeBytesAtomically(file, new) {
            assertArrayEquals(old, file.readBytes())
            val temporary = file.parentFile.listFiles()!!.single { it.name.endsWith(".tmp") }
            assertArrayEquals(new, temporary.readBytes())
        }

        assertArrayEquals(new, file.readBytes())
        assertEquals("unrelated", backup.readText())
        assertEquals(setOf("note.md", "note.md.bak"), file.parentFile.list()!!.toSet())
    }

    @Test
    fun `failed pre-replacement validation leaves target and cleans temp`() {
        val file = temporaryFolder.newFile("note.md").apply { writeText("old") }
        val failure = IOException("validation failed")

        val thrown = assertThrows(IOException::class.java) {
            writeBytesAtomically(file, "new".toByteArray()) { throw failure }
        }

        assertTrue(thrown === failure)
        assertEquals("old", file.readText())
        assertEquals(listOf("note.md"), file.parentFile.list()!!.toList())
    }

    @Test
    fun `failed replacement cannot delete destination directory`() {
        val target = temporaryFolder.newFolder("note.md")
        val child = File(target, "keep").apply { writeText("old") }

        assertThrows(IOException::class.java) { writeBytesAtomically(target, byteArrayOf(1)) }

        assertEquals("old", child.readText())
        assertEquals(listOf("note.md"), target.parentFile.list()!!.toList())
    }

    @Test
    fun `nested writes use distinct temporary files`() {
        val file = temporaryFolder.newFile("note.md").apply { writeText("old") }

        writeBytesAtomically(file, "outer".toByteArray()) {
            writeBytesAtomically(file, "inner".toByteArray())
            assertEquals("inner", file.readText())
            assertEquals(1, file.parentFile.listFiles()!!.count { it.name.endsWith(".tmp") })
        }

        assertEquals("outer", file.readText())
        assertEquals(listOf("note.md"), file.parentFile.list()!!.toList())
    }

    @Test
    fun `reads enforce the stream bound even when file length is stale`() {
        val file = temporaryFolder.newFile("note.md").apply {
            writeBytes(ByteArray(MAX_MARKDOWN_BYTES.toInt() + 1))
        }
        val staleLengthFile = object : File(file.path) {
            override fun length(): Long = 0
        }

        assertThrows(NoteTooLargeException::class.java) { readMarkdown(file) }
        assertThrows(NoteTooLargeException::class.java) { readMarkdown(staleLengthFile) }
    }

    @Test
    fun `size limit permits exactly the maximum raw byte count`() {
        val file = temporaryFolder.newFile("note.md")
        val bytes = ByteArray(MAX_MARKDOWN_BYTES.toInt()) { 'a'.code.toByte() }
        file.writeBytes(bytes)

        assertArrayEquals(bytes, readMarkdownBytes(file))
    }

    @Test
    fun `markdown write validates UTF8 size before touching target`() {
        val file = temporaryFolder.newFile("note.md").apply { writeText("old") }

        assertThrows(NoteTooLargeException::class.java) {
            writeMarkdown(file, "笔".repeat(MAX_MARKDOWN_BYTES.toInt() / 3 + 1))
        }

        assertEquals("old", file.readText())
        assertEquals(listOf("note.md"), file.parentFile.list()!!.toList())
    }
}
