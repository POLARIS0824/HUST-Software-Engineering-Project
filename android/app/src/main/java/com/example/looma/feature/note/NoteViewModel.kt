package com.example.looma.feature.note

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.looma.data.note.NoteChangedExternallyException
import com.example.looma.data.note.NoteRepository
import com.example.looma.domain.model.NoteId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 笔记页的"文档会话"：加载、自动保存、离开前冲刷。
 *
 * 阅读和编辑共用同一份缓冲区，所以不存在两个页面各读一次盘、互相看不到对方修改的问题。
 * 正文缓冲区和光标属于 Compose 的 TextFieldState，不进入 ViewModel。
 */
class NoteViewModel(
    private val repository: NoteRepository,
    private val noteId: NoteId,
) : ViewModel() {
    private val _uiState = MutableStateFlow(NoteUiState())
    val uiState: StateFlow<NoteUiState> = _uiState.asStateFlow()

    private var loadJob: Job? = null
    private var debounceJob: Job? = null
    private var saveJob: Job? = null
    private var saveRequested = false
    private var forceNextSave = false
    private var leaving = false

    init { load() }

    fun load() {
        if (_uiState.value.isDirty || loadJob?.isActive == true) return
        loadJob = viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, loadError = null) }
            try {
                val note = repository.readNote(noteId)
                _uiState.update {
                    it.copy(
                        content = note.content,
                        savedContent = note.content,
                        modifiedAt = note.modifiedAt,
                        isLoading = false,
                        saveState = NoteSaveState.Idle,
                        saveError = null,
                        isConflict = false,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(isLoading = false, loadError = e.localizedMessage ?: "打开笔记失败")
                }
            }
        }
    }

    /** 缓冲区每次变化都会到这里；700ms 没有新输入才真正写盘 */
    fun onContentChanged(value: String) {
        val current = _uiState.value
        if (current.content == value) return
        _uiState.update {
            it.copy(
                content = value,
                saveState = if (it.isConflict) NoteSaveState.Failed else NoteSaveState.Pending,
                saveError = if (it.isConflict) it.saveError else null,
            )
        }
        if (current.isConflict) return

        saveRequested = true
        debounceJob?.cancel()
        debounceJob = viewModelScope.launch {
            delay(AUTOSAVE_DELAY_MS)
            ensureSaveLoop()
        }
    }

    fun retrySave() {
        saveRequested = true
        ensureSaveLoop()
    }

    /** 外部改动冲突时用户选择以本地内容为准 */
    fun overwrite() {
        forceNextSave = true
        _uiState.update {
            it.copy(isConflict = false, saveError = null, saveState = NoteSaveState.Pending)
        }
        retrySave()
    }

    /**
     * 返回前把缓冲区写完。写失败时停在 [NoteUiState.leaveBlocked]，
     * 由界面让用户选择重试、覆盖还是放弃。
     */
    fun saveThenLeave(force: Boolean = false, onSaved: () -> Unit) {
        // 连续按返回只允许发起一次离开流程，否则回调会把返回栈多弹一层
        if (leaving) return
        leaving = true
        if (force) forceNextSave = true
        debounceJob?.cancel()
        saveRequested = true
        ensureSaveLoop()
        viewModelScope.launch {
            saveJob?.join()
            // 冲刷期间如果又有输入，再等一轮
            if (_uiState.value.content != _uiState.value.savedContent) {
                saveRequested = true
                ensureSaveLoop()
                saveJob?.join()
            }
            val state = _uiState.value
            if (state.content == state.savedContent && state.saveState != NoteSaveState.Failed) {
                onSaved()
            } else {
                leaving = false
                _uiState.update { it.copy(leaveBlocked = true) }
            }
        }
    }

    fun onLeaveHandled() {
        leaving = false
        _uiState.update { it.copy(leaveBlocked = false) }
    }

    private fun ensureSaveLoop() {
        if (saveJob?.isActive == true) return
        saveJob = viewModelScope.launch {
            while (saveRequested) {
                saveRequested = false
                if (!persistOnce()) break
            }
            saveJob = null
            // 收尾时刚好又有新输入，接上下一轮（冲突时不自动重试）
            if (saveRequested && !_uiState.value.isConflict) ensureSaveLoop()
        }
    }

    private suspend fun persistOnce(): Boolean {
        val snapshot = _uiState.value
        if (snapshot.content == snapshot.savedContent) return true

        _uiState.update { it.copy(saveState = NoteSaveState.Saving, saveError = null) }
        return try {
            val updated = repository.saveNote(
                id = noteId,
                content = snapshot.content,
                expectedModifiedAt = if (forceNextSave) null else snapshot.modifiedAt,
            )
            forceNextSave = false
            _uiState.update { state ->
                state.copy(
                    savedContent = snapshot.content,
                    modifiedAt = updated.modifiedAt,
                    saveState = if (state.content == snapshot.content) {
                        NoteSaveState.Saved
                    } else {
                        NoteSaveState.Pending
                    },
                    isConflict = false,
                )
            }
            true
        } catch (e: CancellationException) {
            throw e
        } catch (_: NoteChangedExternallyException) {
            _uiState.update {
                it.copy(
                    saveState = NoteSaveState.Failed,
                    isConflict = true,
                    saveError = "笔记已在其他应用中修改",
                )
            }
            false
        } catch (e: Exception) {
            _uiState.update {
                it.copy(
                    saveState = NoteSaveState.Failed,
                    saveError = e.localizedMessage ?: "保存失败",
                )
            }
            false
        }
    }

    companion object {
        private const val AUTOSAVE_DELAY_MS = 700L

        fun factory(
            repository: NoteRepository,
            noteId: NoteId,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer { NoteViewModel(repository, noteId) }
        }
    }
}
