package com.example.looma.feature.note

import java.time.Instant

/** 自动保存的状态机；[Failed] 之后不再自动重试，等用户重试或覆盖 */
enum class NoteSaveState { Idle, Pending, Saving, Saved, Failed }

/**
 * 笔记页的文档会话状态。正文缓冲区和光标属于 Compose 的 TextFieldState，
 * 这里只保存文档层面的事实：最后一次落盘的内容、保存进度和错误。
 */
data class NoteUiState(
    val content: String = "",
    val savedContent: String = "",
    val modifiedAt: Instant? = null,
    val isLoading: Boolean = true,
    val loadError: String? = null,
    val saveState: NoteSaveState = NoteSaveState.Idle,
    val saveError: String? = null,
    val isConflict: Boolean = false,
    val leaveBlocked: Boolean = false,
) {
    val isDirty: Boolean get() = !isLoading && content != savedContent

    /** 返回键需要先过保存流程：有未落盘的修改，或保存/失败状态还没结束 */
    val needsLeaveGuard: Boolean
        get() = isDirty ||
            saveState == NoteSaveState.Pending ||
            saveState == NoteSaveState.Saving ||
            saveState == NoteSaveState.Failed
}
