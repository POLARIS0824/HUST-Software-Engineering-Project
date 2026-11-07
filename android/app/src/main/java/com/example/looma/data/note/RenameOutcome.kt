package com.example.looma.data.note

import com.example.looma.domain.model.Note

data class RenameOutcome(
    val note: Note,
    val linksUpdated: Int,
    val linksFailed: Int,
)
