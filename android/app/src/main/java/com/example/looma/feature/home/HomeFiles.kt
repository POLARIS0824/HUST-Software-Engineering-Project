package com.example.looma.feature.home

import com.example.looma.domain.model.FolderPath
import com.example.looma.domain.model.NotePath
import com.example.looma.domain.model.VaultFolder

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

data class HomeCrumb(val path: FolderPath, val label: String?)

data class HomeFolderItem(
    val path: FolderPath,
    val name: String,
)

data class HomeRecentItem(
    val note: HomeNoteItem,
    val markdown: String,
)

internal class HomeVaultIndex private constructor(
    private val foldersByParent: Map<FolderPath, List<VaultFolder>>,
    private val notesByParent: Map<FolderPath, List<HomeNoteItem>>,
    private val folderNames: Map<FolderPath, String>,
    private val allNotes: List<HomeNoteItem>,
) {

    fun contains(path: FolderPath): Boolean =
        path == FolderPath.Root || folderNames.containsKey(path)

    fun noteCountUnder(folder: FolderPath): Int {
        val prefix = folder.relativePath + "/"
        return allNotes.count { it.id.path.relativePath.startsWith(prefix) }
    }

    fun files(path: FolderPath, sort: HomeSort): HomeFilesState {
        val target = if (contains(path)) path else FolderPath.Root
        return HomeFilesState(
            path = target,
            crumbs = crumbs(target),
            recents = recents(),
            folders = foldersByParent[target].orEmpty()
                .sortedBy { it.name.lowercase() }
                .map { HomeFolderItem(path = it.path, name = it.name) },
            notes = notesByParent[target].orEmpty().orderedBy(sort),
        )
    }

    fun recents(limit: Int = RECENT_LIMIT): List<HomeRecentItem> =
        allNotes.orderedBy(HomeSort.LAST_MODIFIED)
            .take(limit)
            .map { HomeRecentItem(note = it, markdown = it.markdownSnippet) }

    private fun crumbs(path: FolderPath): List<HomeCrumb> {
        if (path == FolderPath.Root) return emptyList()
        val crumbs = mutableListOf(HomeCrumb(FolderPath.Root, null))
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

internal const val RECENT_LIMIT = 10

internal enum class FolderMotion { Forward, Backward, Replace }

internal fun folderMotion(from: FolderPath, to: FolderPath): FolderMotion = when {
    from == to -> FolderMotion.Replace
    from == FolderPath.Root || to.relativePath.startsWith(from.relativePath + "/") -> FolderMotion.Forward
    to == FolderPath.Root || from.relativePath.startsWith(to.relativePath + "/") -> FolderMotion.Backward
    else -> FolderMotion.Replace
}
