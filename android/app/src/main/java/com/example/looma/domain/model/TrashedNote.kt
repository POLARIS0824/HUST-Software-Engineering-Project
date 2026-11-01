package com.example.looma.domain.model

import java.time.Instant

/** 可以从当前 Vault 回收站恢复的笔记记录 */
data class TrashedNote(
    val trashId: String,
    val originalPath: NotePath,
    val deletedAt: Instant,
)