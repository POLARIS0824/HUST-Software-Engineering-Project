package com.example.looma.feature.home

import com.example.looma.domain.model.FolderPath
import com.example.looma.domain.model.NoteId

/** 首页「点到了什么」的事件；屏幕只把点击翻译成事件，判断全在这里 */
sealed interface HomeSelectionEvent {
    data class FolderTapped(val folder: HomeFolderItem) : HomeSelectionEvent
    data class FolderLongPressed(val folder: HomeFolderItem) : HomeSelectionEvent
    data class NoteTapped(val note: HomeNoteItem) : HomeSelectionEvent
    data class NoteLongPressed(val note: HomeNoteItem) : HomeSelectionEvent
    data object BackgroundTapped : HomeSelectionEvent
}

/** 一次点击的结果只有三种：改选择、进文件夹、开笔记 */
sealed interface HomeSelectionOutcome {
    data class Changed(val selection: HomeSelection) : HomeSelectionOutcome
    data class OpenFolder(val path: FolderPath) : HomeSelectionOutcome
    data class OpenNote(val id: NoteId) : HomeSelectionOutcome
}

/**
 * 首页点击的纯函数规则：同一份选择状态加同一个点击，永远得到同一个结果。
 *
 * 核心只有一条：**选择态下点击不导航**。文件夹被选中时，点别的文件夹是换选，
 * 点自己是不选，只有什么都没选中时点文件夹才是进去。
 */
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
