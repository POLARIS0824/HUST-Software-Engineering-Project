package com.example.looma.data.note

import com.example.looma.data.index.IndexDelta
import com.example.looma.data.index.IndexedNote
import com.example.looma.data.index.NoteIndex
import com.example.looma.data.index.VaultIndexer
import com.example.looma.data.index.indexedNote
import com.example.looma.data.vault.VaultRepository
import com.example.looma.domain.markdown.ImageContext
import com.example.looma.data.vault.VaultWriteCoordinator
import com.example.looma.domain.model.NoteRevision
import com.example.looma.domain.model.NoteWriteMode
import com.example.looma.domain.model.NotePath
import com.example.looma.domain.model.Vault
import com.example.looma.domain.model.VaultId
import com.example.looma.domain.model.VaultFolder
import com.example.looma.domain.model.FolderPath
import com.example.looma.domain.model.Note
import com.example.looma.domain.model.NoteId
import com.example.looma.domain.model.NoteProperties
import com.example.looma.domain.model.NoteSummary
import com.example.looma.domain.model.NoteTextStats
import com.example.looma.domain.model.StalledRelocation
import com.example.looma.domain.model.TrashedNote
import com.example.looma.domain.wiki.WikiContext
import com.example.looma.domain.wiki.WikiGraph
import com.example.looma.domain.wiki.WikiNote
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

