package com.example.looma.feature.note

import com.example.looma.domain.model.NoteRevision
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NoteUiStateTest {

    @Test
    fun `a new note nobody wrote in is discarded when the setting is off`() {
        assertTrue(
            NoteUiState(content = "", isNewNote = true, keepEmptyNotes = false).willDiscardEmpty,
        )
    }

    @Test
    fun `a title on its own is content and is kept`() {
        assertFalse(
            NoteUiState(
                content = "# 周会\n\n",
                isNewNote = true,
                keepEmptyNotes = false,
            ).willDiscardEmpty,
        )
    }

    @Test
    fun `a body on its own is content and is kept`() {
        assertFalse(
            NoteUiState(
                content = "随手记一句",
                isNewNote = true,
                keepEmptyNotes = false,
            ).willDiscardEmpty,
        )
    }

    @Test
    fun `an existing blank note is never discarded`() {
        assertFalse(
            NoteUiState(content = "", isNewNote = false, keepEmptyNotes = false).willDiscardEmpty,
        )
    }

    @Test
    fun `the setting keeps a blank new note`() {
        assertFalse(
            NoteUiState(content = "", isNewNote = true, keepEmptyNotes = true).willDiscardEmpty,
        )
    }

    @Test
    fun `a conflict keeps the file on disk`() {
        assertFalse(
            NoteUiState(
                content = "",
                isNewNote = true,
                keepEmptyNotes = false,
                isConflict = true,
            ).willDiscardEmpty,
        )
    }

    @Test
    fun `a clean note adopts a different revision from disk`() {
        val state = NoteUiState(
            content = "旧内容",
            savedContent = "旧内容",
            isLoading = false,
            revision = revision("a"),
        )

        assertTrue(state.canAdoptDiskRevision(revision("b")))
        assertFalse(state.canAdoptDiskRevision(revision("a")))
    }

    @Test
    fun `local edits are never replaced by the disk version`() {
        val state = NoteUiState(
            content = "新内容",
            savedContent = "旧内容",
            isLoading = false,
            revision = revision("a"),
        )

        assertFalse(state.canAdoptDiskRevision(revision("b")))
    }

    @Test
    fun `a conflict or pending save keeps the editor buffer`() {
        val base = NoteUiState(
            content = "内容",
            savedContent = "内容",
            isLoading = false,
            revision = revision("a"),
        )

        assertFalse(base.copy(isConflict = true).canAdoptDiskRevision(revision("b")))
        assertFalse(base.copy(saveFailed = true).canAdoptDiskRevision(revision("b")))
        assertFalse(base.copy(saveState = NoteSaveState.Saving).canAdoptDiskRevision(revision("b")))
    }

    private fun revision(seed: String): NoteRevision =
        NoteRevision.of(seed.toByteArray(Charsets.UTF_8))
}
