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

/**
 * 表示解析后的笔记业务数据
 */
data class Note(
    val id: NoteId,
    val title: String,
    val content: String,
    val modifiedAt: Instant,
    val tags: Set<String> = emptySet(),
)

/**
 * 表示首页、搜索所需的笔记摘要
 */
data class NoteSummary(
    val id: NoteId,
    val title: String,
    val excerpt: String,
    val modifiedAt: Instant,
    val tags: Set<String> = emptySet(),
)