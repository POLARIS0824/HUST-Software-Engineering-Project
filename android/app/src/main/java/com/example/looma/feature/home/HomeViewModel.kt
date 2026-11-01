package com.example.looma.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.looma.data.note.NoteListEntry
import com.example.looma.data.note.NoteRepository
import com.example.looma.data.vault.VaultRepository
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

    /** 重新扫描笔记库；同一时刻只保留最近一次请求 */
    fun refresh() {
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            try {
                val vault = vaultRepository.requireCurrentVault()
                val result = noteRepository.listNotes()
                val notes = result.items.map { it.toHomeItem() }
                _uiState.update { current ->
                    current.copy(
                        vaultName = vault.name,
                        notes = notes.orderedBy(current.sort),
                        searchResults = notes.matching(current.query).orderedBy(current.sort),
                        isLoading = false,
                        errorMessage = result.unreadableCount
                            .takeIf { count -> count > 0 }
                            ?.let { count -> "$count 个笔记文件无法读取" },
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        notes = emptyList(),
                        searchResults = emptyList(),
                        isLoading = false,
                        errorMessage = e.localizedMessage ?: "加载笔记失败",
                    )
                }
            }
        }
    }

    fun setQuery(value: String) {
        _uiState.update {
            it.copy(query = value, searchResults = it.notes.matching(value).orderedBy(it.sort))
        }
    }

    fun toggleLayout() = _uiState.update { it.copy(isGridMode = !it.isGridMode) }

    fun toggleSort() = _uiState.update {
        val sort = when (it.sort) {
            HomeSort.LAST_MODIFIED -> HomeSort.TITLE
            HomeSort.TITLE -> HomeSort.LAST_MODIFIED
        }
        it.copy(
            sort = sort,
            notes = it.notes.orderedBy(sort),
            searchResults = it.searchResults.orderedBy(sort),
        )
    }

    fun createNote() {
        if (_uiState.value.isCreating) return
        viewModelScope.launch {
            _uiState.update { it.copy(isCreating = true, errorMessage = null) }
            try {
                val note = noteRepository.createNote()
                refresh()
                _uiState.update { it.copy(createdNote = note.id) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update { it.copy(errorMessage = e.localizedMessage ?: "创建笔记失败") }
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
    checklist = metadata.checklist.take(5).map { HomeChecklistItem(it.text, it.checked) },
    coverPath = coverPath,
    linkLabel = metadata.firstWikiLink,
    searchableText = searchText,
)
