package com.example.looma.feature.home

import com.example.looma.domain.model.NoteId
import com.example.looma.domain.model.NoteSummary

/** 首页能报给用户的错误，文案由 UI 层按资源映射 */
sealed interface HomeError {
    data class UnreadableNotes(val count: Int) : HomeError
    data object LoadFailed : HomeError
    data object CreateFailed : HomeError
}

/** 只放 Home 渲染需要的数据，不改 domain 模型 */
data class HomeNoteItem(
    val summary: NoteSummary,
    val coverPath: String? = null,
    /** 卡片里渲染的 Markdown 开头，已去掉 frontmatter 与重复标题 */
    val markdownSnippet: String = "",
    val searchableText: String = "",
) {
    val id: NoteId get() = summary.id

    /**
     * Lazy 列表 / 网格的 item key。
     *
     * 必须是 Bundle 可存类型（String、Int 等）：直接传 [NoteId] 会在
     * SaveableStateProvider 抛 “Type of the key ... is not supported”。
     */
    val listKey: String get() = "${id.vaultId.value}/${id.path.relativePath}"
}

enum class HomeSort { LAST_MODIFIED, TITLE }

/**
 * 首页唯一状态源。
 *
 * [notes] 与 [searchResults] 都已排好序，组合阶段只渲染、不再过滤或排序：
 * 那些是 ViewModel 的职责，也避免每次重组（包括搜索框每帧动画）重复排序。
 */
data class HomeUiState(
    val vaultName: String = "Looma",
    /** 首页全量笔记，已按 [sort] 排序 */
    val notes: List<HomeNoteItem> = emptyList(),
    /** 命中搜索框关键字的笔记，已按 [sort] 排序 */
    val searchResults: List<HomeNoteItem> = emptyList(),
    val query: String = "",
    val isGridMode: Boolean = true,
    val sort: HomeSort = HomeSort.LAST_MODIFIED,
    val isLoading: Boolean = true,
    val isCreating: Boolean = false,
    val error: HomeError? = null,
    /** 一次性事件：新建成功后待打开的笔记，消费后置空 */
    val createdNote: NoteId? = null,
    /** 文件视图：当前浏览路径、面包屑、最近笔记与当前层的文件夹 / 笔记 */
    val files: HomeFilesState = HomeFilesState(),
) {
    val isBrowsing: Boolean get() = !files.isAtRoot
}

internal fun List<HomeNoteItem>.matching(query: String): List<HomeNoteItem> {
    val keyword = query.trim()
    if (keyword.isEmpty()) return this
    return filter { note ->
        note.summary.title.contains(keyword, ignoreCase = true) ||
            note.searchableText.contains(keyword, ignoreCase = true) ||
            note.summary.tags.any { it.contains(keyword, ignoreCase = true) }
    }
}

internal fun List<HomeNoteItem>.orderedBy(sort: HomeSort): List<HomeNoteItem> = when (sort) {
    HomeSort.LAST_MODIFIED -> sortedByDescending { it.summary.modifiedAt }
    HomeSort.TITLE -> sortedBy { it.summary.title.lowercase() }
}
