package com.example.looma.data.note

import com.example.looma.data.vault.VaultRepository
import com.example.looma.domain.model.Note
import com.example.looma.domain.model.NoteId
import com.example.looma.domain.model.NoteSummary
import com.example.looma.domain.model.TrashedNote
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** 向上层提供笔记数据访问接口，协调真实文件存储和 Markdown 元信息解析 */
class NoteRepository(vaultRepository: VaultRepository) {
    private val fileStore = NoteFileStore(vaultRepository)
    private val parser = MarkdownParser()

    suspend fun listNotes(): NoteListResult = withContext(Dispatchers.IO) {
        val scan = fileStore.scanNotes()
        val items = mutableListOf<NoteListEntry>()
        var unreadable = scan.unreadableCount

        for (stored in scan.notes) {
            coroutineContext.ensureActive()
            try {
                val metadata = parser.parse(stored.content, stored.file.nameWithoutExtension)
                items += NoteListEntry(
                    summary = NoteSummary(
                        id = stored.id,
                        title = metadata.title,
                        excerpt = metadata.excerpt,
                        modifiedAt = stored.modifiedAt,
                        tags = metadata.tags,
                    ),
                    metadata = metadata,
                    coverPath = fileStore.resolveCover(stored, metadata.firstImageReference),
                    searchText = stored.content,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                unreadable++
            }
        }

        NoteListResult(
            items = items.sortedByDescending { it.summary.modifiedAt },
            unreadableCount = unreadable,
        )
    }

    suspend fun readNote(id: NoteId): Note = toNote(fileStore.readNote(id))

    suspend fun createNote(
        title: String = "未命名笔记",
        initialContent: String? = null,
    ): Note = toNote(fileStore.createNote(title, initialContent))

    suspend fun saveNote(
        id: NoteId,
        content: String,
        expectedModifiedAt: Instant? = null,
    ): Note = toNote(fileStore.saveNote(id, content, expectedModifiedAt))

    suspend fun renameNote(id: NoteId, newFilename: String): Note =
        toNote(fileStore.renameNote(id, newFilename))

    suspend fun deleteNote(id: NoteId): TrashedNote = fileStore.deleteNote(id)

    suspend fun listTrashedNotes(): List<TrashedNote> = fileStore.listTrashedNotes()

    suspend fun restoreNote(trashId: String): Note = toNote(fileStore.restoreNote(trashId))

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
