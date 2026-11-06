package com.example.looma.feature.note

import com.example.looma.domain.markdown.EditorDocument
import com.example.looma.domain.markdown.ImageContext
import com.example.looma.domain.markdown.ToolbarItem
import com.example.looma.domain.wiki.WikiContext
import java.time.Instant

enum class NoteSaveState { Idle, Pending, Saving, Saved, Failed }

data class NoteUiState(
    val content: String = "",
    val savedContent: String = "",
    val modifiedAt: Instant? = null,
    val isLoading: Boolean = true,
    val loadFailed: Boolean = false,
    val saveState: NoteSaveState = NoteSaveState.Idle,
    val saveFailed: Boolean = false,
    val isConflict: Boolean = false,
    val leaveBlocked: Boolean = false,
    val keepEmptyNotes: Boolean = true,
    val isNewNote: Boolean = false,
    val wiki: WikiContext = WikiContext.Empty,
    val imageContext: ImageContext? = null,
    val restoredDraft: Boolean = false,
    val reloadToken: Int = 0,
    val toolbarOrder: List<ToolbarItem> = ToolbarItem.DEFAULT_ORDER,
) {
    val isDirty: Boolean get() = !isLoading && content != savedContent

    val needsLeaveGuard: Boolean
        get() = isDirty ||
            saveState == NoteSaveState.Pending ||
            saveState == NoteSaveState.Saving ||
            saveState == NoteSaveState.Failed

    val willDiscardEmpty: Boolean
        get() = isNewNote &&
            !keepEmptyNotes &&
            !isConflict &&
            isEmptyDraft

    private val isEmptyDraft: Boolean
        get() = EditorDocument.split(content).let { it.title.isBlank() && it.body.isBlank() }
}
