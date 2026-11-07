package com.example.looma.data.note

import com.example.looma.domain.markdown.ImageContext
import com.example.looma.domain.model.FolderPath
import com.example.looma.domain.model.NoteId
import com.example.looma.domain.model.NotePath
import com.example.looma.domain.model.NoteRevision
import com.example.looma.domain.model.NoteWriteMode
import com.example.looma.domain.model.TrashedNote
import com.example.looma.domain.model.Vault
import com.example.looma.domain.model.VaultId
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Properties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class StoredMarkdown(
    val id: NoteId,
    val content: String,
    val modifiedAt: Instant,
    val root: File,
    val file: File,
    val revision: NoteRevision,
)

internal data class StoredDraft(
    val content: String,
    val baseRevision: NoteRevision,
)

internal data class StoredNoteStats(
    val path: NotePath,
    val sizeBytes: Long,
    val modifiedAt: Instant,
)

internal class NoteFileStore(
    private val vault: Vault,
) {
    suspend fun readNote(id: NoteId): StoredMarkdown = withContext(Dispatchers.IO) {
        val (vault, ops) = currentVault(id.vaultId)
        val file = ops.noteFile(id.path)
        storedMarkdown(vault, ops.rootDir, file, readMarkdownBytes(file))
    }

    suspend fun imageContext(id: NoteId): ImageContext? = withContext(Dispatchers.IO) {
        val (_, ops) = currentVault(id.vaultId)
        ImageContext(
            vaultRoot = ops.rootDir.absolutePath,
            noteFile = ops.noteFile(id.path).absolutePath,
        )
    }

    suspend fun noteStats(id: NoteId): StoredNoteStats = withContext(Dispatchers.IO) {
        val (_, ops) = currentVault(id.vaultId)
        val file = ops.noteFile(id.path)
        StoredNoteStats(
            path = id.path,
            sizeBytes = file.length(),
            modifiedAt = Instant.ofEpochMilli(file.lastModified()),
        )
    }

    suspend fun modifiedAt(id: NoteId): Instant = withContext(Dispatchers.IO) {
        val (_, ops) = currentVault(id.vaultId)
        Instant.ofEpochMilli(ops.noteFile(id.path).lastModified())
    }

    suspend fun readText(id: NoteId): Pair<String, Instant> = withContext(Dispatchers.IO) {
        val (_, ops) = currentVault(id.vaultId)
        val file = ops.noteFile(id.path)
        readMarkdown(file) to Instant.ofEpochMilli(file.lastModified())
    }

    suspend fun createNote(
        title: String,
        folder: FolderPath = FolderPath.Root,
    ): StoredMarkdown = withContext(Dispatchers.IO) {
        val ops = VaultFileOps(File(vault.rootPath))
        val file = ops.newNoteFile(folder, title, fallbackName = UNTITLED_FALLBACK)
        storedMarkdown(vault, ops.rootDir, file, byteArrayOf())
    }

    suspend fun discardNote(id: NoteId) = withContext(Dispatchers.IO) {
        val (_, ops) = currentVault(id.vaultId)
        val file = ops.noteFile(id.path)
        if (!file.delete()) throw VaultFileException(VaultFileFailure.IO)
        deleteDraftFiles(ops.rootDir, id.path)
    }

    suspend fun saveNote(
        id: NoteId,
        content: String,
        mode: NoteWriteMode,
    ): StoredMarkdown = withContext(Dispatchers.IO) {
        val (vault, ops) = currentVault(id.vaultId)
        val file = ops.noteFile(id.path)
        val current = readMarkdownBytes(file)
        val actualRevision = NoteRevision.of(current)
        val expectedRevision = when (mode) {
            is NoteWriteMode.Checked -> mode.revision
            is NoteWriteMode.Overwrite -> mode.revision
        }
        if (actualRevision != expectedRevision) throw NoteChangedExternallyException(actualRevision)
        val bytes = markdownBytes(content)
        if (mode is NoteWriteMode.Checked && content == current.toString(Charsets.UTF_8)) {
            return@withContext storedMarkdown(vault, ops.rootDir, file, current)
        }
        if (mode is NoteWriteMode.Overwrite) backupConflictBytes(ops.rootDir, id.path, current)
        writeBytesAtomically(file, bytes) {
            val observed = if (file.isFile) NoteRevision.of(readMarkdownBytes(file)) else NoteRevision.Unknown
            if (observed != actualRevision) throw NoteChangedExternallyException(observed)
        }
        storedMarkdown(vault, ops.rootDir, file, bytes)
    }

    suspend fun deleteNote(id: NoteId): TrashedNote = withContext(Dispatchers.IO) {
        val (_, ops) = currentVault(id.vaultId)
        val record = ops.trash(ops.noteFile(id.path))
        TrashedNote(
            trashId = record.trashId,
            originalPath = record.originalPath,
            deletedAt = record.deletedAt,
            title = record.originalPath.displayName(),
            excerpt = "",
        )
    }

    suspend fun listTrashedNotes(): List<TrashedNote> = withContext(Dispatchers.IO) {
        val ops = VaultFileOps(File(vault.rootPath))
        val parser = MarkdownParser()
        ops.trashed().map { record ->
            val metadata = runCatching { readMarkdown(record.file) }
                .mapCatching { read -> parser.parse(read, record.originalPath.displayName()) }
                .getOrNull()
            TrashedNote(
                trashId = record.trashId,
                originalPath = record.originalPath,
                deletedAt = record.deletedAt,
                title = metadata?.title.orEmpty().ifBlank { record.originalPath.displayName() },
                excerpt = metadata?.excerpt.orEmpty(),
            )
        }
    }

    suspend fun deleteTrashedNote(trashId: String) = withContext(Dispatchers.IO) {
        VaultFileOps(File(vault.rootPath)).deletePermanently(trashId)
    }

    suspend fun restoreNote(trashId: String): StoredMarkdown = withContext(Dispatchers.IO) {
        val ops = VaultFileOps(File(vault.rootPath))
        val record = ops.trashed().firstOrNull { it.trashId == trashId }
            ?: throw VaultFileException(VaultFileFailure.NOT_FOUND)
        val bytes = readMarkdownBytes(record.file)
        val file = ops.restore(record)
        storedMarkdown(vault, ops.rootDir, file, bytes)
    }

    suspend fun restoreNotes(trashIds: List<String>): Pair<List<StoredMarkdown>, TrashBulkOutcome> =
        withContext(Dispatchers.IO) {
            val ops = VaultFileOps(File(vault.rootPath))
            bulkOverTrash(ops, trashIds) { record ->
                val bytes = readMarkdownBytes(record.file)
                storedMarkdown(vault, ops.rootDir, ops.restore(record), bytes)
            }
        }

    suspend fun deleteTrashedNotes(trashIds: List<String>): TrashBulkOutcome =
        withContext(Dispatchers.IO) {
            val ops = VaultFileOps(File(vault.rootPath))
            bulkOverTrash(ops, trashIds) { record -> ops.deletePermanently(record) }.second
        }

    suspend fun writeDraft(id: NoteId, content: String, baseRevision: NoteRevision) = withContext(Dispatchers.IO) {
        val (_, ops) = currentVault(id.vaultId)
        markdownBytes(content)
        val diskContent = try {
            readMarkdown(ops.noteFile(id.path))
        } catch (failure: VaultFileException) {
            if (failure.failure != VaultFileFailure.NOT_FOUND) throw failure
            null
        } catch (_: NoteTooLargeException) {
            null
        }
        if (content == diskContent) {
            deleteDraftFiles(ops.rootDir, id.path)
            return@withContext
        }
        val revision = when (baseRevision) {
            is NoteRevision.Known -> baseRevision.sha256.also {
                require(REVISION_PATTERN.matches(it)) { "invalid draft base revision" }
            }
            NoteRevision.Unknown -> ""
        }
        val properties = Properties().apply {
            setProperty("version", "1")
            setProperty("content", content)
            setProperty("baseRevision", revision)
        }
        val output = ByteArrayOutputStream()
        output.writer(Charsets.UTF_8).use { properties.store(it, null) }
        val bytes = output.toByteArray()
        if (bytes.size > MAX_DRAFT_BYTES) throw NoteTooLargeException("draft", MAX_DRAFT_BYTES)
        val file = draftFile(ops.rootDir, id.path)
        ensureDirectory(checkNotNull(file.parentFile))
        writeBytesAtomically(file, bytes)
    }

    suspend fun readDraft(id: NoteId): StoredDraft? = withContext(Dispatchers.IO) {
        val (_, ops) = currentVault(id.vaultId)
        val file = draftFile(ops.rootDir, id.path)
        if (!file.exists()) {
            val legacy = legacyDraftFile(ops.rootDir, id.path)
            return@withContext if (legacy.exists()) StoredDraft(readMarkdown(legacy), NoteRevision.Unknown) else null
        }
        val properties = Properties()
        try {
            readBytesBounded(file, MAX_DRAFT_BYTES).inputStream().reader(Charsets.UTF_8).use { properties.load(it) }
        } catch (failure: IllegalArgumentException) {
            throw IOException("invalid draft record", failure)
        }
        if (properties.getProperty("version") != "1") throw IOException("unsupported draft version")
        val content = properties.getProperty("content") ?: throw IOException("missing draft content")
        markdownBytes(content)
        val rawRevision = properties.getProperty("baseRevision") ?: throw IOException("missing draft base revision")
        val revision = when {
            rawRevision.isEmpty() -> NoteRevision.Unknown
            REVISION_PATTERN.matches(rawRevision) -> NoteRevision.Known(rawRevision)
            else -> throw IOException("invalid draft base revision")
        }
        StoredDraft(content, revision)
    }

    suspend fun deleteDraft(id: NoteId) = withContext(Dispatchers.IO) {
        val (_, ops) = currentVault(id.vaultId)
        deleteDraftFiles(ops.rootDir, id.path)
    }

    private fun backupConflictBytes(root: File, path: NotePath, bytes: ByteArray): File {
        try {
            val mirroredNote = File(File(root, CONFLICT_DIRECTORY), path.relativePath)
            val directory = checkNotNull(mirroredNote.parentFile)
            ensureDirectory(directory)
            val timestamp = LocalDateTime.now().format(CONFLICT_TIMESTAMP)
            val file = generateSequence(1) { it + 1 }
                .map { copy -> File(directory, conflictBackupName(mirroredNote.nameWithoutExtension, timestamp, copy)) }
                .first { !it.exists() }
            writeBytesAtomically(file, bytes)
            return file
        } catch (failure: IOException) {
            throw NoteBackupFailedException(failure)
        }
    }

    private fun conflictBackupName(noteName: String, timestamp: String, copy: Int): String {
        val suffix = if (copy == 1) " (conflicted copy $timestamp).md" else " (conflicted copy $timestamp) $copy.md"
        val stemBudget = MAX_FILENAME_BYTES - suffix.toByteArray(Charsets.UTF_8).size
        return noteName.takeUtf8Bytes(stemBudget).trimEnd() + suffix
    }

    private fun String.takeUtf8Bytes(maxBytes: Int): String {
        var bytes = 0
        var end = 0
        while (end < length) {
            val codePoint = codePointAt(end)
            val size = when {
                codePoint < 0x80 -> 1
                codePoint < 0x800 -> 2
                codePoint < 0x10000 -> 3
                else -> 4
            }
            if (bytes + size > maxBytes) break
            bytes += size
            end += Character.charCount(codePoint)
        }
        return substring(0, end)
    }

    private fun currentVault(expected: VaultId): Pair<Vault, VaultFileOps> {
        if (vault.id != expected) throw VaultFileException(VaultFileFailure.NOT_FOUND)
        return vault to VaultFileOps(File(vault.rootPath))
    }

    private fun storedMarkdown(vault: Vault, root: File, file: File, bytes: ByteArray): StoredMarkdown =
        StoredMarkdown(
            id = NoteId(vault.id, NotePath(file.relativeTo(root).invariantSeparatorsPath)),
            content = bytes.toString(Charsets.UTF_8),
            modifiedAt = Instant.ofEpochMilli(file.lastModified()),
            root = root,
            file = file,
            revision = NoteRevision.of(bytes),
        )

    private fun deleteDraftFiles(root: File, path: NotePath) {
        for (file in listOf(legacyDraftFile(root, path), draftFile(root, path))) {
            if (file.exists() && !file.delete()) throw VaultFileException(VaultFileFailure.IO)
        }
    }

    private fun ensureDirectory(directory: File) {
        if (!directory.isDirectory && !directory.mkdirs()) throw VaultFileException(VaultFileFailure.IO)
    }

    private companion object {
        const val UNTITLED_FALLBACK = "Untitled"
        const val CONFLICT_DIRECTORY = ".looma/conflicts"
        const val MAX_FILENAME_BYTES = 255
        val CONFLICT_TIMESTAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HHmmss")
        val REVISION_PATTERN = Regex("[0-9a-f]{64}")
    }
}

