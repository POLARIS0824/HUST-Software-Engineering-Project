package com.example.looma.feature.trash

import java.time.Instant

data class TrashEntry(
    val trashId: String,
    val title: String,
    val excerpt: String,
    val originalPath: String,
    val deletedAt: Instant,
)

sealed interface TrashBusy {
    data object None : TrashBusy
    data class Row(val trashId: String) : TrashBusy
    data object Bulk : TrashBusy
}

sealed interface TrashConfirm {
    data class DeleteOne(val entry: TrashEntry) : TrashConfirm
    data object EmptyTrash : TrashConfirm
}

data class TrashUiState(
    val items: List<TrashEntry> = emptyList(),
    val isLoading: Boolean = true,
    val loadFailed: Boolean = false,
    val busy: TrashBusy = TrashBusy.None,
) {
    val isEmpty: Boolean get() = !isLoading && !loadFailed && items.isEmpty()
    val canRestoreAll: Boolean get() = items.isNotEmpty() && busy == TrashBusy.None
    val canEmptyTrash: Boolean get() = items.isNotEmpty() && busy == TrashBusy.None
}