class NoteRepository internal constructor(
    private val vaultRepository: VaultRepository,
    private val index: NoteIndex,
) {
    private val parser = MarkdownParser()
    private val textCache = NoteTextCache()
    private val stalledRelocations = ConcurrentHashMap<VaultId, StalledRelocation>()

    internal suspend fun listNotes(): NoteListResult {
        val current = currentVault()
        val stalled = VaultWriteCoordinator.write(current) {
            recoverPendingRelocations(current, retryStalled = true)
        }
        return read(current.id) { vault, _ ->
            val scan = VaultIndexer(File(vault.rootPath), vault, index).scan()
            NoteListResult(
                items = scan.notes
                    .map { it.toListEntry() }
                    .sortedByDescending { it.summary.modifiedAt },
                folders = scan.folders,
                unreadableCount = scan.unreadableCount,
                stalledRelocation = stalled,
            )
        }
    }

    suspend fun readTexts(ids: List<NoteId>): Map<NoteId, String> = read { vault, fileStore ->
        val texts = mutableMapOf<NoteId, String>()
        for (id in ids.filter { it.vaultId == vault.id }) {
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

    suspend fun readNote(id: NoteId): Note = read(id.vaultId) { _, store -> toNote(store.readNote(id)) }

    suspend fun imageContext(id: NoteId): ImageContext? = read(id.vaultId) { _, store -> store.imageContext(id) }

    suspend fun openNote(id: NoteId): OpenedNote = write(id.vaultId) { _, store ->
        val stored = store.readNote(id)
        val draft = store.readDraft(id)
        if (draft == null || draft.content == stored.content) {
            if (draft != null) store.deleteDraft(id)
            OpenedNote(toNote(stored), stored.content, restoredDraft = false)
        } else {
            OpenedNote(
                note = toNote(stored, draft.content).copy(revision = draft.baseRevision),
                diskContent = stored.content,
                restoredDraft = true,
                conflictRevision = stored.revision.takeIf { it != draft.baseRevision },
            )
        }
    }

    suspend fun reloadNote(id: NoteId): Note = write(id.vaultId) { _, store ->
        val stored = store.readNote(id)
        store.deleteDraft(id)
        toNote(stored)
    }

    suspend fun writeDraft(id: NoteId, content: String, baseRevision: NoteRevision) =
        write(id.vaultId) { _, store -> store.writeDraft(id, content, baseRevision) }

    suspend fun wikiContext(id: NoteId): WikiContext = read(id.vaultId) { _, _ ->
        withContext(Dispatchers.Default) { wikiGraphOf(index.load(id.vaultId)).context(id) }
    }

    suspend fun createNote(
        title: String,
        folder: FolderPath = FolderPath.Root,
    ): Note = write { _, fileStore ->
        val stored = fileStore.createNote(title, folder)
        textCache.put(stored.id, stored.modifiedAt, stored.content)
        writeThrough(IndexDelta(stored.id.vaultId, upserts = listOf(indexedOf(stored))))
        toNote(stored)
    }

    suspend fun saveNote(
        id: NoteId,
        content: String,
        mode: NoteWriteMode,
    ): Note = write(id.vaultId) { _, fileStore ->
        val stored = fileStore.saveNote(id, content, mode)
        textCache.put(stored.id, stored.modifiedAt, stored.content)
        writeThrough(IndexDelta(stored.id.vaultId, upserts = listOf(indexedOf(stored))))
        runCatching {
            if (fileStore.readDraft(id)?.content == content) fileStore.deleteDraft(id)
        }
        toNote(stored)
    }

    suspend fun renameNote(id: NoteId, newFilename: String): RenameOutcome =
        relocate(id) { it.renameNote(id, newFilename) }

    suspend fun moveNote(id: NoteId, target: FolderPath): RenameOutcome =
        relocate(id) { it.moveNote(id, target) }

    suspend fun deleteNote(id: NoteId): TrashedNote = write(id.vaultId) { _, store ->
        val trashed = store.deleteNote(id)
        textCache.remove(id)
        writeThrough(IndexDelta(id.vaultId, removed = listOf(id.path)))
        trashed
    }

    suspend fun discardNote(id: NoteId, expectedRevision: NoteRevision) = write(id.vaultId) { _, store ->
        val current = store.readNote(id)
        if (current.revision != expectedRevision) throw NoteChangedExternallyException(current.revision)
        store.discardNote(id)
        textCache.remove(id)
        writeThrough(IndexDelta(id.vaultId, removed = listOf(id.path)))
    }

    suspend fun listTrashedNotes(): List<TrashedNote> = read { _, store -> store.listTrashedNotes() }

    suspend fun restoreNote(trashId: String): Note = write { _, store ->
        val stored = store.restoreNote(trashId)
        textCache.put(stored.id, stored.modifiedAt, stored.content)
        writeThrough(IndexDelta(stored.id.vaultId, upserts = listOf(indexedOf(stored))))
        toNote(stored)
    }

    suspend fun deleteTrashedNote(trashId: String) = write { _, store -> store.deleteTrashedNote(trashId) }

    suspend fun restoreTrashedNotes(trashIds: List<String>): TrashBulkOutcome = write { _, store ->
        val (restored, outcome) = store.restoreNotes(trashIds)
        for (stored in restored) {
            textCache.put(stored.id, stored.modifiedAt, stored.content)
            writeThrough(IndexDelta(stored.id.vaultId, upserts = listOf(indexedOf(stored))))
        }
        outcome
    }

    suspend fun deleteTrashedNotes(trashIds: List<String>): TrashBulkOutcome =
        write { _, store -> store.deleteTrashedNotes(trashIds) }

    suspend fun properties(id: NoteId): NoteProperties = read(id.vaultId) { _, fileStore ->
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

    suspend fun createFolder(parent: FolderPath, name: String): VaultFolder = write { vault, _ ->
        val ops = VaultFileOps(File(vault.rootPath))
        val directory = ops.createFolder(parent, name)
        folder(vault, FolderPath(directory.relativeTo(ops.rootDir).invariantSeparatorsPath))
    }

    suspend fun renameFolder(path: FolderPath, newName: String): VaultFolder = write { vault, _ ->
        folder(vault, runRelocation(vault) { it.renameFolder(path, newName) }.path)
    }

    suspend fun moveFolder(path: FolderPath, destination: FolderPath): VaultFolder = write { vault, _ ->
        folder(vault, runRelocation(vault) { it.moveFolder(path, destination) }.path)
    }

    suspend fun deleteFolder(path: FolderPath): List<String> = write { vault, _ ->
        val records = VaultFileOps(File(vault.rootPath)).deleteFolder(path)
        reindex(vault, RelocationChanges(removedNotes = records.map { it.originalPath }))
        records.map { it.trashId }
    }

    suspend fun abandonStalledRelocation(journalId: String) {
        val vault = currentVault()
        VaultWriteCoordinator.write(vault) {
            VaultRelocation(vault).abandon(journalId)
            stalledRelocations.remove(vault.id)
        }
    }

    private suspend fun relocate(id: NoteId, operation: (VaultRelocation) -> RelocationResult<NotePath>): RenameOutcome =
        write(id.vaultId) { vault, store ->
            val result = runRelocation(vault, operation)
            val stored = store.readNote(NoteId(vault.id, result.path))
            RenameOutcome(toNote(stored), linksUpdated = result.linksUpdated, linksFailed = result.linksFailed)
        }

    private suspend fun <P> runRelocation(
        vault: Vault,
        operation: (VaultRelocation) -> RelocationResult<P>,
    ): RelocationResult<P> {
        recoverPendingRelocations(vault, retryStalled = true)?.let { throw RelocationStalledException(it) }
        val result = try {
            operation(VaultRelocation(vault))
        } catch (error: RelocationStalledException) {
            stalledRelocations[vault.id] = error.stalled
            throw error
        }
        stalledRelocations.remove(vault.id)
        reindex(vault, result.changes)
        return result
    }

    private suspend fun <T> read(
        expected: VaultId? = null,
        block: suspend (Vault, NoteFileStore) -> T,
    ): T {
        val vault = currentVault(expected)
        return withContext(Dispatchers.IO) { block(vault, NoteFileStore(vault)) }
    }

    private suspend fun <T> write(
        expected: VaultId? = null,
        block: suspend (Vault, NoteFileStore) -> T,
    ): T {
        val vault = currentVault(expected)
        return VaultWriteCoordinator.write(vault) {
            recoverPendingRelocations(vault, retryStalled = false)
            block(vault, NoteFileStore(vault))
        }
    }

    private suspend fun currentVault(expected: VaultId? = null): Vault {
        val vault = vaultRepository.requireCurrentVault()
        if (expected != null && vault.id != expected) throw VaultFileException(VaultFileFailure.NOT_FOUND)
        return vault
    }

    private suspend fun recoverPendingRelocations(vault: Vault, retryStalled: Boolean): StalledRelocation? {
        if (!retryStalled) stalledRelocations[vault.id]?.let { return it }
        val report = VaultRelocation(vault).recoverPending()
        reindex(vault, report.changes)
        val stalled = report.stalled
        if (stalled == null) {
            stalledRelocations.remove(vault.id)
        } else {
            stalledRelocations[vault.id] = stalled
        }
        return stalled
    }

    private suspend fun reindex(vault: Vault, changes: RelocationChanges) {
        if (changes.removedNotes.isEmpty() && changes.changedNotes.isEmpty()) return
        val root = VaultFileOps(File(vault.rootPath)).rootDir
        val changed = changes.changedNotes.distinct()
        (changes.removedNotes + changed).forEach { textCache.remove(NoteId(vault.id, it)) }
        val upserts = changed.mapNotNull { path ->
            val file = File(root, path.relativePath)
            try {
                val text = readMarkdown(file)
                indexedNote(vault.id, root, file, text, parser.parse(text, file.nameWithoutExtension))
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                null
            }
        }
        val removed = changes.removedNotes.distinct().filterNot { it in changed }
        writeThrough(IndexDelta(vault.id, upserts = upserts, removed = removed))
    }

    private fun folder(vault: Vault, path: FolderPath) = VaultFolder(
        vaultId = vault.id,
        path = path,
        name = path.relativePath.substringAfterLast('/'),
    )

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
            revision = stored.revision,
        )
    }
}

data class OpenedNote(
    val note: Note,
    val diskContent: String,
    val restoredDraft: Boolean,
    val conflictRevision: NoteRevision? = null,
)
