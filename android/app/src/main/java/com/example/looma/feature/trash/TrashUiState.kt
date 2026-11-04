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

/** 回收站的忙碌状态：行内操作与整批操作互斥，用一个类型表达 */
sealed interface TrashBusy {
    data object None : TrashBusy
    data class Row(val trashId: String) : TrashBusy
    data object Bulk : TrashBusy
}

/** 需要用户确认的破坏性动作 */
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
