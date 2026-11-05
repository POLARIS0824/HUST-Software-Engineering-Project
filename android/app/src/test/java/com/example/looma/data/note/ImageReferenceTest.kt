package com.example.looma.data.note

import com.example.looma.domain.markdown.ImageSize
import com.example.looma.domain.markdown.ImageSource
import com.example.looma.domain.markdown.splitImageSize
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
        assertEquals(ImageSize(300, null), resolved.size)
    }

    @Test
    fun `keeps the size of local images`() {
        assertEquals(ImageSize(300, null), resolve("pic.png|300")?.size)
        assertEquals(ImageSize(300, 200), resolve("pic.png|300x200")?.size)
        assertEquals(ImageSize(null, 200), resolve("pic.png|x200")?.size)
        assertNull(resolve("pic.png")?.size)
    }

    @Test
    fun `keeps the size of remote images`() {
        assertEquals(
            ImageSource.Remote("https://img.example.com/shot.png", ImageSize(120, 80)),
            resolve("https://img.example.com/shot.png|120x80"),
        )
        assertEquals(
            ImageSource.Remote("https://img.example.com/shot.png", ImageSize(null, 90)),
            resolve("https://img.example.com/shot.png|x90"),
        )
        assertEquals(ImageSource.Remote("https://img.example.com/shot.png"), resolve("https://img.example.com/shot.png"))
    }

    @Test
    fun `only a parsable suffix is treated as a size`() {
        assertEquals("pic.png" to ImageSize(300, null), splitImageSize("pic.png|300"))
        assertEquals("pic.png" to ImageSize(300, 200), splitImageSize("pic.png|300x200"))
        assertEquals("pic.png" to ImageSize(null, 200), splitImageSize("pic.png|x200"))
        assertEquals("pic.png" to null, splitImageSize("pic.png"))
        assertEquals("a|b.png" to null, splitImageSize("a|b.png"))
        assertEquals("pic.png|big" to null, splitImageSize("pic.png|big"))
        assertEquals("pic.png|300x" to null, splitImageSize("pic.png|300x"))
        assertEquals("pic.png|0" to null, splitImageSize("pic.png|0"))
        assertEquals("pic.png|-5" to null, splitImageSize("pic.png|-5"))
        assertEquals("pic.png|300x-1" to null, splitImageSize("pic.png|300x-1"))
        assertEquals("pic.png|300x200x1" to null, splitImageSize("pic.png|300x200x1"))
        assertEquals("|300" to null, splitImageSize("|300"))
    }

    @Test
    fun `never parses empty or non positive sizes`() {
        assertNull(ImageSize.parse(""))
        assertNull(ImageSize.parse("x"))
        assertNull(ImageSize.parse("300x"))
        assertNull(ImageSize.parse("300.5"))
        assertNull(ImageSize.parse("0x200"))
        assertEquals(ImageSize(300, 200), ImageSize.parse(" 300x200 "))
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
    @Test
    fun `preserves remote encoding queries and fragments`() {
        val url = "https://img.example.com/a%20b.png?path=x%2Fy&plus=a+b#part"
        assertEquals(ImageSource.Remote(url), resolve(url))
        assertEquals(ImageSource.Remote(url, ImageSize(300, null)), resolve("$url|300"))
    }

    @Test
    fun `decodes local paths without treating plus as space`() {
        File(root, "notes/a+b #c.png").writeBytes(byteArrayOf(1))
        assertEquals(
            ImageSource.Local(File(root, "notes/a+b #c.png").canonicalPath),
            resolve("a+b%20%23c.png#display-fragment"),
        )
        assertNull(resolve("%2e%2e/%2e%2e/outside.png"))
        assertNull(resolve("%2foutside.png"))
        assertNull(resolve("bad%XY.png"))
    }

    @Test
    fun `filename attachments search vault deterministically after nearby paths`() {
        val a = File(root, "attachments-a").apply { mkdirs() }
        val z = File(root, "attachments-z").apply { mkdirs() }
        File(z, "unique.png").writeBytes(byteArrayOf(1))
        File(a, "unique.png").writeBytes(byteArrayOf(1))
        assertEquals(ImageSource.Local(File(a, "unique.png").canonicalPath), resolve("unique.png"))
        File(root, "notes/unique.png").writeBytes(byteArrayOf(1))
        assertEquals(ImageSource.Local(File(root, "notes/unique.png").canonicalPath), resolve("unique.png"))
        assertNull(resolve("missing/unique.png"))
    }

    @Test
    fun `does not search trashed or metadata attachments`() {
        for (folder in listOf(".trash", ".obsidian", ".git")) {
            File(root, folder).mkdirs()
            File(root, "$folder/hidden.png").writeBytes(byteArrayOf(1))
        }
        assertNull(resolve("hidden.png"))
    }

}
