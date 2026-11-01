package com.example.looma.feature.home

import com.example.looma.domain.model.NoteId
import com.example.looma.domain.model.NoteSummary

data class HomeChecklistItem(
    val text: String,
    val checked: Boolean,
)

/** 只放 Home 渲染需要的数据，不改 domain 模型 */
data class HomeNoteItem(
    val summary: NoteSummary,
    val checklist: List<HomeChecklistItem> = emptyList(),
    val coverPath: String? = null,
    val linkLabel: String? = null,
    val searchableText: String = "",
) {
    val id: NoteId get() = summary.id
}

enum class HomeSort { LAST_MODIFIED, TITLE }

data class HomeUiState(
    val vaultName: String = "Looma",
    val notes: List<HomeNoteItem> = emptyList(),
    val query: String = "",
    val isGridMode: Boolean = true,
    val sort: HomeSort = HomeSort.LAST_MODIFIED,
    val isLoading: Boolean = true,
    val isCreating: Boolean = false,
    val errorMessage: String? = null,
) {
    val visibleNotes: List<HomeNoteItem>
        get() {
            val keyword = query.trim()
            val filtered = notes.asSequence().filter { note ->
                keyword.isBlank() ||
                        note.summary.title.contains(keyword, ignoreCase = true) ||
                        note.searchableText.contains(keyword, ignoreCase = true) ||
                        note.summary.tags.any { it.contains(keyword, ignoreCase = true) }
            }
            return when (sort) {
                HomeSort.LAST_MODIFIED -> filtered.sortedByDescending { it.summary.modifiedAt }
                HomeSort.TITLE -> filtered.sortedBy { it.summary.title.lowercase() }
            }.toList()
        }
}