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
import com.example.looma.data.note.markdownSnippetOf
import com.example.looma.data.vault.VaultRepository
import com.example.looma.domain.model.FolderPath
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 只负责“文件 / 仓库 → [HomeUiState]”。
 *
 * - 一次扫描得到笔记与目录，之后浏览、排序、搜索都只在这份内存快照上做
 * - 选择、重命名、移动、删除、新建文件夹都在这里落到仓库；成功后再刷新一次快照
 * - 移动的目标目录由系统目录选择器给出，ViewModel 只接收校验通过的相对路径
 * - 不持有导航回调：新建成功后写 [HomeUiState.createdNote]，由 HomeRoute 消费
 */
class HomeViewModel(
    private val vaultRepository: VaultRepository,
    private val noteRepository: NoteRepository,
    private val folderRepository: FolderRepository,
) : ViewModel() {
    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()
    private var refreshJob: Job? = null
    private var index: HomeVaultIndex = HomeVaultIndex.Empty

    /** 重新扫描笔记库；同一时刻只保留最近一次请求 */
    fun refresh() {
        refreshJob?.cancel()
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
                    val selection = current.selection.filterTo(mutableSetOf()) { it in alive }
                    current.copy(
                        vaultName = vault.name,
                        vaultRootPath = vault.rootPath,
                        notes = notes.orderedBy(current.sort),
                        searchResults = notes.matching(current.query).orderedBy(current.sort),
                        files = index.files(path, current.sort),
                        selection = selection,
                        selectedItems = notes.selected(selection, current.sort),
                        isLoading = false,
                        isRefreshing = false,
                        error = if (result.unreadableCount > 0) {
                            HomeError.UnreadableNotes(result.unreadableCount)
                        } else {
                            null
                        },
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                index = HomeVaultIndex.Empty
                _uiState.update {
                    it.copy(
                        notes = emptyList(),
                        searchResults = emptyList(),
                        files = HomeFilesState(),
                        selection = emptySet(),
                        selectedItems = emptyList(),
                        isLoading = false,
                        isRefreshing = false,
                        error = HomeError.LoadFailed,
                    )
                }
            }
        }
    }

    /** 下拉刷新：只有用户自己触发时才转下拉指示器 */
    fun refreshFromUser() {
        if (_uiState.value.isCreating || _uiState.value.isActionRunning) return
        _uiState.update { it.copy(isRefreshing = true) }
        refresh()
    }

    /** 打开文件夹；未知路径直接忽略，避免把状态带到快照之外 */
    fun openFolder(path: FolderPath) {
        if (!index.contains(path)) return
        _uiState.update { it.copy(files = index.files(path, it.sort)) }
    }

    /** 返回上一级；已经在根目录时什么也不做，交给系统返回键 */
    fun navigateUp() {
        val current = _uiState.value
        if (current.files.isAtRoot) return
        openFolder(current.files.path.parentFolder())
    }

    fun setQuery(value: String) {
        _uiState.update {
            it.copy(query = value, searchResults = it.notes.matching(value).orderedBy(it.sort))
        }
    }

    fun toggleLayout() = _uiState.update { it.copy(isGridMode = !it.isGridMode) }

    fun setSort(sort: HomeSort) = _uiState.update {
        it.copy(
            sort = sort,
            notes = it.notes.orderedBy(sort),
            searchResults = it.searchResults.orderedBy(sort),
            files = index.files(it.files.path, sort),
            selectedItems = it.selectedItems.orderedBy(sort),
        )
    }

    fun createNote(title: String) {
        if (_uiState.value.isCreating) return
        viewModelScope.launch {
            _uiState.update { it.copy(isCreating = true, error = null) }
            try {
                val folder = _uiState.value.files.path
                val note = noteRepository.createNote(title = title, folder = folder)
                refresh()
                _uiState.update { it.copy(createdNote = note.id, scrollRecentsToStart = true) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update { it.copy(error = HomeError.CreateFailed) }
            } finally {
                _uiState.update { it.copy(isCreating = false) }
            }
        }
    }

    /** HomeRoute 打开新笔记后调用，避免重复导航 */
    fun onCreatedNoteOpened() = _uiState.update { it.copy(createdNote = null) }

    /** 「最近」滚到最前面之后调用 */
    fun onRecentsScrolled() = _uiState.update { it.copy(scrollRecentsToStart = false) }

    // ---- 选择 ----

    fun toggleSelection(item: HomeNoteItem) = _uiState.update { state ->
        val selection = if (item.listKey in state.selection) {
            state.selection - item.listKey
        } else {
            state.selection + item.listKey
        }
        state.copy(
            selection = selection,
            selectedItems = state.notes.selected(selection, state.sort),
        )
    }

    fun clearSelection() = _uiState.update {
        it.copy(selection = emptySet(), selectedItems = emptyList(), dialog = null, dialogError = null)
    }

    // ---- 对话框 ----

    fun startRename() {
        val item = _uiState.value.singleSelection() ?: return
        _uiState.update {
            it.copy(dialog = HomeFileDialog.Rename(item.fileName), dialogError = null)
        }
    }

    fun startProperties() {
        val item = _uiState.value.singleSelection() ?: return
        viewModelScope.launch {
            try {
                val properties = noteRepository.properties(item.id)
                _uiState.update {
                    it.copy(dialog = HomeFileDialog.Properties(properties), dialogError = null)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update { it.copy(message = HomeMessage.ActionFailed) }
            }
        }
    }

    fun startNewFolder() = _uiState.update {
        it.copy(dialog = HomeFileDialog.NewFolder, dialogError = null)
    }

    fun dismissDialog() = _uiState.update { it.copy(dialog = null, dialogError = null) }

    fun confirmRename(name: String) {
        val item = _uiState.value.singleSelection() ?: return
        val trimmed = name.trim()
        if (trimmed.isEmpty()) {
            _uiState.update { it.copy(dialogError = HomeFileError.EMPTY_NAME) }
            return
        }
        runDialogAction(HomeMessage.Renamed) { noteRepository.renameNote(item.id, trimmed) }
    }

    fun confirmNewFolder(name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) {
            _uiState.update { it.copy(dialogError = HomeFileError.EMPTY_NAME) }
            return
        }
        runDialogAction(HomeMessage.FolderCreated) {
            folderRepository.createFolder(_uiState.value.files.path, trimmed)
        }
    }

    /** 系统目录选择器给出的目标；调用方已经确认它在笔记库内 */
    fun moveSelectedTo(target: FolderPath) {
        val moving = _uiState.value.selectedItems
        if (moving.isEmpty()) return
        if (_uiState.value.isActionRunning) return
        viewModelScope.launch {
            _uiState.update { it.copy(isActionRunning = true) }
            try {
                moving.forEach { noteRepository.moveNote(it.id, target) }
                finishAction(HomeMessage.Moved(moving.size))
            } catch (e: CancellationException) {
                throw e
            } catch (e: VaultFileException) {
                _uiState.update {
                    it.copy(
                        isActionRunning = false,
                        message = if (e.failure == VaultFileFailure.INVALID_PATH) {
                            HomeMessage.InvalidMoveTarget
                        } else {
                            HomeMessage.ActionFailed
                        },
                    )
                }
                refresh()
            } catch (e: Exception) {
                _uiState.update { it.copy(isActionRunning = false, message = HomeMessage.ActionFailed) }
                refresh()
            }
        }
    }

    /** 目录选择器返回了笔记库外的地方 */
    fun onMoveTargetRejected() = _uiState.update {
        it.copy(message = HomeMessage.InvalidMoveTarget)
    }

    fun deleteSelected() {
        val deleting = _uiState.value.selectedItems
        if (deleting.isEmpty()) return
        viewModelScope.launch {
            _uiState.update { it.copy(isActionRunning = true, dialogError = null) }
            try {
                val trashIds = deleting.map { noteRepository.deleteNote(it.id).trashId }
                finishAction(HomeMessage.Deleted(deleting.size, trashIds))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(isActionRunning = false, message = HomeMessage.ActionFailed)
                }
                refresh()
            }
        }
    }

    /** Snackbar 上的撤回：把刚删掉的笔记从回收站搬回原路径 */
    fun undoDelete(trashIds: List<String>) {
        if (trashIds.isEmpty()) return
        viewModelScope.launch {
            var restored = 0
            trashIds.forEach { trashId ->
                runCatching { noteRepository.restoreNote(trashId) }
                    .onSuccess { restored++ }
            }
            refresh()
            _uiState.update {
                it.copy(
                    message = if (restored > 0) {
                        HomeMessage.Restored(restored)
                    } else {
                        HomeMessage.ActionFailed
                    },
                )
            }
        }
    }

    fun onMessageShown() = _uiState.update { it.copy(message = null) }

    /**
     * 对话框里的确认动作：成功则关闭对话框、清空选择并刷新，失败把原因留在对话框上，
     * 用户可以改个名字接着试。
     */
    private fun runDialogAction(message: HomeMessage, block: suspend () -> Unit) {
        if (_uiState.value.isActionRunning) return
        viewModelScope.launch {
            _uiState.update { it.copy(isActionRunning = true, dialogError = null) }
            try {
                block()
                finishAction(message)
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
                selection = emptySet(),
                selectedItems = emptyList(),
                message = message,
            )
        }
    }

    private fun HomeUiState.singleSelection(): HomeNoteItem? =
        selectedItems.singleOrNull()

    companion object {
        fun factory(
            vaultRepository: VaultRepository,
            noteRepository: NoteRepository,
            folderRepository: FolderRepository,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer { HomeViewModel(vaultRepository, noteRepository, folderRepository) }
        }
    }
}

private fun List<HomeNoteItem>.selected(
    selection: Set<String>,
    sort: HomeSort,
): List<HomeNoteItem> = filter { it.listKey in selection }.orderedBy(sort)

private fun VaultFileFailure.toHomeFileError(): HomeFileError = when (this) {
    VaultFileFailure.INVALID_NAME -> HomeFileError.INVALID_NAME
    VaultFileFailure.NAME_TAKEN, VaultFileFailure.ALREADY_EXISTS -> HomeFileError.NAME_TAKEN
    VaultFileFailure.RESTORE_CONFLICT -> HomeFileError.RESTORE_CONFLICT
    else -> HomeFileError.GENERIC
}

private fun NoteListEntry.toHomeItem() = HomeNoteItem(
    summary = summary,
    coverPath = coverPath,
    markdownSnippet = markdownSnippetOf(searchText, summary.title),
    searchableText = searchText,
)