internal const val MAX_DRAFT_BYTES = MAX_MARKDOWN_BYTES * 2 + 1024

internal fun draftFile(root: File, path: NotePath): File = draftPath(root, path, ".properties")

internal fun legacyDraftFile(root: File, path: NotePath): File = draftPath(root, path, ".md")

private fun draftPath(root: File, path: NotePath, suffix: String): File {
    val digest = MessageDigest.getInstance("SHA-256").digest(path.relativePath.toByteArray(Charsets.UTF_8))
    val name = digest.joinToString("") { "%02x".format(it.toInt() and 0xFF) }.take(32)
    return File(File(root, ".looma/drafts"), "$name$suffix")
}

internal fun <T> bulkOverTrash(
    ops: VaultFileOps,
    trashIds: List<String>,
    action: (TrashRecord) -> T,
): Pair<List<T>, TrashBulkOutcome> {
    val records = ops.trashed().associateBy { it.trashId }
    val results = mutableListOf<T>()
    var failed = 0
    for (trashId in trashIds) {
        val record = records[trashId]
        if (record == null) {
            failed++
            continue
        }
        try {
            results += action(record)
        } catch (e: IOException) {
            failed++
        }
    }
    return results to TrashBulkOutcome(results.size, failed)
}

private fun NotePath.displayName(): String =
    relativePath.substringAfterLast('/').removeSuffix(".md").ifBlank { relativePath }

class NoteChangedExternallyException(val actualRevision: NoteRevision) : IOException("note changed outside the app")

class NoteBackupFailedException(cause: IOException) : IOException("could not back up note before overwrite", cause)
