package com.example.looma.feature.home

import com.example.looma.domain.model.FolderPath
import com.example.looma.domain.model.NoteId
import com.example.looma.domain.model.NoteProperties
import com.example.looma.domain.model.NoteSummary
import java.util.Locale

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

/**
 * 首页唯一的选择事实：笔记多选、文件夹单选、未选择三者互斥。
 *
 * 用一类型而不是两个字段，是因为「同时选中笔记和文件夹」在界面上根本不存在，
 * 而两个字段的互斥只能靠每个赋值点自己记得清空另一个。
 */
sealed interface HomeSelection {
    data object None : HomeSelection

    /** 笔记多选。空集合收敛成 [None]，所以这里永远非空 */
    data class Notes(val keys: Set<String>) : HomeSelection {
        companion object {
            fun of(keys: Set<String>): HomeSelection = if (keys.isEmpty()) None else Notes(keys)
        }
    }

    data class Folder(val item: HomeFolderItem) : HomeSelection

    val noteKeys: Set<String> get() = (this as? Notes)?.keys.orEmpty()
    val folder: HomeFolderItem? get() = (this as? Folder)?.item
}

/** 一次可撤回操作的逆向数据；没有逆向的操作（例如彻底删除）就没有 Undo */
sealed interface HomeUndo {
    /** 把刚移入回收站的笔记搬回原路径 */
    data class RestoreNotes(val trashIds: List<String>) : HomeUndo

    /** 把刚改名的笔记改回原名 */
    data class RenameNote(val id: NoteId, val name: String) : HomeUndo

    /** 把刚移动的笔记搬回原目录 */
    data class MoveNotes(val moves: List<MovedNote>) : HomeUndo

    data class RenameFolder(val path: FolderPath, val name: String) : HomeUndo

    data class MoveFolder(val path: FolderPath, val parent: FolderPath) : HomeUndo

    /** 新建的文件夹：撤回就是把它删掉 */
    data class DeleteFolder(val path: FolderPath) : HomeUndo
}

/** 移动后的新 id（重名会续号）与它原来的目录 */
data class MovedNote(val id: NoteId, val folder: FolderPath)

/**
 * 一次性提示：文案种类加一个可选的撤回动作。
 *
 * Snackbar 是否显示 Undo 只由 [undo] 是否为空决定，屏幕不用再判断提示的种类。
 */
data class HomeMessage(
    val notice: HomeNotice,
    val undo: HomeUndo? = null,
)

/** 提示文案的种类；带计数的是因为结果本身就带着计数 */
sealed interface HomeNotice {
    data class Deleted(val count: Int) : HomeNotice
    data class Restored(val count: Int) : HomeNotice
    data class Moved(val count: Int) : HomeNotice

    /** 改名时顺手改写的引用数；有引用没改成功时也如实报出来 */
    data class Renamed(val linksUpdated: Int, val linksFailed: Int = 0) : HomeNotice
    data object FolderCreated : HomeNotice
    data object FolderRenamed : HomeNotice
    data object FolderMoved : HomeNotice

    /** 删除文件夹时顺带移进回收站的笔记数 */
    data class FolderDeleted(val notes: Int) : HomeNotice
    data object NameTaken : HomeNotice
    data object ActionFailed : HomeNotice
    data object InvalidMoveTarget : HomeNotice
    data object Undone : HomeNotice
    data object UndoFailed : HomeNotice
}

/** 选中笔记后可以打开的对话框；移动笔记与文件夹都改用系统目录选择器，所以没有移动对话框 */
sealed interface HomeFileDialog {
    data class Rename(val initialName: String) : HomeFileDialog
    data class Properties(val properties: NoteProperties) : HomeFileDialog
    data object NewFolder : HomeFileDialog

    /** 文件夹卡片长按或“更多”打开的小菜单 */
    data class RenameFolder(val path: FolderPath, val initialName: String) : HomeFileDialog
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

data class RecentReveal(val generation: Long, val noteId: NoteId)

internal fun RecentReveal.presentIn(recents: List<HomeRecentItem>): Boolean =
    recents.any { it.note.id == noteId }

internal fun HomeUiState.acknowledgeReveal(request: RecentReveal): HomeUiState =
    if (recentReveal == request) copy(recentReveal = null) else this

enum class HomeSort { LAST_MODIFIED, TITLE, TITLE_DESCENDING }

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
    /** 一次性事件：非选择态下点到的笔记，消费后置空 */
    val noteToOpen: NoteId? = null,
    /** 新建笔记的滚动请求，目标出现在列表且滚动完成后才确认 */
    val recentReveal: RecentReveal? = null,
    /** 文件视图：当前浏览路径、面包屑、最近笔记与当前层的文件夹 / 笔记 */
    val files: HomeFilesState = HomeFilesState(),
    /** 唯一的选择事实 */
    val selection: HomeSelection = HomeSelection.None,
    /** 选中项按当前排序展开，UI 直接读；只由 ViewModel 维护 */
    val selectedNotes: List<HomeNoteItem> = emptyList(),
    val dialog: HomeFileDialog? = null,
    val dialogError: HomeFileError? = null,
    val isActionRunning: Boolean = false,
    val message: HomeMessage? = null,
) {
    val isBrowsing: Boolean get() = !files.isAtRoot
    val isSelecting: Boolean get() = selection != HomeSelection.None
    val selectedFolder: HomeFolderItem? get() = selection.folder
    val selectedKeys: Set<String> get() = selection.noteKeys
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
    HomeSort.LAST_MODIFIED -> sortedWith(compareByDescending<HomeNoteItem> { it.summary.modifiedAt }.thenBy { it.listKey })
    HomeSort.TITLE -> sortedWith(compareBy<HomeNoteItem> { it.summary.title.lowercase(Locale.ROOT) }.thenBy { it.listKey })
    HomeSort.TITLE_DESCENDING -> sortedWith(compareByDescending<HomeNoteItem> { it.summary.title.lowercase(Locale.ROOT) }.thenBy { it.listKey })
}

/** 选择态下按当前排序展开选中项；[HomeSelection.Folder] 没有笔记，自然是空表 */
internal fun List<HomeNoteItem>.selected(
    selection: HomeSelection,
    sort: HomeSort,
): List<HomeNoteItem> = filter { it.listKey in selection.noteKeys }.orderedBy(sort)
