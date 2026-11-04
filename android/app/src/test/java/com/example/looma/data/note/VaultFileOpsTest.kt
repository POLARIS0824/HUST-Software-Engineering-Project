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

    // ---- 文件夹：改名、移动、删除 ----

    @Test
    fun `renameFolder renames the directory in place and keeps its notes`() {
        setUp()
        note("项目/计划.md")
        note("项目/细节/周报.md")

        val renamed = ops.renameFolder(FolderPath("项目"), "归档")

        assertEquals(FolderPath("归档"), renamed)
        assertFalse(File(root, "项目").exists())
        assertTrue(File(root, "归档/计划.md").isFile)
        assertTrue(File(root, "归档/细节/周报.md").isFile)
    }

    @Test
    fun `renameFolder refuses a sibling with the same name`() {
        setUp()
        note("项目/计划.md")
        note("归档/旧.md")

        val error = assertThrows(VaultFileException::class.java) {
            ops.renameFolder(FolderPath("项目"), "归档")
        }

        assertEquals(VaultFileFailure.NAME_TAKEN, error.failure)
        assertTrue(File(root, "项目/计划.md").isFile)
        assertTrue(File(root, "归档/旧.md").isFile)
    }

    @Test
    fun `renameFolder rejects a name that would leave the vault`() {
        setUp()
        note("项目/计划.md")

        val error = assertThrows(VaultFileException::class.java) {
            ops.renameFolder(FolderPath("项目"), "../逃逸")
        }

        assertEquals(VaultFileFailure.INVALID_NAME, error.failure)
        assertTrue(File(root, "项目/计划.md").isFile)
    }

    @Test
    fun `moveFolder moves the directory and its notes under another folder`() {
        setUp()
        note("项目/计划.md")
        ops.createFolder(FolderPath.Root, "档案")

        val moved = ops.moveFolder(FolderPath("项目"), FolderPath("档案"))

        assertEquals(FolderPath("档案/项目"), moved)
        assertFalse(File(root, "项目").exists())
        assertTrue(File(root, "档案/项目/计划.md").isFile)
    }

    @Test
    fun `moveFolder can move a folder back to the root`() {
        setUp()
        note("档案/项目/计划.md")

        val moved = ops.moveFolder(FolderPath("档案/项目"), FolderPath.Root)

        assertEquals(FolderPath("项目"), moved)
        assertTrue(File(root, "项目/计划.md").isFile)
    }

    @Test
    fun `moveFolder refuses to move a folder into itself or a descendant`() {
        setUp()
        note("项目/子目录/计划.md")

        val intoSelf = assertThrows(VaultFileException::class.java) {
            ops.moveFolder(FolderPath("项目"), FolderPath("项目"))
        }
        val intoChild = assertThrows(VaultFileException::class.java) {
            ops.moveFolder(FolderPath("项目"), FolderPath("项目/子目录"))
        }

        assertEquals(VaultFileFailure.INVALID_PATH, intoSelf.failure)
        assertEquals(VaultFileFailure.INVALID_PATH, intoChild.failure)
        assertTrue(File(root, "项目/子目录/计划.md").isFile)
    }

    @Test
    fun `moveFolder reports a name collision at the destination`() {
        setUp()
        note("项目/计划.md")
        note("档案/项目/旧.md")

        val error = assertThrows(VaultFileException::class.java) {
            ops.moveFolder(FolderPath("项目"), FolderPath("档案"))
        }

        assertEquals(VaultFileFailure.NAME_TAKEN, error.failure)
        assertTrue(File(root, "项目/计划.md").isFile)
        assertTrue(File(root, "档案/项目/旧.md").isFile)
    }

    @Test
    fun `deleteFolder moves every note to the trash and removes the directory`() {
        setUp()
        note("项目/计划.md")
        note("项目/周报/第 1 周.md")

        val trashed = ops.deleteFolder(FolderPath("项目"))

        assertEquals(2, trashed.size)
        assertFalse(File(root, "项目").exists())
        assertEquals(
            setOf("项目/计划.md", "项目/周报/第 1 周.md"),
            ops.trashed().map { it.originalPath.relativePath }.toSet(),
        )
    }

    @Test
    fun `deleteFolder skips dot directories such as looma`() {
        setUp()
        note("项目/计划.md")
        note("项目/.looma/私货.md")

        val trashed = ops.deleteFolder(FolderPath("项目"))

        assertEquals(1, trashed.size)
        assertEquals("项目/计划.md", ops.trashed().single().originalPath.relativePath)
        // 点目录整棵跳过：笔记原样保留，文件夹因此不算空，也不会被删掉
        assertTrue(File(root, "项目/.looma/私货.md").isFile)
        assertTrue(File(root, "项目").isDirectory)
    }

    @Test
    fun `deleteFolder keeps the directory when files other than notes remain`() {
        setUp()
        note("项目/计划.md")
        note("项目/封面.png")

        val trashed = ops.deleteFolder(FolderPath("项目"))

        assertEquals(1, trashed.size)
        assertFalse(File(root, "项目/计划.md").exists())
        assertTrue(File(root, "项目/封面.png").isFile)
        assertTrue(File(root, "项目").isDirectory)
    }

    @Test
    fun `deleteFolder leaves notes in place when the trash cannot be created`() {
        setUp()
        File(root, ".looma").mkdirs()
        File(root, ".looma/trash").writeText("not a directory")
        note("项目/计划.md")
        note("项目/周报/第 1 周.md")

        val error = assertThrows(VaultFileException::class.java) {
            ops.deleteFolder(FolderPath("项目"))
        }

        assertEquals(VaultFileFailure.IO, error.failure)
        assertTrue(File(root, "项目/计划.md").isFile)
        assertTrue(File(root, "项目/周报/第 1 周.md").isFile)
    }

    @Test
    fun `deleteFolder refuses the vault root`() {
        setUp()
        note("笔记.md")

        val error = assertThrows(VaultFileException::class.java) {
            ops.deleteFolder(FolderPath.Root)
        }

        assertEquals(VaultFileFailure.INVALID_PATH, error.failure)
        assertTrue(File(root, "笔记.md").isFile)
    }
}
