package com.example.looma.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.looma.data.folder.FolderRepository
import com.example.looma.data.note.NoteListEntry
import com.example.looma.data.note.NoteRepository
import com.example.looma.data.note.VaultFileException
import com.example.looma.data.note.VaultFileFailure
import com.example.looma.data.search.SearchHistoryRepository
import com.example.looma.data.vault.VaultRepository
import com.example.looma.domain.model.FolderPath
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class HomeViewModel(
    private val vaultRepository: VaultRepository,
    private val noteRepository: NoteRepository,
    private val folderRepository: FolderRepository,
    private val searchHistoryRepository: SearchHistoryRepository,
) : ViewModel() {
    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()
    private var refreshJob: Job? = null
    private var revealGeneration = 0L
    private var index: HomeVaultIndex = HomeVaultIndex.Empty

    private var isTextHydrated = false
    private var hydrateJob: Job? = null

    fun refresh(): Job {
        refreshJob?.cancel()
        hydrateJob?.cancel()
        hydrateJob = null
        isTextHydrated = false
        refreshJob = viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            try {
                val vault = vaultRepository.requireCurrentVault()
                val result = noteRepository.listNotes()
                val notes = result.items.map { it.toHomeItem() }
                index = HomeVaultIndex.build(result.folders, notes)
                _uiState.update { current ->
                    val path = current.files.path
                        .takeIf(index::contains)
                        ?: FolderPath.Root
                    val alive = notes.mapTo(mutableSetOf()) { it.listKey }
                    val selection = current.selection.normalized(alive)
                    current.copy(
                        vaultName = vault.name,
                        vaultRootPath = vault.rootPath,
                        notes = notes.orderedBy(current.sort),
                        searchResults = notes.matching(current.query).orderedBy(current.sort),
                        files = index.files(path, current.sort),
                        recentReveal = current.recentReveal?.takeIf { request ->
                            request.presentIn(index.recents())
                        },
                        selection = selection,
                        selectedNotes = notes.selected(selection, current.sort),
                        isLoading = false,
                        isRefreshing = false,
                        error = if (result.unreadableCount > 0) {
                            HomeError.UnreadableNotes(result.unreadableCount)
                        } else {
                            null
                        },
                    )
                }
                loadRecentSearches()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                index = HomeVaultIndex.Empty
                _uiState.update {
                    it.copy(
                        notes = emptyList(),
                        searchResults = emptyList(),
                        files = HomeFilesState(),
                        selection = HomeSelection.None,
                        selectedNotes = emptyList(),
                        isLoading = false,
                        isRefreshing = false,
                        error = HomeError.LoadFailed,
                    )
                }
            }
        }
        return refreshJob!!
    }

    fun refreshFromUser() {
        if (_uiState.value.isCreating || _uiState.value.isActionRunning) return
        _uiState.update { it.copy(isRefreshing = true) }
        refresh()
    }

    fun openFolder(path: FolderPath) {
        if (!index.contains(path)) return
        _uiState.update {
            it.copy(
                files = index.files(path, it.sort),
                selection = HomeSelection.None,
                selectedNotes = emptyList(),
            )
        }
    }

    fun navigateUp() {
        val current = _uiState.value
        if (current.files.isAtRoot) return
        openFolder(current.files.path.parentFolder())
    }

    fun setQuery(value: String) {
        _uiState.update {
            it.copy(query = value, searchResults = it.notes.matching(value).orderedBy(it.sort))
        }
        if (value.isBlank() || isTextHydrated) return
        isTextHydrated = true
        hydrateJob = viewModelScope.launch {
            val notes = _uiState.value.notes
            if (notes.isEmpty()) return@launch
            val texts = try {
                noteRepository.readTexts(notes.map { it.id })
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return@launch
            }
            if (texts.isEmpty()) return@launch
            _uiState.update { current ->
                val hydrated = current.notes.map { note ->
                    texts[note.id]?.let { text -> note.copy(searchableText = text) } ?: note
                }
                current.copy(
                    notes = hydrated,
                    searchResults = hydrated.matching(current.query).orderedBy(current.sort),
                )
            }
        }
    }

    fun recordSearch() {
        val query = _uiState.value.query
        if (query.isBlank()) return
        viewModelScope.launch {
            try {
                searchHistoryRepository.record(query)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
            }
            loadRecentSearches()
        }
    }

    fun toggleLayout() = _uiState.update { it.copy(isGridMode = !it.isGridMode) }

    fun setSort(sort: HomeSort) = _uiState.update {
        it.copy(
            sort = sort,
            notes = it.notes.orderedBy(sort),
            searchResults = it.searchResults.orderedBy(sort),
            files = index.files(it.files.path, sort),
            selectedNotes = it.selectedNotes.orderedBy(sort),
        )
    }

    fun createNote(title: String) {
        if (_uiState.value.isCreating) return
        viewModelScope.launch {
            _uiState.update { it.copy(isCreating = true, error = null) }
            try {
                val folder = _uiState.value.files.path
                val note = noteRepository.createNote(title = title, folder = folder)
                val request = RecentReveal(++revealGeneration, note.id)
                _uiState.update { it.copy(createdNote = note.id, recentReveal = request) }
                refresh().join()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update { it.copy(error = HomeError.CreateFailed) }
            } finally {
                _uiState.update { it.copy(isCreating = false) }
            }
        }
    }

    fun onCreatedNoteOpened() = _uiState.update { it.copy(createdNote = null) }

    fun onNoteOpened() = _uiState.update { it.copy(noteToOpen = null) }

    fun onRecentRevealed(request: RecentReveal) = _uiState.update { it.acknowledgeReveal(request) }

    fun onSelectionEvent(event: HomeSelectionEvent) {
        val current = _uiState.value
        if (current.isActionRunning) return
        when (val outcome = reduceHomeSelection(current.selection, event)) {
            is HomeSelectionOutcome.Changed -> _uiState.update {
                it.copy(
                    selection = outcome.selection,
                    selectedNotes = it.notes.selected(outcome.selection, it.sort),
                    dialog = null,
                    dialogError = null,
                )
            }
            is HomeSelectionOutcome.OpenFolder -> openFolder(outcome.path)
            is HomeSelectionOutcome.OpenNote -> _uiState.update { it.copy(noteToOpen = outcome.id) }
        }
    }

    fun clearSelection() = _uiState.update {
        it.copy(
            selection = HomeSelection.None,
            selectedNotes = emptyList(),
            dialog = null,
            dialogError = null,
        )
    }

    fun startRename() {
        val item = _uiState.value.singleNote() ?: return
        _uiState.update {
            it.copy(dialog = HomeFileDialog.Rename(item.fileName), dialogError = null)
        }
    }

    fun startProperties() {
        val item = _uiState.value.singleNote() ?: return
        viewModelScope.launch {
            try {
                val properties = noteRepository.properties(item.id)
                _uiState.update {
                    it.copy(dialog = HomeFileDialog.Properties(properties), dialogError = null)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(message = HomeMessage(HomeNotice.ActionFailed))
                }
            }
        }
    }

    fun startNewFolder() = _uiState.update {
        it.copy(dialog = HomeFileDialog.NewFolder, dialogError = null)
    }

    fun dismissDialog() = _uiState.update { it.copy(dialog = null, dialogError = null) }

    fun confirmRename(name: String) {
        val item = _uiState.value.singleNote() ?: return
        val trimmed = name.trim()
        if (trimmed.isEmpty()) {
            _uiState.update { it.copy(dialogError = HomeFileError.EMPTY_NAME) }
            return
        }
        val previousName = item.fileName
        runDialogAction {
            val outcome = noteRepository.renameNote(item.id, trimmed)
            HomeMessage(
                notice = HomeNotice.Renamed(outcome.linksUpdated, outcome.linksFailed),
                undo = HomeUndo.RenameNote(outcome.note.id, previousName),
            )
        }
    }

    fun confirmNewFolder(name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) {
            _uiState.update { it.copy(dialogError = HomeFileError.EMPTY_NAME) }
            return
        }
        runDialogAction {
            val folder = folderRepository.createFolder(_uiState.value.files.path, trimmed)
            HomeMessage(
                notice = HomeNotice.FolderCreated,
                undo = HomeUndo.DeleteFolder(folder.path),
            )
        }
    }

    fun startFolderRename() {
        val folder = _uiState.value.selectedFolder ?: return
        _uiState.update {
            it.copy(
                dialog = HomeFileDialog.RenameFolder(folder.path, folder.name),
                dialogError = null,
            )
        }
    }

    fun startFolderDelete() {
        val folder = _uiState.value.selectedFolder ?: return
        _uiState.update {
            it.copy(
                dialog = HomeFileDialog.DeleteFolder(
                    path = folder.path,
                    folderName = folder.name,
                    noteCount = index.noteCountUnder(folder.path),
                ),
                dialogError = null,
            )
        }
    }

    fun confirmRenameFolder(name: String) {
        val dialog = _uiState.value.dialog as? HomeFileDialog.RenameFolder ?: return
        val trimmed = name.trim()
        if (trimmed.isEmpty()) {
            _uiState.update { it.copy(dialogError = HomeFileError.EMPTY_NAME) }
            return
        }
        val previousName = dialog.path.relativePath.substringAfterLast('/')
        runDialogAction {
            val renamed = folderRepository.renameFolder(dialog.path, trimmed)
            HomeMessage(
                notice = HomeNotice.FolderRenamed,
                undo = HomeUndo.RenameFolder(renamed.path, previousName),
            )
        }
    }

    fun confirmDeleteFolder() {
        val dialog = _uiState.value.dialog as? HomeFileDialog.DeleteFolder ?: return
        runDialogAction {
            val trashIds = folderRepository.deleteFolder(dialog.path)
            HomeMessage(
                notice = HomeNotice.FolderDeleted(trashIds.size),
                undo = HomeUndo.RestoreNotes(trashIds).takeIf { trashIds.isNotEmpty() },
            )
        }
    }

    fun moveSelectionTo(target: FolderPath?) {
        if (target == null) {
            _uiState.update { it.copy(message = HomeMessage(HomeNotice.InvalidMoveTarget)) }
            return
        }
        val state = _uiState.value
        if (state.isActionRunning) return
        when (val selection = state.selection) {
            HomeSelection.None -> Unit
            is HomeSelection.Folder -> moveFolderTo(selection.item, target)
            is HomeSelection.Notes -> moveNotesTo(state.selectedNotes, target)
        }
    }

    private fun moveNotesTo(notes: List<HomeNoteItem>, target: FolderPath) {
        if (notes.isEmpty()) return
        viewModelScope.launch {
            _uiState.update { it.copy(isActionRunning = true) }
            try {
                val moves = notes.map { note ->
                    val outcome = noteRepository.moveNote(note.id, target)
                    MovedNote(outcome.note.id, note.id.path.parentFolder())
                }
                finishAction(
                    HomeMessage(
                        notice = HomeNotice.Moved(moves.size),
                        undo = HomeUndo.MoveNotes(moves),
                    ),
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: VaultFileException) {
                _uiState.update { it.copy(isActionRunning = false, message = e.toMessage()) }
                refresh()
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(isActionRunning = false, message = HomeMessage(HomeNotice.ActionFailed))
                }
                refresh()
            }
        }
    }

    private fun moveFolderTo(folder: HomeFolderItem, target: FolderPath) {
        viewModelScope.launch {
            _uiState.update { it.copy(isActionRunning = true) }
            try {
                val moved = folderRepository.moveFolder(folder.path, target)
                finishAction(
                    HomeMessage(
                        notice = HomeNotice.FolderMoved,
                        undo = HomeUndo.MoveFolder(moved.path, folder.path.parentFolder()),
                    ),
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: VaultFileException) {
                _uiState.update { it.copy(isActionRunning = false, message = e.toMessage()) }
                refresh()
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(isActionRunning = false, message = HomeMessage(HomeNotice.ActionFailed))
                }
                refresh()
            }
        }
    }

    fun deleteSelected() {
        val deleting = _uiState.value.selectedNotes
        if (deleting.isEmpty()) return
        viewModelScope.launch {
            _uiState.update { it.copy(isActionRunning = true, dialogError = null) }
            try {
                val trashIds = deleting.map { noteRepository.deleteNote(it.id).trashId }
                finishAction(
                    HomeMessage(
                        notice = HomeNotice.Deleted(deleting.size),
                        undo = HomeUndo.RestoreNotes(trashIds),
                    ),
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(isActionRunning = false, message = HomeMessage(HomeNotice.ActionFailed))
                }
                refresh()
            }
        }
    }

    fun undo(action: HomeUndo) {
        if (_uiState.value.isActionRunning) return
        viewModelScope.launch {
            _uiState.update { it.copy(isActionRunning = true) }
            val undone = applyUndo(action)
            refresh()
            _uiState.update {
                it.copy(
                    isActionRunning = false,
                    message = HomeMessage(
                        if (undone) HomeNotice.Undone else HomeNotice.UndoFailed,
                    ),
                )
            }
        }
    }

    private suspend fun applyUndo(action: HomeUndo): Boolean = runCatching {
        when (action) {
            is HomeUndo.RestoreNotes -> action.trashIds.count { trashId ->
                runCatching { noteRepository.restoreNote(trashId) }.isSuccess
            } > 0

            is HomeUndo.RenameNote -> {
                noteRepository.renameNote(action.id, action.name)
                true
            }

            is HomeUndo.MoveNotes -> {
                action.moves.forEach { noteRepository.moveNote(it.id, it.folder) }
                true
            }

            is HomeUndo.RenameFolder -> {
                folderRepository.renameFolder(action.path, action.name)
                true
            }

            is HomeUndo.MoveFolder -> {
                folderRepository.moveFolder(action.path, action.parent)
                true
            }

            is HomeUndo.DeleteFolder -> {
                folderRepository.deleteFolder(action.path)
                true
            }
        }
    }.getOrDefault(false)

    fun onMessageShown() = _uiState.update { it.copy(message = null) }

    private suspend fun loadRecentSearches() {
        val recent = try {
            searchHistoryRepository.recent()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emptyList()
        }
        _uiState.update { it.copy(recentSearches = recent) }
    }

    private fun runDialogAction(message: suspend () -> HomeMessage) {
        if (_uiState.value.isActionRunning) return
        viewModelScope.launch {
            _uiState.update { it.copy(isActionRunning = true, dialogError = null) }
            try {
                finishAction(message())
            } catch (e: CancellationException) {
                throw e
            } catch (e: VaultFileException) {
                _uiState.update {
                    it.copy(isActionRunning = false, dialogError = e.failure.toHomeFileError())
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(isActionRunning = false, dialogError = HomeFileError.GENERIC)
                }
            }
        }
    }

    private suspend fun finishAction(message: HomeMessage) {
        refresh()
        _uiState.update {
            it.copy(
                isActionRunning = false,
                dialog = null,
                dialogError = null,
                selection = HomeSelection.None,
                selectedNotes = emptyList(),
                message = message,
            )
        }
    }

    private fun HomeSelection.normalized(aliveNotes: Set<String>): HomeSelection = when (this) {
        HomeSelection.None -> this
        is HomeSelection.Notes -> HomeSelection.Notes.of(keys.filterTo(mutableSetOf()) { it in aliveNotes })
        is HomeSelection.Folder -> if (index.contains(item.path)) this else HomeSelection.None
    }

    private fun HomeUiState.singleNote(): HomeNoteItem? = selectedNotes.singleOrNull()

    companion object {
        fun factory(
            vaultRepository: VaultRepository,
            noteRepository: NoteRepository,
            folderRepository: FolderRepository,
            searchHistoryRepository: SearchHistoryRepository,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                HomeViewModel(
                    vaultRepository,
                    noteRepository,
                    folderRepository,
                    searchHistoryRepository,
                )
            }
        }
    }
}

private fun VaultFileFailure.toHomeFileError(): HomeFileError = when (this) {
    VaultFileFailure.INVALID_NAME -> HomeFileError.INVALID_NAME
    VaultFileFailure.NAME_TAKEN, VaultFileFailure.ALREADY_EXISTS -> HomeFileError.NAME_TAKEN
    VaultFileFailure.RESTORE_CONFLICT -> HomeFileError.RESTORE_CONFLICT
    else -> HomeFileError.GENERIC
}

private fun VaultFileException.toMessage(): HomeMessage = HomeMessage(
    when (failure) {
        VaultFileFailure.INVALID_PATH -> HomeNotice.InvalidMoveTarget
        VaultFileFailure.NAME_TAKEN, VaultFileFailure.ALREADY_EXISTS -> HomeNotice.NameTaken
        else -> HomeNotice.ActionFailed
    },
)

private fun NoteListEntry.toHomeItem() = HomeNoteItem(
    summary = summary,
    coverPath = coverPath,
    markdownSnippet = markdownSnippet,
    searchableText = searchText,
)
