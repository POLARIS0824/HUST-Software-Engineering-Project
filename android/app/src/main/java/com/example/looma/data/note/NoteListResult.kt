package com.example.looma.data.note

import com.example.looma.domain.model.NoteSummary
import com.example.looma.domain.model.VaultFolder

/** 与 HomeScreen 无关的通用笔记列表查询结果 */
internal data class NoteListEntry(
    val summary: NoteSummary,
    val coverPath: String?,
    val markdownSnippet: String,
    val searchText: String,
)

/** 一次扫描同时给出笔记与目录，保证首页看到的是同一份 Vault 快照 */
internal data class NoteListResult(
    val items: List<NoteListEntry>,
    val folders: List<VaultFolder> = emptyList(),
    val unreadableCount: Int = 0,
)
