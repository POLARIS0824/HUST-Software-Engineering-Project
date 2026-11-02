package com.example.looma.data.note

import com.example.looma.data.index.IndexDelta
import com.example.looma.data.index.IndexedNote
import com.example.looma.data.index.NoteIndex
import com.example.looma.data.index.VaultIndexer
import com.example.looma.data.index.indexedNote
import com.example.looma.data.vault.VaultRepository
import com.example.looma.domain.model.FolderPath
import com.example.looma.domain.model.Note
import com.example.looma.domain.model.NoteId
import com.example.looma.domain.model.NoteProperties
import com.example.looma.domain.model.NoteSummary
import com.example.looma.domain.model.NoteTextStats
import com.example.looma.domain.model.TrashedNote
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
        return toNote(stored)
    }

    suspend fun renameNote(id: NoteId, newFilename: String): Note {
        val stored = fileStore.renameNote(id, newFilename)
        textCache.move(id, stored.id)
        writeThrough(moveDelta(id, stored))
        return toNote(stored)
    }

    suspend fun moveNote(id: NoteId, target: FolderPath): Note {
        val stored = fileStore.moveNote(id, target)
        textCache.move(id, stored.id)
        writeThrough(moveDelta(id, stored))
        return toNote(stored)
    }

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

    private suspend fun toNote(stored: StoredMarkdown): Note = withContext(Dispatchers.Default) {
        val metadata = parser.parse(stored.content, stored.file.nameWithoutExtension)
        Note(
            id = stored.id,
            title = metadata.title,
            content = stored.content,
            modifiedAt = stored.modifiedAt,
            tags = metadata.tags,
        )
    }
}
