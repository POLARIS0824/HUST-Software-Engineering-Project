package com.example.looma.data.note

import com.example.looma.domain.markdown.ImageSource
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 图片引用只有两种归宿：库内的本地文件，或 http(s) 远程地址。
 * 其余一律拒绝，因为引用写在正文里，用户可以随手写 `../../`。
 */
class ImageReferenceTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var root: File
    private lateinit var note: File

    @Before
    fun setUp() {
        root = temp.newFolder("vault")
        val notes = File(root, "notes").apply { mkdirs() }
        note = File(notes, "a.md").apply { writeText("# a") }
        File(notes, "pic.png").writeBytes(byteArrayOf(1))
        File(notes, "my pic.png").writeBytes(byteArrayOf(1))
        File(notes, "doc.pdf").writeBytes(byteArrayOf(1))
        File(root, "asset.jpg").writeBytes(byteArrayOf(1))
        temp.newFile("outside.png").writeBytes(byteArrayOf(1))
    }

    private fun resolve(reference: String?): ImageSource? =
        resolveImageSource(root.absolutePath, note.absolutePath, reference)

    @Test
    fun `resolves an image next to the note`() {
        val resolved = resolve("pic.png")
        assertTrue(resolved is ImageSource.Local)
        assertEquals(File(root, "notes/pic.png").canonicalPath, (resolved as ImageSource.Local).path)
    }

    @Test
    fun `falls back to the vault root`() {
        val resolved = resolve("asset.jpg")
        assertEquals(File(root, "asset.jpg").canonicalPath, (resolved as ImageSource.Local).path)
    }

    @Test
    fun `keeps remote links as remote`() {
        val resolved = resolve("https://img.example.com/shot.png")
        assertEquals(ImageSource.Remote("https://img.example.com/shot.png"), resolved)
    }

    @Test
    fun `strips the width suffix and decodes spaces`() {
        val resolved = resolve("my%20pic.png|300")
        assertEquals(File(root, "notes/my pic.png").canonicalPath, (resolved as ImageSource.Local).path)
    }

    @Test
    fun `refuses to escape the vault`() {
        assertNull(resolve("../../outside.png"))
    }

    @Test
    fun `refuses absolute paths and non image files`() {
        assertNull(resolve("/etc/passwd.png"))
        assertNull(resolve("doc.pdf"))
    }

    @Test
    fun `refuses cleartext remote links`() {
        assertNull(resolve("http://img.example.com/shot.png"))
    }

    @Test
    fun `cover only accepts local sources`() {
        assertEquals(
            File(root, "notes/pic.png").canonicalPath,
            resolveCover(root, note, "pic.png"),
        )
        assertNull(resolveCover(root, note, "https://img.example.com/shot.png"))
    }
}
