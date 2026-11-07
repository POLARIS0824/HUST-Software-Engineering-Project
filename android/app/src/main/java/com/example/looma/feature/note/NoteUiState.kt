package com.example.looma.feature.note

import com.example.looma.domain.markdown.ImageContext
import com.example.looma.domain.markdown.ToolbarItem
import com.example.looma.domain.model.NoteRevision
import com.example.looma.domain.wiki.WikiContext
import java.time.Instant

enum class NoteSaveState { Idle, Pending, Saving, Saved, Failed }

data class ImageInsertion(val snippet: String, val token: Long)

sealed interface NoteImageError {
    data object NotConfigured : NoteImageError
    data object UnsupportedImage : NoteImageError
    data object ImageTooLarge : NoteImageError
    data object Storage : NoteImageError
    data object Network : NoteImageError
    data class Rejected(val detail: String) : NoteImageError
    data object Unknown : NoteImageError
}

data class NoteUiState(
    val content: String = "",
    val savedContent: String = "",
    val modifiedAt: Instant? = null,
    val revision: NoteRevision = NoteRevision.Unknown,
    val conflictRevision: NoteRevision? = null,
    val leaveReady: Boolean = false,
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
    val showInlineTitle: Boolean = true,
    val toolbarOrder: List<ToolbarItem> = ToolbarItem.DEFAULT_ORDER,
    val isUploadingImage: Boolean = false,
    val imageInsertion: ImageInsertion? = null,
    val imageUploadError: NoteImageError? = null,
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

    fun canAdoptDiskRevision(diskRevision: NoteRevision): Boolean =
        !isLoading &&
            !isDirty &&
            !isConflict &&
            !saveFailed &&
            !leaveReady &&
            !leaveBlocked &&
            saveState != NoteSaveState.Saving &&
            diskRevision != revision

    private val isEmptyDraft: Boolean
        get() = content.isBlank()
}
