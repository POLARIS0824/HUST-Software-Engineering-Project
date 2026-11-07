package com.example.looma.feature.trash

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.looma.data.note.NoteRepository
import com.example.looma.data.note.TrashBulkOutcome
import com.example.looma.domain.model.NoteId
import com.example.looma.domain.model.TrashedNote
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

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
                _uiState.update {
                    it.copy(items = emptyList(), isLoading = false, loadFailed = true)
                }
            }
        }
    }

    fun restore(trashId: String, onFinished: (NoteId?) -> Unit) {
        if (_uiState.value.busy != TrashBusy.None) return
        _uiState.update { it.copy(busy = TrashBusy.Row(trashId)) }
        viewModelScope.launch {
            var restored: NoteId? = null
            try {
                restored = noteRepository.restoreNote(trashId).id
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
            } finally {
                _uiState.update { it.copy(busy = TrashBusy.None) }
                load()
                onFinished(restored)
            }
        }
    }

    fun trashAgain(id: NoteId, onFinished: (Boolean) -> Unit) {
        if (_uiState.value.busy != TrashBusy.None) return
        _uiState.update { it.copy(busy = TrashBusy.Row(id.path.relativePath)) }
        viewModelScope.launch {
            var undone = false
            try {
                noteRepository.deleteNote(id)
                undone = true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
            } finally {
                _uiState.update { it.copy(busy = TrashBusy.None) }
                load()
                onFinished(undone)
            }
        }
    }

    fun deletePermanently(trashId: String, onFinished: (Boolean) -> Unit) {
        if (_uiState.value.busy != TrashBusy.None) return
        _uiState.update { it.copy(busy = TrashBusy.Row(trashId)) }
        viewModelScope.launch {
            var deleted = false
            try {
                noteRepository.deleteTrashedNote(trashId)
                deleted = true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
            } finally {
                _uiState.update { it.copy(busy = TrashBusy.None) }
                load()
                onFinished(deleted)
            }
        }
    }

    fun restoreAll(onFinished: (TrashBulkOutcome?) -> Unit) {
        if (_uiState.value.busy != TrashBusy.None) return
        val trashIds = _uiState.value.items.map { it.trashId }
        _uiState.update { it.copy(busy = TrashBusy.Bulk) }
        viewModelScope.launch {
            var outcome: TrashBulkOutcome? = null
            try {
                outcome = noteRepository.restoreTrashedNotes(trashIds)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
            } finally {
                _uiState.update { it.copy(busy = TrashBusy.None) }
                load()
                onFinished(outcome)
            }
        }
    }

    fun emptyTrash(onFinished: (TrashBulkOutcome?) -> Unit) {
        if (_uiState.value.busy != TrashBusy.None) return
        val trashIds = _uiState.value.items.map { it.trashId }
        _uiState.update { it.copy(busy = TrashBusy.Bulk) }
        viewModelScope.launch {
            var outcome: TrashBulkOutcome? = null
            try {
                outcome = noteRepository.deleteTrashedNotes(trashIds)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
            } finally {
                _uiState.update { it.copy(busy = TrashBusy.None) }
                load()
                onFinished(outcome)
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
