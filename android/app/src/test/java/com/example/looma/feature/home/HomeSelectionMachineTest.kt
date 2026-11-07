package com.example.looma.feature.home

import com.example.looma.domain.model.FolderPath
import com.example.looma.domain.model.NoteId
import com.example.looma.domain.model.NotePath
import com.example.looma.domain.model.NoteSummary
import com.example.looma.domain.model.VaultId
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Test

class HomeSelectionMachineTest {

    private val project = folder("项目")
    private val archive = folder("档案")
    private val note = note("计划")

    @Test
    fun `tapping a folder with nothing selected opens it`() {
        assertEquals(
            HomeSelectionOutcome.OpenFolder(project.path),
            reduceHomeSelection(HomeSelection.None, HomeSelectionEvent.FolderTapped(project)),
        )
    }

    @Test
    fun `tapping the selected folder deselects it`() {
        assertEquals(
            HomeSelectionOutcome.Changed(HomeSelection.None),
            reduceHomeSelection(
                HomeSelection.Folder(project),
                HomeSelectionEvent.FolderTapped(project),
            ),
        )
    }

    @Test
    fun `tapping another folder while one is selected selects it instead of opening`() {
        assertEquals(
            HomeSelectionOutcome.Changed(HomeSelection.Folder(archive)),
            reduceHomeSelection(
                HomeSelection.Folder(project),
                HomeSelectionEvent.FolderTapped(archive),
            ),
        )
    }

    @Test
    fun `long pressing a folder selects it`() {
        assertEquals(
            HomeSelectionOutcome.Changed(HomeSelection.Folder(archive)),
            reduceHomeSelection(HomeSelection.None, HomeSelectionEvent.FolderLongPressed(archive)),
        )
    }

    @Test
    fun `tapping the background clears the selection`() {
        assertEquals(
            HomeSelectionOutcome.Changed(HomeSelection.None),
            reduceHomeSelection(
                HomeSelection.Folder(project),
                HomeSelectionEvent.BackgroundTapped,
            ),
        )
        assertEquals(
            HomeSelectionOutcome.Changed(HomeSelection.None),
            reduceHomeSelection(
                HomeSelection.Notes(setOf(note.listKey)),
                HomeSelectionEvent.BackgroundTapped,
            ),
        )
    }

    @Test
    fun `tapping a note with nothing selected opens it`() {
        assertEquals(
            HomeSelectionOutcome.OpenNote(note.id),
            reduceHomeSelection(HomeSelection.None, HomeSelectionEvent.NoteTapped(note)),
        )
    }

    @Test
    fun `tapping a note while selecting toggles it and leaves folder selection`() {
        assertEquals(
            HomeSelectionOutcome.Changed(HomeSelection.Notes(setOf(note.listKey))),
            reduceHomeSelection(
                HomeSelection.Folder(project),
                HomeSelectionEvent.NoteTapped(note),
            ),
        )
        assertEquals(
            HomeSelectionOutcome.Changed(HomeSelection.None),
            reduceHomeSelection(
                HomeSelection.Notes(setOf(note.listKey)),
                HomeSelectionEvent.NoteTapped(note),
            ),
        )
    }

    @Test
    fun `tapping a folder while notes are selected switches to folder selection`() {
        assertEquals(
            HomeSelectionOutcome.Changed(HomeSelection.Folder(project)),
            reduceHomeSelection(
                HomeSelection.Notes(setOf(note.listKey)),
                HomeSelectionEvent.FolderTapped(project),
            ),
        )
    }

    private fun folder(name: String) = HomeFolderItem(path = FolderPath(name), name = name)

    private fun note(name: String) = HomeNoteItem(
        summary = NoteSummary(
            id = NoteId(VaultId("vault"), NotePath("$name.md")),
            title = name,
            excerpt = "",
            modifiedAt = Instant.parse("2026-11-01T00:00:00Z"),
            tags = emptySet(),
        ),
    )
}
