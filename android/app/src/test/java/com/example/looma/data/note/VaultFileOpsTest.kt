package com.example.looma.data.note

import com.example.looma.domain.model.FolderPath
import com.example.looma.domain.model.NotePath
import java.io.File
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 文件管理闭环的落盘行为：目录、改名、移动、回收站、恢复。
 *
 * 全部跑在临时目录上，断言的是用户能看到的结果（文件在不在、叫什么名字），
 * 而不是内部实现。
 */
class VaultFileOpsTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private lateinit var root: File
    private lateinit var ops: VaultFileOps

    private fun setUp() {
        root = temporaryFolder.newFolder("vault")
        ops = VaultFileOps(root)
    }

    private fun note(path: String, content: String = "# note\n"): File {
        val file = File(root, path)
        file.parentFile?.mkdirs()
        file.writeText(content)
        return file
    }

    @Test
    fun `createFolder writes a directory and refuses duplicates`() {
        setUp()

        val created = ops.createFolder(FolderPath.Root, "项目")

        assertTrue(created.isDirectory)
        assertEquals("项目", created.name)
        val error = assertThrows(VaultFileException::class.java) {
            ops.createFolder(FolderPath.Root, "项目")
        }
        assertEquals(VaultFileFailure.NAME_TAKEN, error.failure)
    }

    @Test
    fun `newNoteFile keeps file names unique`() {
        setUp()

        val first = ops.newNoteFile(FolderPath.Root, "会议记录", fallbackName = "Untitled")
        val second = ops.newNoteFile(FolderPath.Root, "会议记录", fallbackName = "Untitled")

        assertEquals("会议记录.md", first.name)
        assertEquals("会议记录 (1).md", second.name)
        assertTrue(first.isFile && second.isFile)
    }

    @Test
    fun `rename writes the new name and reports taken names`() {
        setUp()
        val original = note("旧名字.md")
        note("已存在.md")

        val renamed = ops.rename(original, "新名字")
        assertEquals("新名字.md", renamed.name)
        assertFalse(original.exists())

        val error = assertThrows(VaultFileException::class.java) {
            ops.rename(renamed, "已存在")
        }
        assertEquals(VaultFileFailure.NAME_TAKEN, error.failure)
    }

    @Test
    fun `rename rejects names that would leave the vault`() {
        setUp()
        val original = note("笔记.md")

        val error = assertThrows(VaultFileException::class.java) {
            ops.rename(original, "../逃逸")
        }
        assertEquals(VaultFileFailure.INVALID_NAME, error.failure)
        assertTrue(original.exists())
    }

    @Test
    fun `move puts the note in the target folder`() {
        setUp()
        val original = note("笔记.md")
        ops.createFolder(FolderPath.Root, "档案")

        val moved = ops.move(original, FolderPath("档案"))

        assertEquals(File(root, "档案/笔记.md").canonicalFile, moved.canonicalFile)
        assertFalse(original.exists())
        assertTrue(moved.isFile)
    }

    @Test
    fun `move keeps both files when the target has the same name`() {
        setUp()
        val original = note("笔记.md", content = "source")
        note("档案/笔记.md", content = "existing")
        ops.ensureFolder(FolderPath("档案"))

        val moved = ops.move(original, FolderPath("档案"))

        assertEquals("笔记 (1).md", moved.name)
        assertEquals("existing", File(root, "档案/笔记.md").readText())
        assertEquals("source", moved.readText())
    }

    @Test
    fun `trash then restore round trips the note`() {
        setUp()
        val original = note("项目/周报.md")

        val record = ops.trash(original, Instant.parse("2026-10-31T12:00:00Z"))

        assertFalse(original.exists())
        val trashed = ops.trashed()
        assertEquals(1, trashed.size)
        assertEquals(NotePath("项目/周报.md"), trashed.single().originalPath)

        val restored = ops.restore(record.trashId)

        assertEquals(original.canonicalFile, restored.canonicalFile)
        assertTrue(ops.trashed().isEmpty())
    }

    @Test
    fun `restore refuses to overwrite a note that came back`() {
        setUp()
        val original = note("周报.md")
        val record = ops.trash(original)
        original.writeText("# recreated\n")

        val error = assertThrows(VaultFileException::class.java) {
            ops.restore(record.trashId)
        }
        assertEquals(VaultFileFailure.RESTORE_CONFLICT, error.failure)
        assertEquals("# recreated\n", original.readText())
        assertEquals(1, ops.trashed().size)
    }

    @Test
    fun `restore creates the original folder again`() {
        setUp()
        val original = note("项目/细节.md")
        val record = ops.trash(original)
        File(root, "项目").deleteRecursively()

        val restored = ops.restore(record.trashId)

        assertTrue(restored.isFile)
        assertEquals(File(root, "项目/细节.md").canonicalFile, restored.canonicalFile)
    }

    @Test
    fun `noteFile rejects paths outside the vault`() {
        setUp()
        note("笔记.md")

        val traversal = assertThrows(VaultFileException::class.java) {
            ops.noteFile(NotePath("../outside.md"))
        }
        assertEquals(VaultFileFailure.INVALID_PATH, traversal.failure)

        val notMarkdown = assertThrows(VaultFileException::class.java) {
            ops.noteFile(NotePath("笔记.txt"))
        }
        assertEquals(VaultFileFailure.INVALID_PATH, notMarkdown.failure)
    }

    @Test
    fun `deletePermanently removes note and metadata`() {
        setUp()
        val original = note("周报.md")
        val record = ops.trash(original)

        ops.deletePermanently(record.trashId)

        assertFalse(record.file.exists())
        assertFalse(record.metaFile.exists())
        assertTrue(ops.trashed().isEmpty())
    }

    @Test
    fun `deletePermanently with an unknown id reports missing`() {
        setUp()

        val error = assertThrows(VaultFileException::class.java) {
            ops.deletePermanently("5f0f0f0f-0000-0000-0000-000000000000")
        }
        assertEquals(VaultFileFailure.NOT_FOUND, error.failure)
    }

    @Test
    fun `trash with an unknown id reports missing`() {
        setUp()

        val error = assertThrows(VaultFileException::class.java) {
            ops.restore("5f0f0f0f-0000-0000-0000-000000000000")
        }
        assertEquals(VaultFileFailure.NOT_FOUND, error.failure)
    }
}
