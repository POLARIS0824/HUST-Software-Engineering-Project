package com.example.looma.data.note

import android.util.AtomicFile
import com.example.looma.data.vault.VaultRepository
import com.example.looma.domain.markdown.ImageContext
import com.example.looma.domain.model.FolderPath
import com.example.looma.domain.model.NoteId
import com.example.looma.domain.model.NotePath
import com.example.looma.domain.model.TrashedNote
import com.example.looma.domain.model.Vault
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 一篇笔记在文件系统中的原始记录，仅用于 data 层 */
internal data class StoredMarkdown(
    val id: NoteId,
    val content: String,
    val modifiedAt: Instant,
    val root: File,
    val file: File,
)

/** 笔记在磁盘上的物理事实，供“文件属性”使用 */
internal data class StoredNoteStats(
    val path: NotePath,
    val sizeBytes: Long,
    val modifiedAt: Instant,
)

/**
 * Markdown 正文的读写与单篇操作。
 *
 * 文件级操作（改名、移动、回收站、目录创建）都委托给 [VaultFileOps]，
 * 这一层只负责把文件变成 [StoredMarkdown] 以及原子写盘。
 * 全库遍历不在这里：那条路径要经过索引，见 `VaultIndexer`。
 */
internal class NoteFileStore(
    private val vaultRepository: VaultRepository,
) {
    suspend fun readNote(id: NoteId): StoredMarkdown = withContext(Dispatchers.IO) {
        val (vault, ops) = currentVault(id.vaultId)
        val file = ops.noteFile(id.path)
        storedMarkdown(vault, ops.rootDir, file, readMarkdown(file))
    }

    /** 解析正文图片只需要两个路径，不必把正文再读一遍 */
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

    /** 只做 stat：正文缓存的命中判断靠它，不能顺手把整篇读出来 */
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
        initialContent: String? = null,
        folder: FolderPath = FolderPath.Root,
    ): StoredMarkdown = withContext(Dispatchers.IO) {
        val vault = vaultRepository.requireCurrentVault()
        val ops = VaultFileOps(File(vault.rootPath))
        val file = ops.newNoteFile(folder, title, fallbackName = UNTITLED_FALLBACK)
        val text = initialContent ?: "# ${title.trim().ifBlank { UNTITLED_FALLBACK }}\n\n"
        try {
            writeMarkdown(file, text)
        } catch (e: Exception) {
            file.delete()
            throw e
        }
        storedMarkdown(vault, ops.rootDir, file, text)
    }

    suspend fun saveNote(
        id: NoteId,
        content: String,
        expectedModifiedAt: Instant? = null,
    ): StoredMarkdown = withContext(Dispatchers.IO) {
        val (vault, ops) = currentVault(id.vaultId)
        val file = ops.noteFile(id.path)
        if (
            expectedModifiedAt != null &&
            Instant.ofEpochMilli(file.lastModified()) != expectedModifiedAt
        ) {
            throw NoteChangedExternallyException()
        }
        writeMarkdown(file, content)
        storedMarkdown(vault, ops.rootDir, file, content)
    }

    /** 改名只改文件名，正文与 Markdown H1 保持原样 */
    suspend fun renameNote(id: NoteId, newFilename: String): StoredMarkdown =
        withContext(Dispatchers.IO) {
            val (vault, ops) = currentVault(id.vaultId)
            val file = ops.rename(ops.noteFile(id.path), newFilename)
            storedMarkdown(vault, ops.rootDir, file, readMarkdown(file))
        }

    suspend fun moveNote(id: NoteId, target: FolderPath): StoredMarkdown =
        withContext(Dispatchers.IO) {
            val (vault, ops) = currentVault(id.vaultId)
            val file = ops.move(ops.noteFile(id.path), target)
            storedMarkdown(vault, ops.rootDir, file, readMarkdown(file))
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
        val vault = vaultRepository.requireCurrentVault()
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
        val vault = vaultRepository.requireCurrentVault()
        VaultFileOps(File(vault.rootPath)).deletePermanently(trashId)
    }

    /** 原路径被占用时拒绝恢复，避免覆盖已有文件 */
    suspend fun restoreNote(trashId: String): StoredMarkdown = withContext(Dispatchers.IO) {
        val vault = vaultRepository.requireCurrentVault()
        val ops = VaultFileOps(File(vault.rootPath))
        val file = ops.restore(trashId)
        storedMarkdown(vault, ops.rootDir, file, readMarkdown(file))
    }

    /** Markdown 解析器只提供引用字符串，Store 负责验证图片文件路径 */
    fun resolveCover(note: StoredMarkdown, reference: String?): String? =
        resolveCover(note.root, note.file, reference)

    /**
     * 把还没落盘的正文写进草稿区，进程被杀后还能捞回来。
     *
     * 草稿不参与扫描（`.looma` 是点目录），文件名是路径的哈希，
     * 所以改名或换目录后旧草稿不会串到别的笔记上。
     */
    suspend fun writeDraft(id: NoteId, content: String) = withContext(Dispatchers.IO) {
        val (_, ops) = currentVault(id.vaultId)
        val file = draftFile(ops.rootDir, id.path)
        file.parentFile?.mkdirs()
        writeMarkdown(file, content)
    }

    /** 读到比正文更新的草稿就返回它；没有草稿或草稿更旧时返回 null */
    suspend fun readDraft(id: NoteId, noteModifiedAt: Instant): String? = withContext(Dispatchers.IO) {
        val (_, ops) = currentVault(id.vaultId)
        val file = draftFile(ops.rootDir, id.path)
        if (!file.isFile) return@withContext null
        if (Instant.ofEpochMilli(file.lastModified()) <= noteModifiedAt) return@withContext null
        runCatching { readMarkdown(file) }.getOrNull()
    }

    suspend fun deleteDraft(id: NoteId) = withContext(Dispatchers.IO) {
        val (_, ops) = currentVault(id.vaultId)
        draftFile(ops.rootDir, id.path).delete()
    }

    /** 覆盖保存之前把磁盘上的那一份留档，返回备份文件路径 */
    suspend fun backupConflict(id: NoteId, content: String): File = withContext(Dispatchers.IO) {
        val (_, ops) = currentVault(id.vaultId)
        val directory = File(ops.rootDir, CONFLICT_DIRECTORY)
        if (!directory.isDirectory && !directory.mkdirs()) {
            throw VaultFileException(VaultFileFailure.IO)
        }
        val base = id.path.relativePath.substringAfterLast('/').removeSuffix(MARKDOWN_SUFFIX)
        val file = File(directory, "$base-${Instant.now().toEpochMilli()}$MARKDOWN_SUFFIX")
        writeMarkdown(file, content)
        file
    }

    private fun draftFile(root: File, path: NotePath): File {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(path.relativePath.toByteArray(Charsets.UTF_8))
        val name = digest.joinToString("") { "%02x".format(it.toInt() and 0xFF) }.take(32)
        return File(File(root, DRAFT_DIRECTORY), "$name$MARKDOWN_SUFFIX")
    }

    private suspend fun currentVault(expected: com.example.looma.domain.model.VaultId): Pair<Vault, VaultFileOps> {
        val vault = vaultRepository.requireCurrentVault()
        if (vault.id != expected) throw VaultFileException(VaultFileFailure.NOT_FOUND)
        return vault to VaultFileOps(File(vault.rootPath))
    }

    private fun storedMarkdown(vault: Vault, root: File, file: File, text: String): StoredMarkdown =
        StoredMarkdown(
            id = NoteId(vault.id, NotePath(file.relativeTo(root).invariantSeparatorsPath)),
            content = text,
            modifiedAt = Instant.ofEpochMilli(file.lastModified()),
            root = root,
            file = file,
        )

    private fun writeMarkdown(file: File, text: String) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_MARKDOWN_BYTES) { "a note cannot exceed 2 MiB" }
        val atomic = AtomicFile(file)
        val stream = atomic.startWrite()
        try {
            stream.write(bytes)
            atomic.finishWrite(stream)
        } catch (e: Exception) {
            atomic.failWrite(stream)
            throw e
        }
    }

    private companion object {
        const val UNTITLED_FALLBACK = "Untitled"
        const val MARKDOWN_SUFFIX = ".md"
        const val DRAFT_DIRECTORY = ".looma/drafts"
        const val CONFLICT_DIRECTORY = ".looma/conflicts"
    }
}

/** `文件夹/名字.md` → `名字`，用于没有可读标题时的兜底 */
private fun NotePath.displayName(): String =
    relativePath.substringAfterLast('/').removeSuffix(".md").ifBlank { relativePath }

class NoteChangedExternallyException : IOException("note changed outside the app")
