package com.example.looma.feature.home

import com.example.looma.domain.model.FolderPath
import com.example.looma.domain.model.NoteId
import com.example.looma.domain.model.NotePath
import com.example.looma.domain.model.NoteSummary
import com.example.looma.domain.model.VaultFolder
import com.example.looma.domain.model.VaultId
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeVaultIndexTest {

    private val vaultId = VaultId("vault")

    @Test
    fun `root shows folders first and notes of the root only`() {
        val index = indexOf(
            folders = listOf(folder("项目"), folder("笔记")),
            notes = listOf(note("根目录笔记"), note("项目周报", folder = "项目")),
        )

        val root = index.files(FolderPath.Root, HomeSort.TITLE)

        assertEquals(listOf("笔记", "项目"), root.folders.map { it.name })
        assertEquals(listOf("根目录笔记"), root.notes.map { it.summary.title })
        assertTrue(root.isAtRoot)
    }

    @Test
    fun `folder card counts direct children and previews their names`() {
        val index = indexOf(
            folders = listOf(folder("项目"), folder("项目/周报")),
            notes = listOf(
                note("周报 8", folder = "项目/周报"),
                note("项目计划", folder = "项目"),
            ),
        )

        val project = index.files(FolderPath.Root, HomeSort.TITLE).folders.single()

        assertEquals(2, project.itemCount)
        assertEquals("周报 · 项目计划", project.preview)
    }

    @Test
    fun `empty folder says so instead of showing a blank preview`() {
        val index = indexOf(folders = listOf(folder("空")), notes = emptyList())

        val empty = index.files(FolderPath.Root, HomeSort.TITLE).folders.single()

        assertEquals(0, empty.itemCount)
        assertEquals(EMPTY_FOLDER_TEXT, empty.preview)
    }

    @Test
    fun `contents of a nested folder only include its direct children`() {
        val index = indexOf(
            folders = listOf(folder("项目"), folder("项目/周报")),
            notes = listOf(
                note("项目计划", folder = "项目"),
                note("周报 8", folder = "项目/周报"),
            ),
        )

        val project = index.files(FolderPath("项目"), HomeSort.TITLE)

        assertEquals(listOf("周报"), project.folders.map { it.name })
        assertEquals(listOf("项目计划"), project.notes.map { it.summary.title })
        assertEquals(listOf("首页", "项目"), project.crumbs.map { it.label })
    }

    @Test
    fun `unknown path falls back to the root`() {
        val index = indexOf(folders = listOf(folder("项目")), notes = emptyList())

        val files = index.files(FolderPath("项目/已删除"), HomeSort.TITLE)

        assertTrue(files.isAtRoot)
        assertEquals(listOf("项目"), files.folders.map { it.name })
    }

    @Test
    fun `recents take the newest ten and keep them newest first`() {
        val notes = (0 until 12).map { offset ->
            note("笔记 $offset", modifiedAt = Instant.parse("2026-10-01T00:00:00Z").plusSeconds(offset * 3600L))
        }
        val index = indexOf(folders = emptyList(), notes = notes)

        val recents = index.recents()

        assertEquals(RECENT_LIMIT, recents.size)
        assertEquals("笔记 11", recents.first().note.summary.title)
        assertEquals("笔记 2", recents.last().note.summary.title)
    }

    @Test
    fun `recent snippet collapses whitespace and falls back to a placeholder`() {
        val index = indexOf(
            folders = emptyList(),
            notes = listOf(
                note(
                    "长文",
                    excerpt = "第一段\\n第二段   第三段 " + "很长".repeat(60),
                ),
                note("空白", excerpt = ""),
            ),
        )

        val recents = index.recents()
        val long = recents.first { it.note.summary.title == "长文" }
        val blank = recents.first { it.note.summary.title == "空白" }

        assertEquals(RECENT_SNIPPET_CHARS, long.snippet.length)
        assertTrue(long.snippet.startsWith("第一段 第二段 第三段"))
        assertEquals(EMPTY_NOTE_TEXT, blank.snippet)
    }

    @Test
    fun `a note's folder is created even when the scan missed the directory`() {
        val index = indexOf(folders = emptyList(), notes = listOf(note("孤儿", folder = "后来创建")))

        val root = index.files(FolderPath.Root, HomeSort.TITLE)

        assertEquals(listOf("后来创建"), root.folders.map { it.name })
        assertEquals(1, root.folders.single().itemCount)
    }

    private fun indexOf(notes: List<HomeNoteItem>, folders: List<VaultFolder>): HomeVaultIndex =
        HomeVaultIndex.build(folders, notes)

    private fun folder(path: String) = VaultFolder(
        vaultId = vaultId,
        path = FolderPath(path),
        name = path.substringAfterLast('/'),
    )

    private fun note(
        title: String,
        folder: String? = null,
        excerpt: String = "",
        modifiedAt: Instant = Instant.parse("2026-10-31T12:00:00Z"),
    ): HomeNoteItem {
        val path = if (folder == null) "$title.md" else "$folder/$title.md"
        return HomeNoteItem(
            summary = NoteSummary(
                id = NoteId(vaultId, NotePath(path)),
                title = title,
                excerpt = excerpt,
                modifiedAt = modifiedAt,
            ),
        )
    }
}
