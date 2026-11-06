package com.example.looma.feature.home

import com.example.looma.domain.model.FolderPath
import com.example.looma.domain.model.NoteId

sealed interface HomeSelectionEvent {
    data class FolderTapped(val folder: HomeFolderItem) : HomeSelectionEvent
    data class FolderLongPressed(val folder: HomeFolderItem) : HomeSelectionEvent
    data class NoteTapped(val note: HomeNoteItem) : HomeSelectionEvent
    data class NoteLongPressed(val note: HomeNoteItem) : HomeSelectionEvent
    data object BackgroundTapped : HomeSelectionEvent
}

sealed interface HomeSelectionOutcome {
    data class Changed(val selection: HomeSelection) : HomeSelectionOutcome
    data class OpenFolder(val path: FolderPath) : HomeSelectionOutcome
    data class OpenNote(val id: NoteId) : HomeSelectionOutcome
}

internal fun reduceHomeSelection(
    current: HomeSelection,
    event: HomeSelectionEvent,
): HomeSelectionOutcome = when (event) {
    is HomeSelectionEvent.FolderTapped -> when (current) {
        HomeSelection.None -> HomeSelectionOutcome.OpenFolder(event.folder.path)
        is HomeSelection.Folder -> HomeSelectionOutcome.Changed(
            if (current.item.path == event.folder.path) {
                HomeSelection.None
            } else {
                HomeSelection.Folder(event.folder)
            },
        )
        is HomeSelection.Notes -> HomeSelectionOutcome.Changed(HomeSelection.Folder(event.folder))
    }

    is HomeSelectionEvent.FolderLongPressed ->
        HomeSelectionOutcome.Changed(HomeSelection.Folder(event.folder))

    is HomeSelectionEvent.NoteTapped -> when (current) {
        HomeSelection.None -> HomeSelectionOutcome.OpenNote(event.note.id)
        is HomeSelection.Folder ->
            HomeSelectionOutcome.Changed(HomeSelection.Notes.of(setOf(event.note.listKey)))
        is HomeSelection.Notes -> HomeSelectionOutcome.Changed(
            HomeSelection.Notes.of(
                if (event.note.listKey in current.keys) {
                    current.keys - event.note.listKey
                } else {
                    current.keys + event.note.listKey
                },
            ),
        )
    }

    is HomeSelectionEvent.NoteLongPressed -> when (current) {
        is HomeSelection.Notes ->
            HomeSelectionOutcome.Changed(HomeSelection.Notes.of(current.keys + event.note.listKey))
        else -> HomeSelectionOutcome.Changed(HomeSelection.Notes.of(setOf(event.note.listKey)))
    }

    HomeSelectionEvent.BackgroundTapped -> HomeSelectionOutcome.Changed(HomeSelection.None)
}
