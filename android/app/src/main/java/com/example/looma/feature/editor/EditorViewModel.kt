package com.example.looma.feature.editor

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.looma.data.note.NoteRepository
import com.example.looma.domain.model.NoteId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** One instance per editor NavEntry, injected with the NoteId supplied by the route. */
class EditorViewModel(
    private val repository: NoteRepository,
    private val noteId: NoteId,
) : ViewModel() {
    private val _uiState = MutableStateFlow(EditorUiState())
    val uiState: StateFlow<EditorUiState> = _uiState.asStateFlow()

    init { load() }

    fun load() {
        // Do not replace a local unsaved draft when the user manually retries.
        if (_uiState.value.isDirty) return
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            try {
                val note = repository.readNote(noteId)
                _uiState.update {
                    it.copy(
                        title = note.title,
                        content = note.content,
                        savedContent = note.content,
                        modifiedAt = note.modifiedAt,
                        isLoading = false,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(isLoading = false, errorMessage = e.localizedMessage ?: "打开笔记失败")
                }
            }
        }
    }

    fun onContentChanged(value: String) {
        _uiState.update { it.copy(content = value, errorMessage = null) }
    }

    /** Save is explicit in this first editor version; the caller may navigate only after success. */
    fun save(onSaved: () -> Unit = {}) {
        val current = _uiState.value
        if (current.isLoading || current.isSaving) return
        if (!current.isDirty) {
            onSaved()
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true, errorMessage = null) }
            try {
                val updated = repository.saveNote(
                    id = noteId,
                    content = current.content,
                    expectedModifiedAt = current.modifiedAt,
                )
                _uiState.update { state ->
                    state.copy(
                        title = updated.title,
                        savedContent = current.content,
                        modifiedAt = updated.modifiedAt,
                        isSaving = false,
                    )
                }
                // If more text was typed while saving, do not navigate away.
                if (_uiState.value.isDirty) return@launch
                onSaved()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isSaving = false,
                        errorMessage = e.localizedMessage ?: "保存失败，请检查笔记是否被外部修改",
                    )
                }
            }
        }
    }

    companion object {
        fun factory(
            repository: NoteRepository,
            noteId: NoteId,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer { EditorViewModel(repository, noteId) }
        }
    }
}
