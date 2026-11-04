package com.example.looma.feature.note

import com.example.looma.domain.markdown.EditorDocument
import com.example.looma.domain.markdown.ImageContext
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
    /** 打开笔记时读到的设置：新笔记没有写任何内容就离开时要不要丢弃 */
    val keepEmptyNotes: Boolean = true,
    /** 这篇笔记是刚从首页新建的；只有新建的空白笔记才可能被丢弃 */
    val isNewNote: Boolean = false,
    /** 正文里每个 `[[目标]]` 解析到哪篇笔记，以及谁链接到这里；空表示还没读到 */
    val wiki: WikiContext = WikiContext.Empty,
    /** 正文图片的解析坐标；空表示还没有库，图片一律不加载 */
    val imageContext: ImageContext? = null,
    /** 打开时正文来自一份没落盘的草稿，界面提示一次让用户知道 */
    val restoredDraft: Boolean = false,
    /** 每换一次正文来源就加一，界面据此重建输入缓冲区 */
    val reloadToken: Int = 0,
) {
    val isDirty: Boolean get() = !isLoading && content != savedContent

    /** 返回键需要先过保存流程：有未落盘的修改，或保存/失败状态还没结束 */
    val needsLeaveGuard: Boolean
        get() = isDirty ||
            saveState == NoteSaveState.Pending ||
            saveState == NoteSaveState.Saving ||
            saveState == NoteSaveState.Failed

    /**
     * 新建的笔记什么都没写就离开。
     *
     * 只有「新建、打开时空、现在仍空」才算：把一篇已有笔记清空不属于误触，
     * 那种情况仍然保存，不然删掉一段文字再退出会让整篇笔记凭空消失。
     * 空 = 标题和正文都空；只写了标题的笔记是用户写下的内容，不能丢。
     */
    val willDiscardEmpty: Boolean
        get() = isNewNote &&
            !keepEmptyNotes &&
            !isConflict &&
            isEmptyDraft

    /** 标题与正文都为空；判定规则和编辑页拆分标题的方式是同一份 */
    private val isEmptyDraft: Boolean
        get() = EditorDocument.split(content).let { it.title.isBlank() && it.body.isBlank() }
}
