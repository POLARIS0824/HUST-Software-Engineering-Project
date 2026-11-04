package com.example.looma.data.note

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 回收站批量操作的计数语义：能量的一篇不能因为别人冲突就整批不做，
 * 失败的条数还要如实报给界面。
 *
 * 和 [VaultFileOpsTest] 一样跑在临时目录上，只断言用户能看到的结果。
 */
class TrashBulkStoreTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private lateinit var root: File
    private lateinit var ops: VaultFileOps

    private fun setUp() {
        root = temporaryFolder.newFolder("vault")
        ops = VaultFileOps(root)
    }

    private fun note(path: String, content: String = "# 笔记\n"): File {
        val file = File(root, path)
        file.parentFile?.mkdirs()
        file.writeText(content)
        return file
    }

    @Test
    fun `bulk restore keeps the restorable notes and counts conflicts as failed`() {
        setUp()
        val weekly = note("项目/周报.md")
        val minutes = note("项目/纪要.md")
        val weeklyRecord = ops.trash(weekly)
        val minutesRecord = ops.trash(minutes)
        // 原位置又冒出一份同名文件：这一篇恢复必然冲突，只能计入失败并留在回收站
        note("项目/纪要.md", "# 后来写的\n")

        val (restored, outcome) = bulkOverTrash(
            ops,
            listOf(weeklyRecord.trashId, minutesRecord.trashId),
        ) { record -> ops.restore(record) }

        assertEquals(1, outcome.succeeded)
        assertEquals(1, outcome.failed)
        assertEquals(2, outcome.total)
        assertEquals(File(root, "项目/周报.md").canonicalFile, restored.single().canonicalFile)
        assertEquals(listOf("项目/纪要.md"), ops.trashed().map { it.originalPath.relativePath })
    }

    @Test
    fun `bulk delete removes exactly the listed records`() {
        setUp()
        val first = ops.trash(note("a.md"))
        val second = ops.trash(note("b.md"))
        val kept = ops.trash(note("c.md"))

        val outcome = bulkOverTrash(
            ops,
            listOf(first.trashId, second.trashId),
        ) { record -> ops.deletePermanently(record) }.second

        assertEquals(2, outcome.succeeded)
        assertTrue(outcome.isFullSuccess)
        assertEquals(listOf(kept.trashId), ops.trashed().map { it.trashId })
    }

    @Test
    fun `an id that is no longer in the trash counts as failed`() {
        setUp()
        val record = ops.trash(note("a.md"))
        ops.deletePermanently(record.trashId)

        val (restored, outcome) = bulkOverTrash(ops, listOf(record.trashId)) { ops.restore(it) }

        assertEquals(0, outcome.succeeded)
        assertEquals(1, outcome.failed)
        assertTrue(restored.isEmpty())
    }
}
