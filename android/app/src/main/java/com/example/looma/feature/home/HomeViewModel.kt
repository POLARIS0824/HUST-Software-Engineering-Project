package com.example.looma.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.looma.data.note.NoteRepository
import com.example.looma.data.vault.VaultRepository
import com.example.looma.domain.model.NoteId
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
) : ViewModel() {
    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()
    private var refreshJob: Job? = null

    init { refresh() }

    fun refresh() {
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            try {
                val vault = vaultRepository.requireCurrentVault()
                val result = noteRepository.listNotes()
                val notes = result.items.map { item ->
                    HomeNoteItem(
                        summary = item.summary,
                        checklist = item.checklist.map {
                            HomeChecklistItem(it.text, it.checked)
                        },
                        coverPath = item.coverPath,
                        linkLabel = item.firstWikiLink,
                        searchableText = item.searchText,
                    )
                }
                _uiState.update {
                    it.copy(
                        vaultName = vault.name,
                        notes = notes,
                        isLoading = false,
                        errorMessage = if (result.unreadableCount > 0) {
                            "${result.unreadableCount} 个笔记文件无法读取"
                        } else null,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        notes = emptyList(),
                        isLoading = false,
                        errorMessage = e.localizedMessage ?: "加载笔记失败",
                    )
                }
            }
        }
    }

    fun setQuery(value: String) {
        _uiState.update { it.copy(query = value) }
    }

    fun toggleLayout() {
        _uiState.update { it.copy(isGridMode = !it.isGridMode) }
    }

    fun toggleSort() {
        _uiState.update {
            it.copy(
                sort = if (it.sort == HomeSort.LAST_MODIFIED) {
                    HomeSort.TITLE
                } else HomeSort.LAST_MODIFIED,
            )
        }
    }

    /** 点击新建后真正落盘，然后才回调 NoteId 交给导航层 */
    fun createNote(onCreated: (NoteId) -> Unit) {
        if (_uiState.value.isCreating) return
        viewModelScope.launch {
            _uiState.update { it.copy(isCreating = true, errorMessage = null) }
            try {
                val note = noteRepository.createNote()
                onCreated(note.id)
                refresh()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(errorMessage = e.localizedMessage ?: "创建笔记失败")
                }
            } finally {
                _uiState.update { it.copy(isCreating = false) }
            }
        }
    }

    companion object {
        fun factory(
            vaultRepository: VaultRepository,
            noteRepository: NoteRepository,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer { HomeViewModel(vaultRepository, noteRepository) }
        }
    }
}
