package com.example.looma.domain.wiki

import com.example.looma.domain.model.NoteId
import com.example.looma.domain.model.NotePath

data class WikiNote(
    val id: NoteId,
    val title: String,
)

data class Backlink(
    val id: NoteId,
    val title: String,
)

data class WikiContext(
    private val targets: Map<String, NoteId> = emptyMap(),
    val backlinks: List<Backlink> = emptyList(),
    val linkables: List<WikiNote> = emptyList(),
) {
    fun resolve(target: String): NoteId? = targets[normalizeWikiName(target)]

    companion object {
        val Empty = WikiContext()
    }
}

class WikiGraph(
    notes: List<WikiNote>,
    private val linksByNote: Map<NoteId, List<String>>,
) {
    private val byPath: Map<String, WikiNote>
    private val byName: Map<String, List<WikiNote>>
    private val all: List<WikiNote>
    private val titles: Map<NoteId, String>

    init {
        val sorted = notes.sortedWith(compareBy({ it.id.path.relativePath.length }, { it.id.path.relativePath }))
        byPath = sorted.associateBy { normalizeWikiName(it.id.path.relativePath) }
        byName = sorted.groupBy { baseNameOf(it.id.path) }
        all = sorted
        titles = notes.associate { it.id to it.title }
    }

    fun resolve(target: String, from: NotePath): NoteId? {
        val key = normalizeWikiName(target)
        if (key.isEmpty()) return null
        byPath[key]?.let { return it.id }

        val candidates = if ('/' in key) {
            val suffix = "/$key"
            all.filter { normalizeWikiName(it.id.path.relativePath).endsWith(suffix) }
        } else {
            byName[key].orEmpty()
        }
        if (candidates.isEmpty()) return null

        val sourceFolder = folderOf(from)
        return (candidates.firstOrNull { folderOf(it.id.path) == sourceFolder } ?: candidates.first()).id
    }

    fun backlinksOf(note: NoteId): List<Backlink> = linksByNote.entries
        .asSequence()
        .filter { (source, targets) -> source != note && targets.any { resolve(it, source.path) == note } }
        .map { (source, _) -> Backlink(id = source, title = titles[source].orEmpty().ifBlank { baseNameOf(source.path) }) }
        .sortedWith(compareBy({ it.title.lowercase() }, { it.id.path.relativePath }))
        .toList()

    fun referrersOf(note: NoteId): List<NoteId> = linksByNote.entries
        .asSequence()
        .filter { (source, targets) -> source == note || targets.any { resolve(it, source.path) == note } }
        .map { it.key }
        .sortedBy { it.path.relativePath }
        .toList()

    fun context(note: NoteId): WikiContext = WikiContext(
        targets = linksByNote[note].orEmpty()
            .mapNotNull { target -> resolve(target, note.path)?.let { normalizeWikiName(target) to it } }
            .toMap(),
        backlinks = backlinksOf(note),
        linkables = all
            .asSequence()
            .filter { it.id != note }
            .map { WikiNote(it.id, titles[it.id].orEmpty().ifBlank { baseNameOf(it.id.path) }) }
            .sortedWith(compareBy({ it.title.lowercase() }, { it.id.path.relativePath }))
            .toList(),
    )

    private fun folderOf(path: NotePath): String = path.relativePath.substringBeforeLast('/', missingDelimiterValue = "")

    companion object {
        private fun baseNameOf(path: NotePath): String = normalizeWikiName(path.relativePath.substringAfterLast('/'))
    }
}

fun normalizeWikiName(raw: String): String {
    val slashed = raw.trim().replace('\\', '/')
    val withoutSuffix = if (slashed.endsWith(".md", ignoreCase = true)) slashed.dropLast(3) else slashed
    return withoutSuffix.trim('/').lowercase()
}

fun wikiPathOf(path: NotePath): String =
    path.relativePath.replace('\\', '/').let { if (it.endsWith(".md")) it.dropLast(3) else it }

fun wikiNameOf(path: NotePath): String = wikiPathOf(path).substringAfterLast('/')

fun retargetAfterRename(old: NotePath, new: NotePath): (String) -> String? {
    val oldName = normalizeWikiName(wikiNameOf(old))
    val oldPath = normalizeWikiName(wikiPathOf(old))
    return { target ->
        when (normalizeWikiName(target)) {
            oldName -> wikiNameOf(new)
            oldPath -> wikiPathOf(new)
            else -> null
        }
    }
}
