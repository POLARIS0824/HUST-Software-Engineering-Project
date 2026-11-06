package com.example.looma.feature.home

import com.example.looma.domain.model.FolderPath
import com.example.looma.domain.model.NoteId
import com.example.looma.domain.model.NoteProperties
import com.example.looma.domain.model.NoteSummary
import java.util.Locale

sealed interface HomeError {
    data class UnreadableNotes(val count: Int) : HomeError
    data object LoadFailed : HomeError
    data object CreateFailed : HomeError
}

enum class HomeFileError {
    EMPTY_NAME,
    INVALID_NAME,
    NAME_TAKEN,
    RESTORE_CONFLICT,
    GENERIC,
}

sealed interface HomeSelection {
    data object None : HomeSelection

    data class Notes(val keys: Set<String>) : HomeSelection {
        companion object {
            fun of(keys: Set<String>): HomeSelection = if (keys.isEmpty()) None else Notes(keys)
        }
    }

    data class Folder(val item: HomeFolderItem) : HomeSelection

    val noteKeys: Set<String> get() = (this as? Notes)?.keys.orEmpty()
    val folder: HomeFolderItem? get() = (this as? Folder)?.item
}

sealed interface HomeUndo {
    data class RestoreNotes(val trashIds: List<String>) : HomeUndo

    data class RenameNote(val id: NoteId, val name: String) : HomeUndo

    data class MoveNotes(val moves: List<MovedNote>) : HomeUndo

    data class RenameFolder(val path: FolderPath, val name: String) : HomeUndo

    data class MoveFolder(val path: FolderPath, val parent: FolderPath) : HomeUndo

    data class DeleteFolder(val path: FolderPath) : HomeUndo
}

data class MovedNote(val id: NoteId, val folder: FolderPath)

data class HomeMessage(
    val notice: HomeNotice,
    val undo: HomeUndo? = null,
)

sealed interface HomeNotice {
    data class Deleted(val count: Int) : HomeNotice
    data class Restored(val count: Int) : HomeNotice
    data class Moved(val count: Int) : HomeNotice

    data class Renamed(val linksUpdated: Int, val linksFailed: Int = 0) : HomeNotice
    data object FolderCreated : HomeNotice
    data object FolderRenamed : HomeNotice
    data object FolderMoved : HomeNotice

    data class FolderDeleted(val notes: Int) : HomeNotice
    data object NameTaken : HomeNotice
    data object ActionFailed : HomeNotice
    data object InvalidMoveTarget : HomeNotice
    data object Undone : HomeNotice
    data object UndoFailed : HomeNotice
}

sealed interface HomeFileDialog {
    data class Rename(val initialName: String) : HomeFileDialog
    data class Properties(val properties: NoteProperties) : HomeFileDialog
    data object NewFolder : HomeFileDialog

    data class RenameFolder(val path: FolderPath, val initialName: String) : HomeFileDialog
    data class DeleteFolder(
        val path: FolderPath,
        val folderName: String,
        val noteCount: Int,
    ) : HomeFileDialog
}

data class HomeNoteItem(
    val summary: NoteSummary,
    val coverPath: String? = null,
    val markdownSnippet: String = "",
    val searchableText: String = "",
) {
    val id: NoteId get() = summary.id

    val listKey: String get() = "${id.vaultId.value}/${id.path.relativePath}"

    val fileName: String
        get() = id.path.relativePath
            .substringAfterLast('/')
            .removeSuffix(".md")
}

data class RecentReveal(val generation: Long, val noteId: NoteId)

internal fun RecentReveal.presentIn(recents: List<HomeRecentItem>): Boolean =
    recents.any { it.note.id == noteId }

internal fun HomeUiState.acknowledgeReveal(request: RecentReveal): HomeUiState =
    if (recentReveal == request) copy(recentReveal = null) else this

enum class HomeSort { LAST_MODIFIED, TITLE, TITLE_DESCENDING }

data class HomeUiState(
    val vaultName: String = "Looma",
    val vaultRootPath: String? = null,
    val notes: List<HomeNoteItem> = emptyList(),
    val searchResults: List<HomeNoteItem> = emptyList(),
    val query: String = "",
    val recentSearches: List<String> = emptyList(),
    val isGridMode: Boolean = true,
    val sort: HomeSort = HomeSort.LAST_MODIFIED,
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val isCreating: Boolean = false,
    val error: HomeError? = null,
    val createdNote: NoteId? = null,
    val noteToOpen: NoteId? = null,
    val recentReveal: RecentReveal? = null,
    val files: HomeFilesState = HomeFilesState(),
    val selection: HomeSelection = HomeSelection.None,
    val selectedNotes: List<HomeNoteItem> = emptyList(),
    val dialog: HomeFileDialog? = null,
    val dialogError: HomeFileError? = null,
    val isActionRunning: Boolean = false,
    val message: HomeMessage? = null,
) {
    val isBrowsing: Boolean get() = !files.isAtRoot
    val isSelecting: Boolean get() = selection != HomeSelection.None
    val selectedFolder: HomeFolderItem? get() = selection.folder
    val selectedKeys: Set<String> get() = selection.noteKeys
}

internal fun List<HomeNoteItem>.matching(query: String): List<HomeNoteItem> {
    val keyword = query.trim()
    if (keyword.isEmpty()) return this
    return filter { note ->
        note.summary.title.contains(keyword, ignoreCase = true) ||
            note.searchableText.contains(keyword, ignoreCase = true) ||
            note.summary.tags.any { it.contains(keyword, ignoreCase = true) }
    }
}

internal fun List<HomeNoteItem>.orderedBy(sort: HomeSort): List<HomeNoteItem> = when (sort) {
    HomeSort.LAST_MODIFIED -> sortedWith(compareByDescending<HomeNoteItem> { it.summary.modifiedAt }.thenBy { it.listKey })
    HomeSort.TITLE -> sortedWith(compareBy<HomeNoteItem> { it.summary.title.lowercase(Locale.ROOT) }.thenBy { it.listKey })
    HomeSort.TITLE_DESCENDING -> sortedWith(compareByDescending<HomeNoteItem> { it.summary.title.lowercase(Locale.ROOT) }.thenBy { it.listKey })
}

internal fun List<HomeNoteItem>.selected(
    selection: HomeSelection,
    sort: HomeSort,
): List<HomeNoteItem> = filter { it.listKey in selection.noteKeys }.orderedBy(sort)
