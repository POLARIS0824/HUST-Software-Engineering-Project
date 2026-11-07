package com.example.looma.feature.note

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.looma.data.image.ImageUpload
import com.example.looma.data.image.ImageUploadException
import com.example.looma.data.image.ImageUploadFailure
import com.example.looma.data.image.ImageUploader
import com.example.looma.data.note.NoteChangedExternallyException
import com.example.looma.data.note.NoteRepository
import com.example.looma.data.settings.SettingsRepository
import com.example.looma.domain.model.NoteId
import com.example.looma.domain.model.NoteWriteMode
import com.example.looma.domain.wiki.WikiContext
import com.example.looma.feature.note.NoteViewModel.Companion.SAVE_INTERVAL_MS
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class NoteViewModel(
    private val repository: NoteRepository,
    private val settingsRepository: SettingsRepository,
    private val imageUploader: ImageUploader,
    private val noteId: NoteId,
    isNewNote: Boolean,
) : ViewModel() {
    private val _uiState = MutableStateFlow(NoteUiState(isNewNote = isNewNote))
    val uiState: StateFlow<NoteUiState> = _uiState.asStateFlow()

    private data class SaveRequest(val content: String, val mode: NoteWriteMode)

    private val editorWrites = Mutex()
    private var operationGeneration = 0
    private var loadJob: Job? = null
    private var autosaveTick: Job? = null
    private var leaving = false

    init {
        load()
        viewModelScope.launch {
            settingsRepository.settings.collect { settings ->
                _uiState.update {
                    it.copy(
                        showInlineTitle = settings.showInlineTitle,
                        toolbarOrder = settings.toolbarOrder,
                    )
                }
            }
        }
    }

    fun load() {
        if (_uiState.value.isDirty || loadJob?.isActive == true) return
        loadJob = enqueue { generation ->
            _uiState.update { it.copy(isLoading = true, loadFailed = false) }
            try {
                val opened = repository.openNote(noteId)
                val keepEmptyNotes = settingsRepository.settings.first().keepEmptyNotes
                val wiki = runCatching { repository.wikiContext(noteId) }
                    .onFailure { if (it is CancellationException) throw it }
                    .getOrDefault(WikiContext.Empty)
                val imageContext = runCatching { repository.imageContext(noteId) }
                    .onFailure { if (it is CancellationException) throw it }
                    .getOrNull()
                if (generation != operationGeneration) return@enqueue
                val conflict = opened.conflictRevision != null
                val dirty = opened.note.content != opened.diskContent
                _uiState.update {
                    it.copy(
                        content = opened.note.content,
                        savedContent = opened.diskContent,
                        modifiedAt = opened.note.modifiedAt,
                        revision = opened.note.revision,
                        conflictRevision = opened.conflictRevision,
                        isLoading = false,
                        saveState = when {
                            conflict -> NoteSaveState.Failed
                            dirty -> NoteSaveState.Pending
                            else -> NoteSaveState.Idle
                        },
                        saveFailed = conflict,
                        loadFailed = false,
                        isConflict = conflict,
                        keepEmptyNotes = keepEmptyNotes,
                        wiki = wiki,
                        imageContext = imageContext,
                        restoredDraft = opened.restoredDraft,
                        reloadToken = it.reloadToken + 1,
                    )
                }
                if (dirty && !conflict) scheduleAutosave()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (generation == operationGeneration) {
                    _uiState.update { it.copy(isLoading = false, loadFailed = true) }
                }
            }
        }
    }

    fun syncWithDisk() {
        val current = _uiState.value
        if (current.isLoading || current.isDirty || current.isConflict || current.saveFailed) return
        enqueue {
            try {
                val disk = repository.readNote(noteId)
                _uiState.update { state ->
                    if (!state.canAdoptDiskRevision(disk.revision)) {
                        state
                    } else {
                        state.copy(
                            content = disk.content,
                            savedContent = disk.content,
                            modifiedAt = disk.modifiedAt,
                            revision = disk.revision,
                            reloadToken = state.reloadToken + 1,
                            saveState = NoteSaveState.Idle,
                            saveFailed = false,
                            isConflict = false,
                            restoredDraft = false,
                        )
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
        }
    }

    fun refreshWiki() {
        if (_uiState.value.isLoading) return
        viewModelScope.launch {
            try {
                val wiki = repository.wikiContext(noteId)
                _uiState.update { it.copy(wiki = wiki) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
        }
    }

    fun uploadImage(uri: Uri) {
        if (_uiState.value.isUploadingImage) return
        viewModelScope.launch {
            _uiState.update { it.copy(isUploadingImage = true, imageUploadError = null) }
            try {
                val settings = settingsRepository.settings.first()
                val upload = imageUploader.upload(uri, settings)
                val snippet = when (upload) {
                    is ImageUpload.Attachment -> "![[${upload.fileName}]]"
                    is ImageUpload.Remote -> "![](${upload.url})"
                }
                _uiState.update {
                    it.copy(
                        isUploadingImage = false,
                        imageInsertion = ImageInsertion(snippet, System.nanoTime()),
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: ImageUploadException) {
                _uiState.update {
                    it.copy(isUploadingImage = false, imageUploadError = e.toNoteError())
                }
            } catch (_: Exception) {
                _uiState.update {
                    it.copy(isUploadingImage = false, imageUploadError = NoteImageError.Unknown)
                }
            }
        }
    }

    fun onImageInserted() {
        _uiState.update { it.copy(imageInsertion = null) }
    }

    fun onImageErrorShown() {
        _uiState.update { it.copy(imageUploadError = null) }
    }

    fun onContentChanged(value: String) {
        val current = _uiState.value
        if (current.isLoading || current.loadFailed || current.leaveReady || current.content == value) return
        _uiState.update {
            it.copy(
                content = value,
                saveState = if (it.isConflict) NoteSaveState.Failed else NoteSaveState.Pending,
                saveFailed = if (it.isConflict) it.saveFailed else false,
                restoredDraft = false,
            )
        }
        scheduleAutosave()
    }

    fun retrySave() {
        cancelAutosave()
        enqueue { generation ->
            if (!_uiState.value.isLoading) saveCurrent(generation)
        }
    }

    fun overwrite() {
        if (_uiState.value.isLoading) return
        val request = overwriteRequest()
        cancelAutosave()
        enqueue { generation -> saveCurrent(generation, request) }
    }

    fun reloadFromDisk() {
        operationGeneration++
        cancelAutosave()
        leaving = false
        _uiState.update { it.copy(isLoading = true, leaveBlocked = false, leaveReady = false) }
        loadJob = enqueue { generation ->
            try {
                val note = repository.reloadNote(noteId)
                if (generation != operationGeneration) return@enqueue
                _uiState.update {
                    it.copy(
                        content = note.content,
                        savedContent = note.content,
                        modifiedAt = note.modifiedAt,
                        revision = note.revision,
                        conflictRevision = null,
                        isLoading = false,
                        loadFailed = false,
                        saveState = NoteSaveState.Idle,
                        saveFailed = false,
                        isConflict = false,
                        restoredDraft = false,
                        reloadToken = it.reloadToken + 1,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                if (generation == operationGeneration) {
                    _uiState.update {
                        it.copy(isLoading = false, saveFailed = true, saveState = NoteSaveState.Failed)
                    }
                }
            }
        }
    }

    fun onEnterBackground() {
        cancelAutosave()
        enqueue { generation ->
            val state = _uiState.value
            if (!state.isLoading && (state.isDirty || state.isConflict || state.saveFailed)) {
                checkpoint()
                if (!_uiState.value.isConflict) saveCurrent(generation)
            }
        }
    }

    fun saveThenLeave(force: Boolean = false) {
        if (leaving || _uiState.value.isLoading || _uiState.value.leaveReady) return
        val request = if (force) overwriteRequest() else null
        leaving = true
        cancelAutosave()
        enqueue { generation ->
            try {
                val state = _uiState.value
                val saved = if (request == null && state.willDiscardEmpty) {
                    discardEmpty(state)
                } else {
                    saveCurrent(generation, request)
                }
                if (generation == operationGeneration) {
                    _uiState.update { it.copy(leaveReady = saved, leaveBlocked = !saved) }
                }
            } finally {
                leaving = false
            }
        }
    }

    fun requestLeave() {
        saveThenLeave()
    }

    fun onLeaveHandled() {
        _uiState.update { it.copy(leaveBlocked = false) }
    }

    fun onLeaveReadyHandled() {
        _uiState.update { it.copy(leaveReady = false) }
    }

    private fun overwriteRequest(): SaveRequest {
        val state = _uiState.value
        return SaveRequest(
            state.content,
            NoteWriteMode.Overwrite(state.conflictRevision ?: state.revision),
        )
    }

    private fun cancelAutosave() {
        autosaveTick?.cancel()
        autosaveTick = null
    }

    private fun scheduleAutosave() {
        if (autosaveTick?.isActive == true) return
        autosaveTick = viewModelScope.launch {
            delay(SAVE_INTERVAL_MS)
            autosaveTick = null
            enqueue { generation ->
                val state = _uiState.value
                when {
                    state.isConflict -> checkpoint()
                    state.isDirty -> saveCurrent(generation)
                }
            }
        }
    }

    private fun enqueue(action: suspend (Int) -> Unit): Job {
        val generation = operationGeneration
        return viewModelScope.launch {
            editorWrites.withLock {
                if (generation == operationGeneration) action(generation)
            }
        }
    }

    private suspend fun saveCurrent(generation: Int, request: SaveRequest? = null): Boolean {
        if (request != null && !persistOnce(request)) return false
        while (generation == operationGeneration) {
            val state = _uiState.value
            if (!state.isDirty && !state.isConflict && !state.saveFailed) return true
            if (!persistOnce(SaveRequest(state.content, NoteWriteMode.Checked(state.revision)))) return false
        }
        return false
    }

    private suspend fun persistOnce(request: SaveRequest): Boolean {
        _uiState.update { it.copy(saveState = NoteSaveState.Saving, saveFailed = false) }
        try {
            val updated = repository.saveNote(noteId, request.content, request.mode)
            _uiState.update { state ->
                state.copy(
                    savedContent = request.content,
                    modifiedAt = updated.modifiedAt,
                    revision = updated.revision,
                    conflictRevision = null,
                    saveState = if (state.content == request.content) {
                        NoteSaveState.Saved
                    } else {
                        NoteSaveState.Pending
                    },
                    saveFailed = false,
                    isConflict = false,
                    restoredDraft = false,
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            recordFailure(e)
            checkpoint()
            return false
        }
        return checkpoint()
    }

    private suspend fun checkpoint(): Boolean {
        val state = _uiState.value
        return try {
            repository.writeDraft(noteId, state.content, state.revision)
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            recordFailure(e)
            false
        }
    }

    private suspend fun discardEmpty(state: NoteUiState): Boolean {
        return try {
            repository.discardNote(noteId, state.revision)
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            recordFailure(e)
            checkpoint()
            false
        }
    }

    private fun recordFailure(error: Exception) {
        _uiState.update {
            it.copy(
                saveState = NoteSaveState.Failed,
                saveFailed = true,
                isConflict = it.isConflict || error is NoteChangedExternallyException,
                conflictRevision = (error as? NoteChangedExternallyException)?.actualRevision
                    ?: it.conflictRevision,
            )
        }
    }

    companion object {
        private const val SAVE_INTERVAL_MS = 2_000L

        fun factory(
            repository: NoteRepository,
            settingsRepository: SettingsRepository,
            imageUploader: ImageUploader,
            noteId: NoteId,
            isNewNote: Boolean = false,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                NoteViewModel(repository, settingsRepository, imageUploader, noteId, isNewNote)
            }
        }
    }
}

private fun ImageUploadException.toNoteError(): NoteImageError = when (failure) {
    ImageUploadFailure.NotConfigured -> NoteImageError.NotConfigured
    ImageUploadFailure.UnsupportedImage -> NoteImageError.UnsupportedImage
    ImageUploadFailure.ImageTooLarge -> NoteImageError.ImageTooLarge
    ImageUploadFailure.ReadFailed, ImageUploadFailure.StorageFailed -> NoteImageError.Storage
    ImageUploadFailure.Rejected -> NoteImageError.Rejected(detail.orEmpty())
    ImageUploadFailure.Network -> NoteImageError.Network
}
