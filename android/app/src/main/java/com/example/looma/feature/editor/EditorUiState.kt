package com.example.looma.feature.editor

import java.time.Instant

data class EditorUiState(
    val title: String = "Note",
    val content: String = "",
    val savedContent: String = "",
    val modifiedAt: Instant? = null,
    val isLoading: Boolean = true,
    val isSaving: Boolean = false,
    val errorMessage: String? = null,
) {
    val isDirty: Boolean get() = !isLoading && content != savedContent
}
