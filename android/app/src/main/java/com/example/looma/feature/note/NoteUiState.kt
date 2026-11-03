package com.example.looma.feature.note

import com.example.looma.domain.markdown.ImageContext
import com.example.looma.domain.markdown.isEffectivelyEmpty
import com.example.looma.domain.wiki.WikiContext
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
    val loadFailed: Boolean = false,
    val saveState: NoteSaveState = NoteSaveState.Idle,
    val saveFailed: Boolean = false,
    val isConflict: Boolean = false,
    val leaveBlocked: Boolean = false,
    /** 打开笔记时读到的设置：没有写内容就离开时要不要丢弃 */
    val keepEmptyNotes: Boolean = true,
    /** 正文里每个 `[[目标]]` 解析到哪篇笔记，以及谁链接到这里；空表示还没读到 */
    val wiki: WikiContext = WikiContext.Empty,
    /** 正文图片的解析坐标；空表示还没有库，图片一律不加载 */
    val imageContext: ImageContext? = null,
) {
    val isDirty: Boolean get() = !isLoading && content != savedContent

    /** 返回键需要先过保存流程：有未落盘的修改，或保存/失败状态还没结束 */
    val needsLeaveGuard: Boolean
        get() = isDirty ||
            saveState == NoteSaveState.Pending ||
            saveState == NoteSaveState.Saving ||
            saveState == NoteSaveState.Failed

    /**
     * 误触新建、什么都没写就离开。
     *
     * 只有「打开时就空、现在仍然空」才算：把一篇有内容的笔记清空不属于误触，
     * 那种情况仍然保存，不然删掉一段文字再退出会让整篇笔记进回收站。
     */
    val willDiscardEmpty: Boolean
        get() = !keepEmptyNotes &&
            !isConflict &&
            isEffectivelyEmpty(content) &&
            isEffectivelyEmpty(savedContent)
}
