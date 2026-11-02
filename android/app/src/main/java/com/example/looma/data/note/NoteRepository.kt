package com.example.looma.data.note

import com.example.looma.data.index.IndexDelta
import com.example.looma.data.index.IndexedNote
import com.example.looma.data.index.NoteIndex
import com.example.looma.data.index.VaultIndexer
import com.example.looma.data.index.indexedNote
import com.example.looma.data.vault.VaultRepository
import com.example.looma.domain.markdown.MarkdownIndex
import com.example.looma.domain.markdown.WikiLinks
import com.example.looma.domain.model.FolderPath
import com.example.looma.domain.model.Note
import com.example.looma.domain.model.NoteId
import com.example.looma.domain.model.NoteProperties
import com.example.looma.domain.model.NoteSummary
import com.example.looma.domain.model.NoteTextStats
import com.example.looma.domain.model.TrashedNote
import com.example.looma.domain.wiki.WikiContext
import com.example.looma.domain.wiki.WikiGraph
import com.example.looma.domain.wiki.WikiNote
import com.example.looma.domain.wiki.retargetAfterRename
import java.io.File
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/**
 * 向上层提供笔记数据访问接口，协调真实文件存储、Markdown 解析与索引缓存。
 *
 * 文件才是唯一的内容来源：列表读索引，正文按需回读文件。
 * 索引只是缓存，任何写索引失败都不允许让文件操作失败。
 */
