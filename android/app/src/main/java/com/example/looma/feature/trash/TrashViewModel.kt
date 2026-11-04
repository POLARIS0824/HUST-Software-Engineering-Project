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
                _uiState.update {
                    it.copy(items = emptyList(), isLoading = false, loadFailed = true)
                }
            }
        }
    }

    /**
     * 恢复一篇笔记。失败时保留在列表里，让用户先处理原位置的同名文件，
     * 所以无论成败都重新加载一次，只是提示不同。
     *
     * @param onFinished 恢复出来的笔记 id，失败为 null
     */
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
                // 冲突或 IO 失败只影响这一篇，列表照样刷新
            } finally {
                _uiState.update { it.copy(busy = TrashBusy.None) }
                load()
                onFinished(restored)
            }
        }
    }

    /**
     * 撤销一次恢复：把刚恢复的笔记重新丢回回收站。
     *
     * 撤销没有对应的回收站行，拿笔记路径占住互斥，避免和恢复、删除撞在一起。
     *
     * @param onFinished 是否撤销成功
     */
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
                // 笔记已经不在了，撤销就是失败，提示一下即可
            } finally {
                _uiState.update { it.copy(busy = TrashBusy.None) }
                load()
                onFinished(undone)
            }
        }
    }

    /** 彻底删除一条回收站记录；失败时保留在列表里 */
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
                // 删不掉就留在原地，刷新后用户还能再试
            } finally {
                _uiState.update { it.copy(busy = TrashBusy.None) }
                load()
                onFinished(deleted)
            }
        }
    }

    /**
     * 恢复当前列表里的全部笔记，清单在动手之前先快照。
     *
     * @param onFinished 成败计数；null 表示清单都没读出来，一篇都没动
     */
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
                // 整批没能开始，界面按“一篇都没恢复”提示
            } finally {
                _uiState.update { it.copy(busy = TrashBusy.None) }
                load()
                onFinished(outcome)
            }
        }
    }

    /** 清空回收站：快照同样在动手之前取，返回成败计数，null 表示整批没能开始 */
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
                // 删不掉就都留着，刷新后用户还能再试
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
