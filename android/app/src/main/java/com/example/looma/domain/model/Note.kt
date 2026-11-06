package com.example.looma.domain.model

import java.time.Instant

@JvmInline
value class NotePath(
    val relativePath: String
)

data class NoteId(
    val vaultId: VaultId,
    val path: NotePath,
)

data class Note(
    val id: NoteId,
    val title: String,
    val content: String,
    val modifiedAt: Instant,
    val tags: Set<String> = emptySet(),
)

data class NoteSummary(
    val id: NoteId,
    val title: String,
    val excerpt: String,
    val modifiedAt: Instant,
    val tags: Set<String> = emptySet(),
)
