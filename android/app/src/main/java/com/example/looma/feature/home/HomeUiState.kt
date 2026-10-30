package com.example.looma.feature.home

import com.example.looma.domain.model.NoteId
import com.example.looma.domain.model.NoteSummary

/** Home 专用展示数据，不把界面状态放进 domain 层 */
data class ChecklistItemUiModel(
    val text: String,
    val checked: Boolean,
)

data class NoteUiModel(
    val summary: NoteSummary,
    val checklist: List<ChecklistItemUiModel> = emptyList(),
    val coverPath: String? = null,
) {
    val id: NoteId get() = summary.id
    val title: String get() = summary.title
    val preview: String get() = summary.excerpt
}

data class HomeUiState(
    val vaultName: String? = null,
    val notes: List<NoteUiModel> = emptyList(),
    val query: String = "",
    val isGridMode: Boolean = true,
    val sortByTitle: Boolean = false,
    val isLoading: Boolean = true,
    val errorMessage: String? = null,
) {
    val visibleNotes: List<NoteUiModel>
        get() = notes
            .asSequence()
            .filter { note ->
                val text = query.trim()
                text.isEmpty() ||
                        note.title.contains(text, ignoreCase = true) ||
                        note.preview.contains(text, ignoreCase = true) ||
                        note.summary.tags.any { it.contains(text, ignoreCase = true) } ||
                        note.checklist.any { it.text.contains(text, ignoreCase = true) }
            }
            .let { filtered ->
                if (sortByTitle) filtered.sortedBy { it.title.lowercase() }
                else filtered.sortedByDescending { it.summary.modifiedAt }
            }
            .toList()
}