class NoteRepository internal constructor(
    private val vaultRepository: VaultRepository,
    private val index: NoteIndex,
) {
    private val fileStore = NoteFileStore(vaultRepository)
    private val parser = MarkdownParser()
    private val textCache = NoteTextCache()

    internal suspend fun listNotes(): NoteListResult {
        val vault = vaultRepository.requireCurrentVault()
        val scan = VaultIndexer(File(vault.rootPath), vault, index).scan()
        return NoteListResult(
            items = scan.notes
                .map { it.toListEntry() }
                .sortedByDescending { it.summary.modifiedAt },
            folders = scan.folders,
            unreadableCount = scan.unreadableCount,
        )
    }

    /** 搜索时补齐正文；返回磁盘上的最新内容，读不到的直接缺席 */
    suspend fun readTexts(ids: List<NoteId>): Map<NoteId, String> = withContext(Dispatchers.IO) {
        val texts = mutableMapOf<NoteId, String>()
        for (id in ids) {
            coroutineContext.ensureActive()
            val modifiedAt = runCatching { fileStore.modifiedAt(id) }.getOrNull() ?: continue
            val cached = textCache.get(id, modifiedAt)
            if (cached != null) {
                texts[id] = cached
                continue
            }
            runCatching { fileStore.readText(id) }.onSuccess { (text, readAt) ->
                textCache.put(id, readAt, text)
                texts[id] = text
            }
        }
        texts
    }

    suspend fun readNote(id: NoteId): Note = toNote(fileStore.readNote(id))

    /** 重试保存前重新取一次修改时间：先分清"刚才写失败"和"别人真的改过" */
    suspend fun modifiedAtOf(id: NoteId): Instant = fileStore.modifiedAt(id)

    /**
     * 打开一篇笔记，并把还没落盘的草稿找回来。
     *
     * 草稿比正文新才作数：上一次保存成功之后草稿就该被删掉，
     * 留下来的只可能是"写了但没写进去"的那一份。
     */
    suspend fun openNote(id: NoteId): OpenedNote {
        val stored = fileStore.readNote(id)
        val draft = fileStore.readDraft(id, stored.modifiedAt)
        if (draft == null || draft == stored.content) {
            return OpenedNote(toNote(stored), restoredDraft = false)
        }
        return OpenedNote(
            note = toNote(stored, content = draft),
            restoredDraft = true,
        )
    }

    /** 应用退到后台时先把缓冲区写进草稿区，进程被杀也不至于丢字 */
    suspend fun writeDraft(id: NoteId, content: String) = fileStore.writeDraft(id, content)

    /** 覆盖保存之前，把磁盘上被别人改过的那一份留档 */
    suspend fun backupConflict(id: NoteId): Boolean = runCatching {
        val stored = fileStore.readNote(id)
        fileStore.backupConflict(id, stored.content)
    }.isSuccess

    /**
     * 只读索引拼出的双链快照：正文里的目标各解析到哪篇笔记，哪几篇又引用了它。
     *
     * 不扫库也不读盘，所以新鲜度最多等于最后一次扫描或写穿；索引还没跟上的改动这里看不到。
     */
    suspend fun wikiContext(id: NoteId): WikiContext = withContext(Dispatchers.Default) {
        wikiGraphOf(index.load(id.vaultId)).context(id)
    }

    suspend fun createNote(
        title: String,
        initialContent: String? = null,
        folder: FolderPath = FolderPath.Root,
    ): Note {
        val stored = fileStore.createNote(title, initialContent, folder)
        textCache.put(stored.id, stored.modifiedAt, stored.content)
        writeThrough(IndexDelta(stored.id.vaultId, upserts = listOf(indexedOf(stored))))
        return toNote(stored)
    }

    suspend fun saveNote(
        id: NoteId,
        content: String,
        expectedModifiedAt: Instant? = null,
    ): Note {
        // 文件被别人改过时这里会抛；索引必须保持旧值，不能替用户“确认”这次丢失的保存
        val stored = fileStore.saveNote(id, content, expectedModifiedAt)
        textCache.put(stored.id, stored.modifiedAt, stored.content)
        writeThrough(IndexDelta(stored.id.vaultId, upserts = listOf(indexedOf(stored))))
        // 正文已经落盘，草稿没有存在价值了；删不掉也只是多占一份副本
        runCatching { fileStore.deleteDraft(id) }
        return toNote(stored)
    }

    suspend fun renameNote(id: NoteId, newFilename: String): RenameOutcome =
        relocate(id) { fileStore.renameNote(id, newFilename) }

    suspend fun moveNote(id: NoteId, target: FolderPath): RenameOutcome =
        relocate(id) { fileStore.moveNote(id, target) }

    suspend fun deleteNote(id: NoteId): TrashedNote {
        val trashed = fileStore.deleteNote(id)
        textCache.remove(id)
        writeThrough(IndexDelta(id.vaultId, removed = listOf(id.path)))
        return trashed
    }

    suspend fun listTrashedNotes(): List<TrashedNote> = fileStore.listTrashedNotes()

    suspend fun restoreNote(trashId: String): Note {
        val stored = fileStore.restoreNote(trashId)
        textCache.put(stored.id, stored.modifiedAt, stored.content)
        writeThrough(IndexDelta(stored.id.vaultId, upserts = listOf(indexedOf(stored))))
        return toNote(stored)
    }

    /** 从回收站彻底删除，不可恢复 */
    suspend fun deleteTrashedNote(trashId: String) = fileStore.deleteTrashedNote(trashId)

    /** 文件属性：磁盘大小与修改时间来自文件，字数与任务数来自正文 */
    suspend fun properties(id: NoteId): NoteProperties = withContext(Dispatchers.Default) {
        val note = toNote(fileStore.readNote(id))
        val stats = fileStore.noteStats(id)
        val metadata = parser.parse(note.content, id.path.relativePath)
        NoteProperties(
            path = id.path,
            sizeBytes = stats.sizeBytes,
            modifiedAt = stats.modifiedAt,
            words = NoteTextStats.words(note.content),
            characters = NoteTextStats.characters(note.content),
            checkboxesDone = metadata.checklist.count { it.checked },
            checkboxesTotal = metadata.checklist.size,
        )
    }

    /** 改名与移动可能原地不动，这时旧 path 不能出现在 removed 里，否则刚写的行会被删掉 */
    private fun moveDelta(oldId: NoteId, stored: StoredMarkdown): IndexDelta = IndexDelta(
        vaultId = stored.id.vaultId,
        upserts = listOf(indexedOf(stored)),
        removed = oldId.path.takeIf { it != stored.id.path }?.let { listOf(it) }.orEmpty(),
    )

    /**
     * 改名与移动的共用流程：按索引快照找出引用方，动文件，再逐个把引用方的正文改过来。
     *
     * 文件操作失败时原样抛出：引用方一个字都没改，索引仍是旧路径，上层现有的错误提示依然成立。
     * 引用改写失败只计数，不能让已经成功的改名反过来算失败。
     */
    private suspend fun relocate(
        id: NoteId,
        operation: suspend () -> StoredMarkdown,
    ): RenameOutcome {
        // 快照必须在动文件之前取：文件一搬走，旧路径就从索引里消失了
        val referrers = wikiGraphOf(index.load(id.vaultId)).referrersOf(id)
        val stored = operation()
        textCache.move(id, stored.id)
        writeThrough(moveDelta(id, stored))

        // 原地改名/移动没有换目标名，再走一遍只会把每个引用改写成同样的字面量、白白刷新 mtime
        if (stored.id == id) {
            return RenameOutcome(note = toNote(stored), linksUpdated = 0, linksFailed = 0)
        }

        var linksUpdated = 0
        var linksFailed = 0
        withContext(Dispatchers.IO) {
            for (referrer in referrers) {
                // 被改名的笔记自己也在 referrers 里，但它此时已经在新的路径上
                val current = if (referrer == id) stored.id else referrer
                try {
                    val (text, modifiedAt) = fileStore.readText(current)
                    val filename = current.path.relativePath.substringAfterLast('/').removeSuffix(".md")
                    val spans = MarkdownIndex.index(text, filename).links
                    val rewritten = WikiLinks.retarget(
                        source = text,
                        spans = spans,
                        retarget = retargetAfterRename(id.path, stored.id.path),
                    ) ?: continue
                    // 拿刚读到的 mtime 当乐观锁：期间被别人改过就抛，宁可这次漏改也不覆盖别人的编辑
                    val saved = fileStore.saveNote(current, rewritten, expectedModifiedAt = modifiedAt)
                    // 缓存和索引都还是改写前的正文，必须跟着换掉
                    textCache.remove(current)
                    writeThrough(IndexDelta(saved.id.vaultId, upserts = listOf(indexedOf(saved))))
                    linksUpdated++
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    linksFailed++
                }
            }
        }
        return RenameOutcome(
            note = toNote(stored),
            linksUpdated = linksUpdated,
            linksFailed = linksFailed,
        )
    }

    /** 索引快照到双链图只在这里发生一次，名字解析规则全部留在 [WikiGraph] 里 */
    private fun wikiGraphOf(indexed: List<IndexedNote>): WikiGraph = WikiGraph(
        notes = indexed.map { note -> WikiNote(note.id, note.title) },
        linksByNote = indexed.associate { note -> note.id to note.links.map { link -> link.target } },
    )

    private fun IndexedNote.toListEntry(): NoteListEntry = NoteListEntry(
        summary = NoteSummary(
            id = id,
            title = title,
            excerpt = excerpt,
            modifiedAt = modifiedAt,
            tags = tags.toSet(),
        ),
        coverPath = coverPath,
        markdownSnippet = snippet,
        // 索引不存正文；搜索先用摘要与片段兜底，UI 需要更全的文本时走 readTexts
        searchText = snippet,
    )

    private fun indexedOf(stored: StoredMarkdown): IndexedNote {
        val metadata = parser.parse(stored.content, stored.file.nameWithoutExtension)
        return indexedNote(stored.id.vaultId, stored.root, stored.file, stored.content, metadata)
    }

    private suspend fun writeThrough(delta: IndexDelta) {
        runCatching { index.apply(delta) }.onFailure { error ->
            if (error is CancellationException) throw error
        }
    }

    private suspend fun toNote(
        stored: StoredMarkdown,
        content: String = stored.content,
    ): Note = withContext(Dispatchers.Default) {
        val metadata = parser.parse(content, stored.file.nameWithoutExtension)
        Note(
            id = stored.id,
            title = metadata.title,
            content = content,
            modifiedAt = stored.modifiedAt,
            tags = metadata.tags,
        )
    }
}

/** 打开结果：正文可能来自文件，也可能来自一份更新的草稿 */
data class OpenedNote(
    val note: Note,
    val restoredDraft: Boolean,
)
