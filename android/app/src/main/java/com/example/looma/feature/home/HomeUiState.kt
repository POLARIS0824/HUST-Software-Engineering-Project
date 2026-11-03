package com.example.looma.feature.home

import com.example.looma.domain.model.FolderPath
import com.example.looma.domain.model.NoteId
import com.example.looma.domain.model.NoteProperties
import com.example.looma.domain.model.NoteSummary

/** 首页能报给用户的错误，文案由 UI 层按资源映射 */
sealed interface HomeError {
    data class UnreadableNotes(val count: Int) : HomeError
    data object LoadFailed : HomeError
    data object CreateFailed : HomeError
}

/** 文件操作失败的原因，同样由 UI 映射成资源 */
enum class HomeFileError {
    EMPTY_NAME,
    INVALID_NAME,
    NAME_TAKEN,
    RESTORE_CONFLICT,
    GENERIC,
}

/** 一次性提示；删除带着回收站 id，Snackbar 才能撤回 */
sealed interface HomeMessage {
    data class Deleted(val count: Int, val trashIds: List<String>) : HomeMessage
    data class Restored(val count: Int) : HomeMessage
    data class Moved(val count: Int) : HomeMessage
    /** 改名时顺手改写的引用数；有引用没改成功时也如实报出来 */
    data class Renamed(val linksUpdated: Int, val linksFailed: Int = 0) : HomeMessage
    data object FolderCreated : HomeMessage
    data object FolderRenamed : HomeMessage
    data object FolderMoved : HomeMessage
    /** 删除文件夹时顺带移进回收站的笔记数 */
    data class FolderDeleted(val notes: Int) : HomeMessage
    data object ActionFailed : HomeMessage
    data object InvalidMoveTarget : HomeMessage
}

/** 选中笔记后可以打开的对话框；移动目标改用系统目录选择器，所以不再有移动对话框 */
sealed interface HomeFileDialog {
    data class Rename(val initialName: String) : HomeFileDialog
    data class Properties(val properties: NoteProperties) : HomeFileDialog
    data object NewFolder : HomeFileDialog

    /** 文件夹卡片长按或“更多”打开的小菜单 */
    data class RenameFolder(val path: FolderPath, val initialName: String) : HomeFileDialog
    data class MoveFolder(
        val path: FolderPath,
        val folderName: String,
        val targets: List<FolderPath>,
    ) : HomeFileDialog
    data class DeleteFolder(
        val path: FolderPath,
        val folderName: String,
        val noteCount: Int,
    ) : HomeFileDialog
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

    /** 重命名对话框里预填的名字，去掉目录和扩展名 */
    val fileName: String
        get() = id.path.relativePath
            .substringAfterLast('/')
            .removeSuffix(".md")
}

enum class HomeSort { LAST_MODIFIED, TITLE }

/**
 * 首页唯一状态源。
 *
 * [notes] 与 [searchResults] 都已排好序，组合阶段只渲染、不再过滤或排序：
 * 那些是 ViewModel 的职责，也避免每次重组（包括搜索框每帧动画）重复排序。
 * 选中状态只保存 [HomeNoteItem.listKey]，跨刷新也一样稳定。
 */
data class HomeUiState(
    val vaultName: String = "Looma",
    val vaultRootPath: String? = null,
    /** 首页全量笔记，已按 [sort] 排序 */
    val notes: List<HomeNoteItem> = emptyList(),
    /** 命中搜索框关键字的笔记，已按 [sort] 排序 */
    val searchResults: List<HomeNoteItem> = emptyList(),
    val query: String = "",
    /** 搜索框为空时展示的最近搜索，最新在前 */
    val recentSearches: List<String> = emptyList(),
    val isGridMode: Boolean = true,
    val sort: HomeSort = HomeSort.LAST_MODIFIED,
    val isLoading: Boolean = true,
    /** 用户下拉触发的刷新；和首次加载分开，避免回前台时下拉指示器闪一下 */
    val isRefreshing: Boolean = false,
    val isCreating: Boolean = false,
    val error: HomeError? = null,
    /** 一次性事件：新建成功后待打开的笔记，消费后置空 */
    val createdNote: NoteId? = null,
    /** 一次性事件：新建完成后把「最近」滚回最前面 */
    val scrollRecentsToStart: Boolean = false,
    /** 文件视图：当前浏览路径、面包屑、最近笔记与当前层的文件夹 / 笔记 */
    val files: HomeFilesState = HomeFilesState(),
    /** 当前选中的笔记 key */
    val selection: Set<String> = emptySet(),
    /** 选中项按当前排序展开，UI 直接读 */
    val selectedItems: List<HomeNoteItem> = emptyList(),
    /**
     * 长按文件夹后进入的动作目标。
     *
     * 和笔记多选互斥：顶栏只有一个，文件夹只支持单个，所以两边不能同时有值。
     */
    val selectedFolder: HomeFolderItem? = null,
    val dialog: HomeFileDialog? = null,
    val dialogError: HomeFileError? = null,
    val isActionRunning: Boolean = false,
    val message: HomeMessage? = null,
) {
    val isBrowsing: Boolean get() = !files.isAtRoot
    val isSelecting: Boolean get() = selection.isNotEmpty() || selectedFolder != null
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
