package com.example.looma.data.note

import com.example.looma.domain.model.NoteSummary

/** 与 HomeScreen 无关的通用笔记列表查询结果 */
data class NoteListEntry(
    val summary: NoteSummary,
    val metadata: MarkdownMetadata,
    val coverPath: String?,
    val searchText: String,
)

data class NoteListResult(
    val items: List<NoteListEntry>,
    val unreadableCount: Int = 0,
)