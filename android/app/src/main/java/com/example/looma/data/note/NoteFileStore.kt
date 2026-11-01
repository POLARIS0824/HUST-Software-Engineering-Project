package com.example.looma.data.note

import android.util.AtomicFile
import com.example.looma.data.vault.VaultRepository
import com.example.looma.domain.model.FolderPath
import com.example.looma.domain.model.NoteId
import com.example.looma.domain.model.NotePath
import com.example.looma.domain.model.TrashedNote
import com.example.looma.domain.model.Vault
import com.example.looma.domain.model.VaultFolder
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.time.Instant
import java.util.Properties
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/** 一篇笔记在文件系统中的原始记录，仅用于 data 层 */
internal data class StoredMarkdown(
    val id: NoteId,
    val content: String,
    val modifiedAt: Instant,
    val root: File,
    val file: File,
)

internal data class NoteFileScan(
    val notes: List<StoredMarkdown>,
    val folders: List<VaultFolder>,
    val unreadableCount: Int,
)

/** 真实文件读写、目录遍历、路径校验和回收站，不处理 Markdown 语义 */
internal class NoteFileStore(
    private val vaultRepository: VaultRepository,
) {
    suspend fun scanNotes(): NoteFileScan = withContext(Dispatchers.IO) {
        val vault = vaultRepository.requireCurrentVault()
        val root = rootDir(vault)
        val items = mutableListOf<StoredMarkdown>()
        val folders = mutableListOf<VaultFolder>()
        var unreadable = 0

        for (file in root.walkTopDown().maxDepth(MAX_DEPTH).onEnter { directory ->
            val inside = runCatching { isInside(root, directory) }.getOrDefault(false)
            inside && (directory == root || !directory.name.startsWith('.'))
        }) {
            coroutineContext.ensureActive()
            if (file.isDirectory) {
                val inside = runCatching { isInside(root, file) }.getOrDefault(false)
                if (file != root && inside && !file.name.startsWith('.')) {
                    folders += VaultFolder(
                        vaultId = vault.id,
                        path = FolderPath(file.relativeTo(root).invariantSeparatorsPath),
                        name = file.name,
                    )
                }
                continue
            }
            if (!file.isFile || !file.extension.equals("md", ignoreCase = true)) continue
            try {
                if (!isInside(root, file)) throw IOException("文件超出 Vault")
                items += storedMarkdown(vault, root, file, readMarkdown(file))
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                unreadable++
            }
        }
        NoteFileScan(
            notes = items,
            folders = folders.sortedBy { it.path.relativePath.lowercase() },
            unreadableCount = unreadable,
        )
    }

    suspend fun readNote(id: NoteId): StoredMarkdown = withContext(Dispatchers.IO) {
        val (vault, file) = existingNote(id)
        val root = rootDir(vault)
        storedMarkdown(vault, root, file, readMarkdown(file))
    }

    suspend fun createNote(
        title: String = "未命名笔记",
        initialContent: String? = null,
    ): StoredMarkdown = withContext(Dispatchers.IO) {
        val vault = vaultRepository.requireCurrentVault()
        val root = rootDir(vault)
        val base = safeFilename(title)
        var chosen: File? = null
        for (i in 0..999) {
            coroutineContext.ensureActive()
            val name = if (i == 0) "$base.md" else "$base ($i).md"
            val candidate = File(root, name)
            if (!isInside(root, candidate)) throw SecurityException("无效笔记路径")
            if (candidate.createNewFile()) {
                chosen = candidate
                break
            }
        }
        val file = chosen ?: throw IOException("找不到可用的笔记文件名")
        val text = initialContent ?: "# ${title.trim().ifBlank { "未命名笔记" }}\n\n"
        try {
            writeMarkdown(file, text)
        } catch (e: Exception) {
            file.delete()
            throw e
        }
        storedMarkdown(vault, root, file, text)
    }

    suspend fun saveNote(
        id: NoteId,
        content: String,
        expectedModifiedAt: Instant? = null,
    ): StoredMarkdown = withContext(Dispatchers.IO) {
        val (vault, file) = existingNote(id)
        if (
            expectedModifiedAt != null &&
            Instant.ofEpochMilli(file.lastModified()) != expectedModifiedAt
        ) {
            throw NoteChangedExternallyException()
        }
        writeMarkdown(file, content)
        storedMarkdown(vault, rootDir(vault), file, content)
    }

    /** 重命名路径，不改变正文或 Markdown H1 */
    suspend fun renameNote(id: NoteId, newFilename: String): StoredMarkdown =
        withContext(Dispatchers.IO) {
            val (vault, oldFile) = existingNote(id)
            val root = rootDir(vault)
            val dest = File(oldFile.parentFile, "${safeFilename(newFilename)}.md")
            if (!isInside(root, dest)) throw SecurityException("无效目标路径")
            if (oldFile.canonicalFile != dest.canonicalFile) {
                if (dest.exists()) throw IOException("同名笔记已经存在")
                if (!oldFile.renameTo(dest)) throw IOException("重命名笔记失败")
            }
            storedMarkdown(vault, root, dest, readMarkdown(dest))
        }

    /** 先写元数据，再移动文件到 Vault/.looma/trash */
    suspend fun deleteNote(id: NoteId): TrashedNote = withContext(Dispatchers.IO) {
        val (vault, file) = existingNote(id)
        val root = rootDir(vault)
        val trashDir = File(root, ".looma/trash")
        ensureInternalDirectory(root, trashDir)
        val trashId = UUID.randomUUID().toString()
        val trashFile = File(trashDir, "$trashId.md")
        val metaFile = File(trashDir, "$trashId.properties")
        val deletedAt = Instant.now()
        val metadata = Properties().apply {
            setProperty("originalPath", id.path.relativePath)
            setProperty("deletedAt", deletedAt.toEpochMilli().toString())
        }
        writeProperties(metaFile, metadata)
        try {
            if (!file.renameTo(trashFile)) throw IOException("移动到回收站失败")
        } catch (e: Exception) {
            metaFile.delete()
            throw e
        }
        TrashedNote(trashId, id.path, deletedAt)
    }

    suspend fun listTrashedNotes(): List<TrashedNote> = withContext(Dispatchers.IO) {
        val vault = vaultRepository.requireCurrentVault()
        val root = rootDir(vault)
        val trashDir = File(root, ".looma/trash")
        if (!trashDir.isDirectory) return@withContext emptyList()
        if (!isInside(root, trashDir)) throw SecurityException("无效回收站目录")
        trashDir.listFiles().orEmpty().asSequence()
            .filter { it.extension == "properties" && isInside(root, it) }
            .mapNotNull { meta ->
                runCatching {
                    val id = meta.nameWithoutExtension
                    val file = File(trashDir, "$id.md")
                    if (!UUID_PATTERN.matches(id) || !file.isFile || !isInside(root, file)) {
                        return@runCatching null
                    }
                    val p = readProperties(meta)
                    val path = NotePath(p.getProperty("originalPath") ?: return@runCatching null)
                    resolveNoteFile(root, path)
                    TrashedNote(
                        trashId = id,
                        originalPath = path,
                        deletedAt = Instant.ofEpochMilli(p.getProperty("deletedAt").toLong()),
                    )
                }.getOrNull()
            }
            .sortedByDescending { it.deletedAt }
            .toList()
    }

    /** 原路径有同名文件时拒绝恢复 */
    suspend fun restoreNote(trashId: String): StoredMarkdown = withContext(Dispatchers.IO) {
        require(UUID_PATTERN.matches(trashId)) { "无效回收站 ID" }
        val vault = vaultRepository.requireCurrentVault()
        val root = rootDir(vault)
        val trashDir = File(root, ".looma/trash")
        if (!isInside(root, trashDir)) throw SecurityException("无效回收站目录")
        val metaFile = File(trashDir, "$trashId.properties")
        val trashFile = File(trashDir, "$trashId.md")
        if (!metaFile.isFile || !trashFile.isFile) {
            throw FileNotFoundException("回收站文件不存在")
        }
        if (!isInside(root, metaFile) || !isInside(root, trashFile)) {
            throw SecurityException("回收站文件路径异常")
        }
        val path = NotePath(
            readProperties(metaFile).getProperty("originalPath")
                ?: throw IOException("回收站记录缺少原路径")
        )
        val destination = resolveNoteFile(root, path)
        if (destination.exists()) throw IOException("原位置存在同名文件，请先处理冲突")
        destination.parentFile?.let { parent ->
            if (!parent.isDirectory && !parent.mkdirs()) throw IOException("无法创建笔记目录")
        }
        if (!trashFile.renameTo(destination)) throw IOException("恢复笔记失败")
        metaFile.delete()
        storedMarkdown(vault, root, destination, readMarkdown(destination))
    }

    /** Markdown 解析器只提供引用字符串，Store 负责验证图片文件路径 */
    fun resolveCover(note: StoredMarkdown, reference: String?): String? {
        val src = reference?.substringBefore('|')?.trim()?.replace("%20", " ") ?: return null
        if (src.isBlank() || src.startsWith('/') || "://" in src || '\\' in src) return null
        val allowed = setOf("png", "jpg", "jpeg", "webp")
        return listOf(File(note.file.parentFile, src), File(note.root, src))
            .firstNotNullOfOrNull { candidate ->
                runCatching {
                    candidate.canonicalFile.takeIf {
                        isInside(note.root, it) && it.isFile && it.extension.lowercase() in allowed
                    }?.absolutePath
                }.getOrNull()
            }
    }

    private suspend fun existingNote(id: NoteId): Pair<Vault, File> {
        val vault = vaultRepository.requireCurrentVault()
        require(id.vaultId == vault.id) { "笔记不属于当前 Vault" }
        val file = resolveNoteFile(rootDir(vault), id.path)
        if (!file.isFile) {
            throw FileNotFoundException("笔记文件不存在：${id.path.relativePath}")
        }
        return vault to file
    }

    private fun storedMarkdown(vault: Vault, root: File, file: File, text: String): StoredMarkdown =
        StoredMarkdown(
            id = NoteId(vault.id, NotePath(file.relativeTo(root).invariantSeparatorsPath)),
            content = text,
            modifiedAt = Instant.ofEpochMilli(file.lastModified()),
            root = root,
            file = file,
        )

    private fun rootDir(vault: Vault): File = File(vault.rootPath).canonicalFile.also {
        if (!it.isDirectory || !it.canRead()) throw IOException("当前 Vault 不可访问")
    }

    private fun resolveNoteFile(root: File, path: NotePath): File {
        val raw = path.relativePath
        require(raw.isNotBlank() && !raw.startsWith('/') && '\\' !in raw) {
            "无效笔记路径"
        }
        require(raw.split('/').none { it.isBlank() || it == "." || it == ".." }) {
            "无效笔记路径"
        }
        require(raw.endsWith(".md", ignoreCase = true)) { "只支持 Markdown 笔记" }
        val file = File(root, raw).canonicalFile
        if (!isInside(root, file) || file == root) {
            throw SecurityException("笔记路径超出 Vault")
        }
        return file
    }

    private fun isInside(root: File, candidate: File): Boolean =
        candidate.canonicalFile.toPath().startsWith(root.canonicalFile.toPath())

    private fun ensureInternalDirectory(root: File, directory: File) {
        if (!isInside(root, directory)) throw SecurityException("目录超出 Vault")
        if (!directory.isDirectory && !directory.mkdirs()) {
            throw IOException("无法创建回收站目录")
        }
    }

    private fun readMarkdown(file: File): String {
        if (file.length() > MAX_FILE_BYTES) throw IOException("笔记文件过大：${file.name}")
        return AtomicFile(file).openRead().bufferedReader(Charsets.UTF_8).use { it.readText() }
            .removePrefix("\uFEFF")
    }

    private fun writeMarkdown(file: File, text: String) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_FILE_BYTES) { "单篇笔记暂时不能超过 2 MiB" }
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

    private fun writeProperties(file: File, data: Properties) {
        val atomic = AtomicFile(file)
        val stream = atomic.startWrite()
        try {
            data.store(stream, "Looma trash metadata")
            atomic.finishWrite(stream)
        } catch (e: Exception) {
            atomic.failWrite(stream)
            throw e
        }
    }

    private fun readProperties(file: File): Properties = Properties().apply {
        AtomicFile(file).openRead().use { load(it) }
    }

    private fun safeFilename(title: String): String = title
        .trim().removeSuffix(".md")
        .replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_")
        .trim(' ', '.')
        .take(72)
        .ifBlank { "未命名笔记" }

    private companion object {
        const val MAX_DEPTH = 40
        const val MAX_FILE_BYTES = 2L * 1024 * 1024
        val UUID_PATTERN = Regex("[0-9a-fA-F-]{36}")
    }
}

class NoteChangedExternallyException : IOException("笔记已被其他程序修改，请重新加载后再保存")
