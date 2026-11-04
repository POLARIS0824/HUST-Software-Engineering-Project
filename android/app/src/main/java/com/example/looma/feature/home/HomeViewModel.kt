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

/**
 * 只负责“文件 / 仓库 → [HomeUiState]”。
 *
 * - 一次扫描得到笔记与目录，之后浏览、排序、搜索都只在这份内存快照上做
 * - 选择、重命名、移动、删除、新建文件夹都在这里落到仓库；成功后再刷新一次快照
 * - 移动的目标目录由系统目录选择器给出，ViewModel 只接收校验通过的相对路径；
 *   移动的是笔记还是文件夹由当前选择决定，调用方不必区分
 * - 每个成功的动作都带着自己的逆向数据，Snackbar 上的撤回就是把它再执行一遍
 * - 不持有导航回调：新建成功写 [HomeUiState.createdNote]，点开笔记写 [HomeUiState.noteToOpen]
 */
class HomeViewModel(
    private val vaultRepository: VaultRepository,
    private val noteRepository: NoteRepository,
    private val folderRepository: FolderRepository,
    private val searchHistoryRepository: SearchHistoryRepository,
) : ViewModel() {
    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()
    private var refreshJob: Job? = null
    private var index: HomeVaultIndex = HomeVaultIndex.Empty

    /** 本轮搜索是否已用磁盘正文替换过快照；刷新开始时复位，允许下一轮再补 */
    private var isTextHydrated = false
    private var hydrateJob: Job? = null

    /** 重新扫描笔记库；同一时刻只保留最近一次请求 */
    fun refresh() {
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
                    // 文件夹被外部删掉、笔记被外部移走时，选择要跟着收敛，不能指向不存在的东西
                    val selection = current.selection.normalized(alive)
                    current.copy(
                        vaultName = vault.name,
                        vaultRootPath = vault.rootPath,
                        notes = notes.orderedBy(current.sort),
                        searchResults = notes.matching(current.query).orderedBy(current.sort),
                        files = index.files(path, current.sort),
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
                // 索引已经上屏，再补最近搜索；拿到空表也不影响列表
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
        _uiState.update {
            it.copy(
                files = index.files(path, it.sort),
                selection = HomeSelection.None,
                selectedNotes = emptyList(),
            )
        }
    }

    /** 返回上一级；已经在根目录时什么也不做，交给系统返回键 */
    fun navigateUp() {
        val current = _uiState.value
        if (current.files.isAtRoot) return
        openFolder(current.files.path.parentFolder())
    }

    fun setQuery(value: String) {
        // 先用索引里的摘要 / 标签给出一份即时结果，正文补齐后再替换
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
                // 读正文失败就保留索引快照，搜索仍可按摘要工作
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

    /** IME 搜索键与点击结果时记录；空 query 不写历史 */
    fun recordSearch() {
        val query = _uiState.value.query
        if (query.isBlank()) return
        viewModelScope.launch {
            try {
                searchHistoryRepository.record(query)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 写历史失败不影响这次搜索本身
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

    /** HomeRoute 打开点到的笔记后调用，避免重复导航 */
    fun onNoteOpened() = _uiState.update { it.copy(noteToOpen = null) }

    /** 「最近」滚到最前面之后调用 */
    fun onRecentsScrolled() = _uiState.update { it.copy(scrollRecentsToStart = false) }

    // ---- 选择：所有点击都从这里进入，规则在 [reduceHomeSelection] 里 ----

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

    // ---- 对话框 ----

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

    // ---- 文件夹操作：长按进入和笔记相同的顶栏，三个动作都走对话框或目录选择器 ----

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
                // 空文件夹没有笔记可搬回来，也就没有可撤回的东西
                undo = HomeUndo.RestoreNotes(trashIds).takeIf { trashIds.isNotEmpty() },
            )
        }
    }

    // ---- 移动：笔记与文件夹共用系统目录选择器 ----

    /**
     * 系统目录选择器给出的目标；null 表示选到了笔记库外。
     *
     * 移动的是笔记还是文件夹由当前选择决定，所以调用方不用告诉 ViewModel。
     */
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
                // 记下移动后的新 id：重名会续号，撤回要按新路径搬
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

    /** Snackbar 上的撤回：按 [HomeUndo] 里记下的逆向数据把文件搬回去 */
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

    /**
     * 撤回只重放逆向动作，不做补偿逻辑：每一步失败都按整体失败上报。
     *
     * 删除的撤回是逐条恢复，只要有一条搬回来就算撤回了，其余留在回收站。
     */
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

    /** 最近搜索只是辅助信息，读失败就当作没有历史，不占用错误提示 */
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

    /**
     * 对话框里的确认动作：成功则关闭对话框、清空选择并刷新，失败把原因留在对话框上，
     * 用户可以改个名字接着试。[message] 由动作本身给出，因为它常常带着结果计数和撤回数据。
     */
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

    /** 刷新后路径可能已经不存在：笔记只留还活着的 key，文件夹不存在就退回未选择 */
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

/** 移动类动作失败时的提示：选错目标与重名要分开说 */
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
