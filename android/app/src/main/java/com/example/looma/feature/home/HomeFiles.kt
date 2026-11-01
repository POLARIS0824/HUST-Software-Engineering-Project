package com.example.looma.feature.home

import com.example.looma.domain.model.FolderPath
import com.example.looma.domain.model.NotePath
import com.example.looma.domain.model.VaultFolder

/**
 * 首页文件视图要渲染的一屏数据。
 *
 * [folders] 与 [notes] 已经在 ViewModel 里排好序，组合阶段只负责画：
 * 文件夹永远在笔记前面，所以它们分成两个列表，而不是一个带类型的混合列表。
 */
data class HomeFilesState(
    val path: FolderPath = FolderPath.Root,
    val crumbs: List<HomeCrumb> = emptyList(),
    val recents: List<HomeRecentItem> = emptyList(),
    val folders: List<HomeFolderItem> = emptyList(),
    val notes: List<HomeNoteItem> = emptyList(),
) {
    val isAtRoot: Boolean get() = path == FolderPath.Root
    val isEmpty: Boolean get() = folders.isEmpty() && notes.isEmpty()
}

data class HomeCrumb(val path: FolderPath, val label: String)

data class HomeFolderItem(
    val path: FolderPath,
    val name: String,
    /** 直接子项数量，和点进去看到的条目数一致 */
    val itemCount: Int,
    /** 前几个子项名称，空文件夹给一句占位文案 */
    val preview: String,
)

data class HomeRecentItem(
    val note: HomeNoteItem,
    /** 缩略图里显示的正文开头，已折叠空白并截断，永不为空 */
    val snippet: String,
)

/**
 * 一次扫描结果的只读快照。
 *
 * 所有页面数据都由它投影出来，浏览文件夹、排序、搜索都不再碰磁盘。
 * 它不进 [HomeUiState]：对外只暴露投影后的列表，避免组合阶段拿它自行过滤。
 */
internal class HomeVaultIndex private constructor(
    private val foldersByParent: Map<FolderPath, List<VaultFolder>>,
    private val notesByParent: Map<FolderPath, List<HomeNoteItem>>,
    private val folderNames: Map<FolderPath, String>,
    private val allNotes: List<HomeNoteItem>,
) {

    fun contains(path: FolderPath): Boolean =
        path == FolderPath.Root || folderNames.containsKey(path)

    /** 找不到的路径回落到根目录，用于外部删除文件夹后的收敛 */
    fun files(path: FolderPath, sort: HomeSort): HomeFilesState {
        val target = if (contains(path)) path else FolderPath.Root
        val folders = foldersByParent[target].orEmpty()
            .sortedBy { it.name.lowercase() }
            .map(::toFolderItem)
        return HomeFilesState(
            path = target,
            crumbs = crumbs(target),
            recents = recents(),
            folders = folders,
            notes = notesByParent[target].orEmpty().orderedBy(sort),
        )
    }

    fun recents(limit: Int = RECENT_LIMIT): List<HomeRecentItem> =
        allNotes.sortedByDescending { it.summary.modifiedAt }
            .take(limit)
            .map { HomeRecentItem(note = it, snippet = it.recentSnippet()) }

    private fun toFolderItem(folder: VaultFolder): HomeFolderItem {
        val childFolders = foldersByParent[folder.path].orEmpty().sortedBy { it.name.lowercase() }
        val childNotes = notesByParent[folder.path].orEmpty().sortedBy { it.summary.title.lowercase() }
        val names = childFolders.map { it.name } + childNotes.map { it.summary.title }
        val preview = when {
            names.isEmpty() -> EMPTY_FOLDER_TEXT
            names.size > PREVIEW_LIMIT -> names.take(PREVIEW_LIMIT).joinToString(" · ") + " …"
            else -> names.joinToString(" · ")
        }
        return HomeFolderItem(
            path = folder.path,
            name = folder.name,
            itemCount = childFolders.size + childNotes.size,
            preview = preview,
        )
    }

    private fun crumbs(path: FolderPath): List<HomeCrumb> {
        if (path == FolderPath.Root) return emptyList()
        val crumbs = mutableListOf(HomeCrumb(FolderPath.Root, ROOT_LABEL))
        var current = ""
        path.relativePath.split('/').forEach { segment ->
            current = if (current.isEmpty()) segment else "$current/$segment"
            val folderPath = FolderPath(current)
            crumbs += HomeCrumb(folderPath, folderNames[folderPath] ?: segment)
        }
        return crumbs
    }

    companion object {
        val Empty: HomeVaultIndex = HomeVaultIndex(emptyMap(), emptyMap(), emptyMap(), emptyList())

        /**
         * [folders] 来自扫描，包含空目录；笔记的祖先目录也会补进来，
         * 这样任何一条笔记都不会落到不存在的父文件夹里。
         */
        fun build(folders: List<VaultFolder>, notes: List<HomeNoteItem>): HomeVaultIndex {
            val names = folders.associateTo(mutableMapOf()) { it.path to it.name }
            val known = folders.toMutableList()
            notes.forEach { note ->
                var parent = note.id.path.parentFolder()
                while (parent != FolderPath.Root && parent !in names) {
                    val name = parent.relativePath.substringAfterLast('/')
                    names[parent] = name
                    known += VaultFolder(note.id.vaultId, parent, name)
                    parent = parent.parentFolder()
                }
            }
            return HomeVaultIndex(
                foldersByParent = known.groupBy { it.path.parentFolder() },
                notesByParent = notes.groupBy { it.id.path.parentFolder() },
                folderNames = names,
                allNotes = notes,
            )
        }
    }
}

internal fun NotePath.parentFolder(): FolderPath {
    val slash = relativePath.lastIndexOf('/')
    return if (slash < 0) FolderPath.Root else FolderPath(relativePath.substring(0, slash))
}

internal fun FolderPath.parentFolder(): FolderPath {
    val slash = relativePath.lastIndexOf('/')
    return when {
        relativePath.isEmpty() -> this
        slash < 0 -> FolderPath.Root
        else -> FolderPath(relativePath.substring(0, slash))
    }
}

internal fun HomeNoteItem.recentSnippet(): String {
    val text = summary.excerpt
        .replace(LITERAL_NEWLINE, " ")
        .replace(WHITESPACE, " ")
        .trim()
        .take(RECENT_SNIPPET_CHARS)
    return text.ifBlank { EMPTY_NOTE_TEXT }
}

internal const val RECENT_LIMIT = 10
internal const val RECENT_SNIPPET_CHARS = 60
internal const val PREVIEW_LIMIT = 3
internal const val ROOT_LABEL = "首页"
internal const val EMPTY_FOLDER_TEXT = "还没有内容"
internal const val EMPTY_NOTE_TEXT = "空笔记"

private val LITERAL_NEWLINE = Regex("""\\n""")
private val WHITESPACE = Regex("""\s+""")
