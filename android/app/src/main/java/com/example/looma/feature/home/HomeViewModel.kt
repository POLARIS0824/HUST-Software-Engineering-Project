package com.example.looma.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.looma.data.note.NoteListEntry
import com.example.looma.data.note.NoteRepository
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
 * - 排序与搜索在 ViewModel 里算好，UI 不做业务判断
 * - 不持有导航回调：新建成功后写 [HomeUiState.createdNote]，由 HomeRoute 消费
 * - 首次加载交给 HomeRoute 的 ON_RESUME 生命周期事件，不在 init 里再扫一次
 */
class HomeViewModel(
    private val vaultRepository: VaultRepository,
    private val noteRepository: NoteRepository,
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
                    current.copy(
                        vaultName = vault.name,
                        notes = notes.orderedBy(current.sort),
                        searchResults = notes.matching(current.query).orderedBy(current.sort),
                        files = index.files(path, current.sort),
                        isLoading = false,
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
                        isLoading = false,
                        error = HomeError.LoadFailed,
                    )
                }
            }
        }
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
        )
    }

    fun createNote() {
        if (_uiState.value.isCreating) return
        viewModelScope.launch {
            _uiState.update { it.copy(isCreating = true, error = null) }
            try {
                val folder = _uiState.value.files.path
                val note = noteRepository.createNote(folder = folder)
                refresh()
                _uiState.update { it.copy(createdNote = note.id) }
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

    companion object {
        fun factory(
            vaultRepository: VaultRepository,
            noteRepository: NoteRepository,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer { HomeViewModel(vaultRepository, noteRepository) }
        }
    }
}

private fun NoteListEntry.toHomeItem() = HomeNoteItem(
    summary = summary,
    coverPath = coverPath,
    markdownSnippet = markdownSnippetOf(searchText, summary.title),
    searchableText = searchText,
)
