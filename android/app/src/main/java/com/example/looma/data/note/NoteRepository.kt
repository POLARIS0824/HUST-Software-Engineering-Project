package com.example.looma.data.note

import com.example.looma.data.index.IndexDelta
import com.example.looma.data.index.IndexedNote
import com.example.looma.data.index.NoteIndex
import com.example.looma.data.index.VaultIndexer
import com.example.looma.data.index.indexedNote
import com.example.looma.data.vault.VaultRepository
import com.example.looma.domain.markdown.ImageContext
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

    suspend fun imageContext(id: NoteId): ImageContext? = fileStore.imageContext(id)

    suspend fun modifiedAtOf(id: NoteId): Instant = fileStore.modifiedAt(id)

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

    suspend fun writeDraft(id: NoteId, content: String) = fileStore.writeDraft(id, content)

    suspend fun backupConflict(id: NoteId): Boolean = runCatching {
        val stored = fileStore.readNote(id)
        fileStore.backupConflict(id, stored.content)
    }.isSuccess

    suspend fun wikiContext(id: NoteId): WikiContext = withContext(Dispatchers.Default) {
        wikiGraphOf(index.load(id.vaultId)).context(id)
    }

    suspend fun createNote(
        title: String,
        folder: FolderPath = FolderPath.Root,
    ): Note {
        val stored = fileStore.createNote(title, folder)
        textCache.put(stored.id, stored.modifiedAt, stored.content)
        writeThrough(IndexDelta(stored.id.vaultId, upserts = listOf(indexedOf(stored))))
        return toNote(stored)
    }

    suspend fun saveNote(
        id: NoteId,
        content: String,
        expectedModifiedAt: Instant? = null,
    ): Note {
        val stored = fileStore.saveNote(id, content, expectedModifiedAt)
        textCache.put(stored.id, stored.modifiedAt, stored.content)
        writeThrough(IndexDelta(stored.id.vaultId, upserts = listOf(indexedOf(stored))))
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

    suspend fun discardNote(id: NoteId) {
        fileStore.discardNote(id)
        textCache.remove(id)
        writeThrough(IndexDelta(id.vaultId, removed = listOf(id.path)))
    }

    suspend fun listTrashedNotes(): List<TrashedNote> = fileStore.listTrashedNotes()

    suspend fun restoreNote(trashId: String): Note {
        val stored = fileStore.restoreNote(trashId)
        textCache.put(stored.id, stored.modifiedAt, stored.content)
        writeThrough(IndexDelta(stored.id.vaultId, upserts = listOf(indexedOf(stored))))
        return toNote(stored)
    }

    suspend fun deleteTrashedNote(trashId: String) = fileStore.deleteTrashedNote(trashId)

    suspend fun restoreTrashedNotes(trashIds: List<String>): TrashBulkOutcome {
        val (restored, outcome) = fileStore.restoreNotes(trashIds)
        for (stored in restored) {
            textCache.put(stored.id, stored.modifiedAt, stored.content)
            writeThrough(IndexDelta(stored.id.vaultId, upserts = listOf(indexedOf(stored))))
        }
        return outcome
    }

    suspend fun deleteTrashedNotes(trashIds: List<String>): TrashBulkOutcome =
        fileStore.deleteTrashedNotes(trashIds)

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

    private fun moveDelta(oldId: NoteId, stored: StoredMarkdown): IndexDelta = IndexDelta(
        vaultId = stored.id.vaultId,
        upserts = listOf(indexedOf(stored)),
        removed = oldId.path.takeIf { it != stored.id.path }?.let { listOf(it) }.orEmpty(),
    )

    private suspend fun relocate(
        id: NoteId,
        operation: suspend () -> StoredMarkdown,
    ): RenameOutcome {
        val referrers = wikiGraphOf(index.load(id.vaultId)).referrersOf(id)
        val stored = operation()
        textCache.move(id, stored.id)
        writeThrough(moveDelta(id, stored))

        if (stored.id == id) {
            return RenameOutcome(note = toNote(stored), linksUpdated = 0, linksFailed = 0)
        }

        var linksUpdated = 0
        var linksFailed = 0
        withContext(Dispatchers.IO) {
            for (referrer in referrers) {
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
                    val saved = fileStore.saveNote(current, rewritten, expectedModifiedAt = modifiedAt)
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

data class OpenedNote(
    val note: Note,
    val restoredDraft: Boolean,
)
