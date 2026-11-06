package com.example.looma.feature.note

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
}
