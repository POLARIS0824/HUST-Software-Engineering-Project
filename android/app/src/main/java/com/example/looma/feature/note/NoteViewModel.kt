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

class NoteViewModel(
    private val repository: NoteRepository,
    private val settingsRepository: SettingsRepository,
    private val imageUploader: ImageUploader,
    private val noteId: NoteId,
    isNewNote: Boolean,
) : ViewModel() {
    private val _uiState = MutableStateFlow(NoteUiState(isNewNote = isNewNote))
    val uiState: StateFlow<NoteUiState> = _uiState.asStateFlow()

    private var loadJob: Job? = null
    private var saveTick: Job? = null
    private var saveJob: Job? = null
    private var saveRequested = false
    private var forceNextSave = false
    private var leaving = false

    init {
        load()
        viewModelScope.launch {
            settingsRepository.settings.collect { settings ->
                _uiState.update { it.copy(toolbarOrder = settings.toolbarOrder) }
            }
        }
    }

    fun load() {
        if (_uiState.value.isDirty || loadJob?.isActive == true) return
        loadJob = viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, loadFailed = false) }
            try {
                val opened = repository.openNote(noteId)
                val keepEmptyNotes = settingsRepository.settings.first().keepEmptyNotes
                val wiki = runCatching { repository.wikiContext(noteId) }.getOrDefault(WikiContext.Empty)
                val imageContext = runCatching { repository.imageContext(noteId) }.getOrNull()
                _uiState.update {
                    it.copy(
                        content = opened.note.content,
                        savedContent = opened.note.content,
                        modifiedAt = opened.note.modifiedAt,
                        isLoading = false,
                        saveState = NoteSaveState.Idle,
                        saveFailed = false,
                        loadFailed = false,
                        isConflict = false,
                        keepEmptyNotes = keepEmptyNotes,
                        wiki = wiki,
                        imageContext = imageContext,
                        restoredDraft = opened.restoredDraft,
                        reloadToken = it.reloadToken + 1,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update { it.copy(isLoading = false, loadFailed = true) }
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
        if (current.content == value) return
        _uiState.update {
            it.copy(
                content = value,
                saveState = if (it.isConflict) NoteSaveState.Failed else NoteSaveState.Pending,
                saveFailed = if (it.isConflict) it.saveFailed else false,
                restoredDraft = false,
            )
        }
        if (current.isConflict) return
        scheduleSave()
    }

    fun retrySave() {
        viewModelScope.launch {
            val disk = runCatching { repository.modifiedAtOf(noteId) }.getOrNull()
            val known = _uiState.value.modifiedAt
            if (disk != null && known != null && disk != known) {
                _uiState.update {
                    it.copy(isConflict = true, saveFailed = true, saveState = NoteSaveState.Failed)
                }
                return@launch
            }
            saveRequested = true
            ensureSaveLoop()
        }
    }

    fun overwrite() {
        viewModelScope.launch {
            repository.backupConflict(noteId)
            forceNextSave = true
            _uiState.update {
                it.copy(isConflict = false, saveFailed = false, saveState = NoteSaveState.Pending)
            }
            retrySave()
        }
    }

    fun reloadFromDisk() {
        loadJob?.cancel()
        saveTick?.cancel()
        saveTick = null
        saveRequested = false
        _uiState.update {
            it.copy(isConflict = false, saveFailed = false, saveState = NoteSaveState.Idle)
        }
        loadJob = viewModelScope.launch {
            try {
                val opened = repository.openNote(noteId)
                _uiState.update {
                    it.copy(
                        content = opened.note.content,
                        savedContent = opened.note.content,
                        modifiedAt = opened.note.modifiedAt,
                        saveState = NoteSaveState.Idle,
                        saveFailed = false,
                        isConflict = false,
                        restoredDraft = opened.restoredDraft,
                        reloadToken = it.reloadToken + 1,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _uiState.update { it.copy(saveFailed = true, saveState = NoteSaveState.Failed) }
            }
        }
    }

    fun onEnterBackground() {
        val state = _uiState.value
        if (state.isLoading || !state.isDirty) return
        viewModelScope.launch {
            runCatching { repository.writeDraft(noteId, state.content) }
            saveRequested = true
            ensureSaveLoop()
        }
    }

    fun saveThenLeave(force: Boolean = false, onSaved: () -> Unit) {
        if (leaving) return
        leaving = true
        if (force) forceNextSave = true
        saveTick?.cancel()
        saveTick = null
        saveRequested = true
        ensureSaveLoop()
        viewModelScope.launch {
            saveJob?.join()
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

    fun requestLeave(onLeave: () -> Unit) {
        val state = _uiState.value
        when {
            state.willDiscardEmpty -> viewModelScope.launch {
                runCatching { repository.discardNote(noteId) }
                onLeave()
            }
            state.needsLeaveGuard -> saveThenLeave(onSaved = onLeave)
            else -> onLeave()
        }
    }

    fun onLeaveHandled() {
        leaving = false
        _uiState.update { it.copy(leaveBlocked = false) }
    }

    private fun scheduleSave() {
        if (saveTick?.isActive == true) return
        saveTick = viewModelScope.launch {
            delay(SAVE_INTERVAL_MS)
            saveTick = null
            if (_uiState.value.isDirty && !_uiState.value.isConflict) {
                saveRequested = true
                ensureSaveLoop()
            }
        }
    }

    private fun ensureSaveLoop() {
        if (saveJob?.isActive == true) return
        saveJob = viewModelScope.launch {
            while (saveRequested) {
                saveRequested = false
                if (!persistOnce()) break
            }
            saveJob = null
            if (saveRequested && !_uiState.value.isConflict) ensureSaveLoop()
        }
    }

    private suspend fun persistOnce(): Boolean {
        val snapshot = _uiState.value
        if (snapshot.content == snapshot.savedContent) return true

        _uiState.update { it.copy(saveState = NoteSaveState.Saving, saveFailed = false) }
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
                    restoredDraft = false,
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
                    saveFailed = true,
                )
            }
            false
        } catch (e: Exception) {
            _uiState.update {
                it.copy(saveState = NoteSaveState.Failed, saveFailed = true)
            }
            false
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
