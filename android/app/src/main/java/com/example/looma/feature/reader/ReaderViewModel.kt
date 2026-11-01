package com.example.looma.feature.reader

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.looma.data.note.NoteRepository
import com.example.looma.domain.markdown.MarkdownIndex
import com.example.looma.domain.markdown.withCheckboxChecked
import com.example.looma.domain.model.NoteId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 阅读态：一次解析出可渲染的文档，任务项的勾选直接写回同一个文件。 */
class ReaderViewModel(
    private val repository: NoteRepository,
    private val noteId: NoteId,
) : ViewModel() {
    private val _uiState = MutableStateFlow(ReaderUiState())
    val uiState: StateFlow<ReaderUiState> = _uiState.asStateFlow()

    private val fallbackTitle = noteId.path.relativePath
        .substringAfterLast('/')
        .substringBeforeLast('.')

    private var loadJob: Job? = null

    init { reload() }

    /** 回到前台或从编辑页返回时重新读盘，保证看到的是文件里的当前内容 */
    fun reload() {
        if (_uiState.value.isSaving || loadJob?.isActive == true) return
        loadJob = viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            try {
                val note = repository.readNote(noteId)
                _uiState.update {
                    it.copy(
                        document = MarkdownIndex.index(note.content, fallbackTitle),
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

    /**
     * 只改原文里的三个字符，写完重新解析一次。
     * 乐观更新，失败时退回点击前的文档并保留错误提示。
     */
    fun onCheckboxToggled(position: Int, checked: Boolean) {
        val state = _uiState.value
        val document = state.document ?: return
        if (state.isSaving) return
        val updated = document.withCheckboxChecked(position, checked) ?: return

        _uiState.update {
            it.copy(
                document = MarkdownIndex.index(updated, fallbackTitle),
                isSaving = true,
                errorMessage = null,
            )
        }
        viewModelScope.launch {
            try {
                val note = repository.saveNote(noteId, updated, state.modifiedAt)
                _uiState.update { it.copy(isSaving = false, modifiedAt = note.modifiedAt) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        document = document,
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
            initializer { ReaderViewModel(repository, noteId) }
        }
    }
}
