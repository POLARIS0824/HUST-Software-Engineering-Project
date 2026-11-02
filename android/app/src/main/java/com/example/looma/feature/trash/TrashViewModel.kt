package com.example.looma.feature.trash

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.looma.data.note.NoteRepository
import com.example.looma.domain.model.TrashedNote
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 回收站：列出被删笔记，恢复时把文件搬回原路径 */
class TrashViewModel(
    private val noteRepository: NoteRepository,
) : ViewModel() {
    private val _uiState = MutableStateFlow(TrashUiState())
    val uiState: StateFlow<TrashUiState> = _uiState.asStateFlow()
    private var loadJob: Job? = null

    fun load() {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, loadFailed = false) }
            try {
                val items = noteRepository.listTrashedNotes().map { it.toEntry() }
                _uiState.update { it.copy(items = items, isLoading = false) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update { it.copy(items = emptyList(), isLoading = false, loadFailed = true) }
            }
        }
    }

    /**
     * 恢复一篇笔记。失败时保留在列表里，让用户先处理原位置的同名文件，
     * 所以无论成败都重新加载一次，只是提示不同。
     */
    fun restore(trashId: String, onFinished: (Boolean) -> Unit) {
        if (_uiState.value.restoringId != null) return
        viewModelScope.launch {
            _uiState.update { it.copy(restoringId = trashId) }
            var restored = false
            try {
                noteRepository.restoreNote(trashId)
                restored = true
                load()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                load()
            } finally {
                _uiState.update { it.copy(restoringId = null) }
                onFinished(restored)
            }
        }
    }

    /** 彻底删除一条回收站记录；失败时保留在列表里 */
    fun deletePermanently(trashId: String, onFinished: (Boolean) -> Unit) {
        if (_uiState.value.restoringId != null) return
        viewModelScope.launch {
            _uiState.update { it.copy(restoringId = trashId) }
            var deleted = false
            try {
                noteRepository.deleteTrashedNote(trashId)
                deleted = true
                load()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                load()
            } finally {
                _uiState.update { it.copy(restoringId = null) }
                onFinished(deleted)
            }
        }
    }

    companion object {
        fun factory(noteRepository: NoteRepository): ViewModelProvider.Factory = viewModelFactory {
            initializer { TrashViewModel(noteRepository) }
        }
    }
}

private fun TrashedNote.toEntry(): TrashEntry = TrashEntry(
    trashId = trashId,
    title = title.ifBlank { originalPath.relativePath },
    excerpt = excerpt,
    originalPath = originalPath.relativePath,
    deletedAt = deletedAt,
)
