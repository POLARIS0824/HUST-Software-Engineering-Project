package com.example.looma.feature.trash

import java.time.Instant

/** 回收站里的一行：够显示标题、出处和删除时间即可 */
data class TrashEntry(
    val trashId: String,
    val title: String,
    val excerpt: String,
    val originalPath: String,
    val deletedAt: Instant,
)

data class TrashUiState(
    val items: List<TrashEntry> = emptyList(),
    val isLoading: Boolean = true,
    val loadFailed: Boolean = false,
    val restoringId: String? = null,
) {
    val isEmpty: Boolean get() = !isLoading && !loadFailed && items.isEmpty()
}
