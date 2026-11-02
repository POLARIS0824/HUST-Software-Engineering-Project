package com.example.looma.domain.wiki

import com.example.looma.domain.model.NoteId
import com.example.looma.domain.model.NotePath

/** 解析与反查只依赖这两件事实：路径决定名字，标题只用于展示 */
data class WikiNote(
    val id: NoteId,
    val title: String,
)

/** 一条反向链接：谁引用了这篇笔记 */
data class Backlink(
    val id: NoteId,
    val title: String,
)

/**
 * 一篇笔记的双链视图快照。
 *
 * 解析结果只在这里出现一次：正文里每个目标解析到哪篇笔记。
 * 快照是值，索引一变就要重新取，界面不自己判断"应该解析得到"。
 */
data class WikiContext(
    private val targets: Map<String, NoteId> = emptyMap(),
    val backlinks: List<Backlink> = emptyList(),
    /** 可以链到的其它笔记，按标题排序；插入双链时的候选来源 */
    val linkables: List<WikiNote> = emptyList(),
) {
    fun resolve(target: String): NoteId? = targets[normalizeWikiName(target)]

    companion object {
        val Empty = WikiContext()
    }
}

/**
 * 一次索引快照上的双链图，纯计算，不读盘。
 *
 * 名字规则只有这一份：`Note`、`Folder/Note`、`Note.md` 都归一到同一个键；
 * 重名时先看来源所在目录，再看路径长短与字典序，保证同一快照结果稳定。
 */
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

    /** 目标解析不到就返回 null，界面据此显示未解析；不抛错，也不猜"最像"的那篇 */
    fun resolve(target: String, from: NotePath): NoteId? {
        val key = normalizeWikiName(target)
        if (key.isEmpty()) return null
        byPath[key]?.let { return it.id }

        val candidates = if ('/' in key) {
            // 省略前导目录的写法：`[[Sub/Note]]` 也能找到 `A/Sub/Note.md`
            val suffix = "/$key"
            all.filter { normalizeWikiName(it.id.path.relativePath).endsWith(suffix) }
        } else {
            byName[key].orEmpty()
        }
        if (candidates.isEmpty()) return null

        val sourceFolder = folderOf(from)
        return (candidates.firstOrNull { folderOf(it.id.path) == sourceFolder } ?: candidates.first()).id
    }

    /** 谁引用了这篇笔记；自引用不算反向链接 */
    fun backlinksOf(note: NoteId): List<Backlink> = linksByNote.entries
        .asSequence()
        .filter { (source, targets) -> source != note && targets.any { resolve(it, source.path) == note } }
        .map { (source, _) -> Backlink(id = source, title = titles[source].orEmpty().ifBlank { baseNameOf(source.path) }) }
        .sortedWith(compareBy({ it.title.lowercase() }, { it.id.path.relativePath }))
        .toList()

    /** 改名或移动之前要找的改写对象，包含它自己：笔记内部的 `[[旧名]]` 也要跟着改 */
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
        /** `A/B/Note.md` → `note` */
        private fun baseNameOf(path: NotePath): String = normalizeWikiName(path.relativePath.substringAfterLast('/'))
    }
}

/** 双链名字的归一化：去首尾空白、去 `.md`、分隔符统一、小写；解析和索引都用它 */
fun normalizeWikiName(raw: String): String {
    val slashed = raw.trim().replace('\\', '/')
    val withoutSuffix = if (slashed.endsWith(".md", ignoreCase = true)) slashed.dropLast(3) else slashed
    return withoutSuffix.trim('/').lowercase()
}

/** `A/B/Note.md` 的目标写法（去掉扩展名） */
fun wikiPathOf(path: NotePath): String =
    path.relativePath.replace('\\', '/').let { if (it.endsWith(".md")) it.dropLast(3) else it }

/** 不带目录的目标写法 */
fun wikiNameOf(path: NotePath): String = wikiPathOf(path).substringAfterLast('/')

/**
 * 改名或移动后的目标对照：原本写文件名的继续写文件名，写成路径的跟着换到新路径。
 * 返回 null 的引用不需要动，因此调用方可以把它直接交给 [WikiLinks.retarget]。
 */
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
