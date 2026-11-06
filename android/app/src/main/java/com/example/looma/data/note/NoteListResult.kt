package com.example.looma.data.note

import com.example.looma.domain.model.NoteSummary
import com.example.looma.domain.model.VaultFolder

internal data class NoteListEntry(
    val summary: NoteSummary,
    val coverPath: String?,
    val markdownSnippet: String,
    val searchText: String,
)

internal data class NoteListResult(
    val items: List<NoteListEntry>,
    val folders: List<VaultFolder> = emptyList(),
    val unreadableCount: Int = 0,
)
