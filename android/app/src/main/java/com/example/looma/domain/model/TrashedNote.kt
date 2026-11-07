package com.example.looma.domain.model

import java.time.Instant

data class TrashedNote(
    val trashId: String,
    val originalPath: NotePath,
    val deletedAt: Instant,
    val title: String = "",
    val excerpt: String = "",
)
