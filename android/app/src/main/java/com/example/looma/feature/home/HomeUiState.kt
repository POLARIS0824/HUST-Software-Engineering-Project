package com.example.looma.feature.home

data class NoteUiModel(
    val id: String,
    val title: String,
    val preview: String,
)

data class HomeUiState(
    val notes: List<NoteUiModel> = emptyList(),
    val query: String = "",
    val isGridMode: Boolean = true,
    val sortByTitle: Boolean = false,
) {
    val visibleNotes: List<NoteUiModel>
        get() {
            val filtered = notes.filter { note ->
                note.title.contains(query, ignoreCase = true) ||
                        note.preview.contains(query, ignoreCase = true)
            }

            return if (sortByTitle) {
                filtered.sortedBy { it.title }
            } else {
                filtered
            }
        }
}
