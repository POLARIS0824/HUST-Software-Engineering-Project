package com.example.looma.feature.reader

import com.example.looma.domain.markdown.MarkdownDocument
import java.time.Instant

data class ReaderUiState(
    val document: MarkdownDocument? = null,
    val modifiedAt: Instant? = null,
    val isLoading: Boolean = true,
    val isSaving: Boolean = false,
    val errorMessage: String? = null,
)
