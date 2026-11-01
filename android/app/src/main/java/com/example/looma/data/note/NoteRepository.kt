package com.example.looma.data.note

import android.util.AtomicFile
import com.example.looma.data.vault.VaultRepository
import com.example.looma.domain.model.Note
import com.example.looma.domain.model.NoteId
import com.example.looma.domain.model.NotePath
import com.example.looma.domain.model.NoteSummary
import com.example.looma.domain.model.Vault
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

/** 只保存 Markdown 文件，不把文件正文放进数据库 */
class NoteRepository(private val vaultRepository: VaultRepository) {

    data class ChecklistItem(val text: String, val checked: Boolean)

    data class ListItem(
        val summary: NoteSummary,
        val checklist: List<ChecklistItem> = emptyList(),
        val coverPath: String? = null,
        val firstWikiLink: String? = null,
        val searchText: String = "",
    )

    data class ListResult(
        val items: List<ListItem>,
        val unreadableCount: Int = 0,
    )

    data class TrashedNote(
        val trashId: String,
        val originalPath: NotePath,
        val deletedAt: Instant,
    )

    /** 每次主动扫描 Vault，能读到 Obsidian 等外部程序的最新修改 */
    suspend fun listNotes(): ListResult = withContext(Dispatchers.IO) {
        val vault = vaultRepository.requireCurrentVault()
        val root = rootDir(vault)
        val items = mutableListOf<ListItem>()
        var unreadable = 0

        for (file in root.walkTopDown().maxDepth(MAX_DEPTH).onEnter { directory ->
            val inside = runCatching { isInside(root, directory) }.getOrDefault(false)
            inside && (directory == root || !directory.name.startsWith('.'))
        }) {
            coroutineContext.ensureActive()
            if (!file.isFile || !file.extension.equals("md", ignoreCase = true)) continue
            try {
                if (!isInside(root, file)) throw IOException("文件超出 Vault")
                val text = readMarkdown(file)
                val path = NotePath(file.relativeTo(root).invariantSeparatorsPath)
                items += buildListItem(vault, root, file, path, text)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                unreadable++
            }
        }
        ListResult(items.sortedByDescending { it.summary.modifiedAt }, unreadable)
    }

    suspend fun readNote(id: NoteId): Note = withContext(Dispatchers.IO) {
        val (vault, file) = existingNote(id)
        val text = readMarkdown(file)
        parseNote(vault, file, id.path, text)
    }

    /** 新笔记先以唯一文件名创建，不会覆盖已有 Markdown 文件 */
    suspend fun createNote(
        title: String = "未命名笔记",
        initialContent: String? = null,
    ): Note = withContext(Dispatchers.IO) {
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
        val path = NotePath(file.relativeTo(root).invariantSeparatorsPath)
        parseNote(vault, file, path, text)
    }

    /** 可传入打开编辑器时的 modifiedAt，发现外部修改则拒绝覆盖 */
    suspend fun saveNote(
        id: NoteId,
        content: String,
        expectedModifiedAt: Instant? = null,
    ): Note = withContext(Dispatchers.IO) {
        val (vault, file) = existingNote(id)
        if (expectedModifiedAt != null &&
            Instant.ofEpochMilli(file.lastModified()) != expectedModifiedAt
        ) {
            throw NoteChangedExternallyException()
        }
        writeMarkdown(file, content)
        parseNote(vault, file, id.path, content)
    }

    /** 重命名文件路径，不修改 Markdown 正文或已有 H1 标题 */
    suspend fun renameNote(id: NoteId, newFilename: String): Note = withContext(Dispatchers.IO) {
        val (vault, oldFile) = existingNote(id)
        val root = rootDir(vault)
        val dest = File(oldFile.parentFile, "${safeFilename(newFilename)}.md")
        if (!isInside(root, dest)) throw SecurityException("无效目标路径")
        if (oldFile.canonicalFile != dest.canonicalFile) {
            if (dest.exists()) throw IOException("同名笔记已经存在")
            if (!oldFile.renameTo(dest)) throw IOException("重命名笔记失败")
        }
        val newPath = NotePath(dest.relativeTo(root).invariantSeparatorsPath)
        parseNote(vault, dest, newPath, readMarkdown(dest))
    }

    /** 删除使用 Vault 下的 .looma/trash 回收站，先写还原信息、后移动原文件 */
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
                    if (!UUID_PATTERN.matches(id) ||
                        !File(trashDir, "$id.md").isFile ||
                        !isInside(root, File(trashDir, "$id.md"))) {
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

    /** 原路径已被其他文件占用时拒绝恢复，绝不覆盖现有笔记 */
    suspend fun restoreNote(trashId: String): Note = withContext(Dispatchers.IO) {
        require(UUID_PATTERN.matches(trashId)) { "无效回收站 ID" }
        val vault = vaultRepository.requireCurrentVault()
        val root = rootDir(vault)
        val trashDir = File(root, ".looma/trash")
        if (!isInside(root, trashDir)) throw SecurityException("无效回收站目录")
        val metaFile = File(trashDir, "$trashId.properties")
        val trashFile = File(trashDir, "$trashId.md")
        if (!metaFile.isFile || !trashFile.isFile) throw FileNotFoundException("回收站文件不存在")
        if (!isInside(root, metaFile) || !isInside(root, trashFile)) {
            throw SecurityException("回收站文件路径异常")
        }
        val path = NotePath(readProperties(metaFile).getProperty("originalPath")
            ?: throw IOException("回收站记录缺少原路径"))
        val destination = resolveNoteFile(root, path)
        if (destination.exists()) throw IOException("原位置存在同名文件，请先处理冲突")
        destination.parentFile?.let { parent ->
            if (!parent.isDirectory && !parent.mkdirs()) throw IOException("无法创建笔记目录")
        }
        if (!trashFile.renameTo(destination)) throw IOException("恢复笔记失败")
        metaFile.delete() // 删除失败只会留下无效元信息，不会丢失已恢复文件
        parseNote(vault, destination, path, readMarkdown(destination))
    }

    private suspend fun existingNote(id: NoteId): Pair<Vault, File> {
        val vault = vaultRepository.requireCurrentVault()
        require(id.vaultId == vault.id) { "笔记不属于当前 Vault" }
        val file = resolveNoteFile(rootDir(vault), id.path)
        if (!file.isFile) throw FileNotFoundException("笔记文件不存在：${id.path.relativePath}")
        return vault to file
    }

    private fun rootDir(vault: Vault): File = File(vault.rootPath).canonicalFile.also {
        if (!it.isDirectory || !it.canRead()) throw IOException("当前 Vault 不可访问")
    }

    private fun resolveNoteFile(root: File, path: NotePath): File {
        val raw = path.relativePath
        require(raw.isNotBlank() && !raw.startsWith('/') && '\\' !in raw) { "无效笔记路径" }
        require(raw.split('/').none { it.isBlank() || it == "." || it == ".." }) { "无效笔记路径" }
        require(raw.endsWith(".md", ignoreCase = true)) { "只支持 Markdown 笔记" }
        val file = File(root, raw).canonicalFile
        if (!isInside(root, file) || file == root) throw SecurityException("笔记路径超出 Vault")
        return file
    }

    private fun isInside(root: File, candidate: File): Boolean =
        candidate.canonicalFile.toPath().startsWith(root.canonicalFile.toPath())

    private fun ensureInternalDirectory(root: File, directory: File) {
        if (!isInside(root, directory)) throw SecurityException("目录超出 Vault")
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("无法创建回收站目录")
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

    private fun parseNote(vault: Vault, file: File, path: NotePath, text: String): Note {
        val body = bodyLines(text)
        return Note(
            id = NoteId(vault.id, path),
            title = extractTitle(body, file.nameWithoutExtension),
            content = text,
            modifiedAt = Instant.ofEpochMilli(file.lastModified()),
            tags = TAG.findAll(text).map { it.groupValues[1] }.toSet(),
        )
    }

    private fun buildListItem(
        vault: Vault,
        root: File,
        file: File,
        path: NotePath,
        text: String,
    ): ListItem {
        val note = parseNote(vault, file, path, text)
        val body = bodyLines(text)
        val excerpt = body.asSequence()
            .filterNot { it.matches(HEADING) || it.matches(CHECKLIST) }
            .filterNot { it.trim().startsWith("```") || it.trim().startsWith("![[") || it.trim().startsWith("![") }
            .map { it.trim().replace(Regex("^[-*]\\s+"), "") }
            .filter { it.isNotBlank() }
            .take(15)
            .joinToString("\n")
            .take(360)
        val checklist = body.mapNotNull { line ->
            val match = CHECKLIST.matchEntire(line) ?: return@mapNotNull null
            ChecklistItem(match.groupValues[2], match.groupValues[1].equals("x", true))
        }.take(5)
        val link = WIKI_LINK.find(text)?.groupValues?.get(1)
            ?.substringBefore('|')?.substringBefore('#')?.take(40)
        return ListItem(
            summary = NoteSummary(
                id = note.id,
                title = note.title,
                excerpt = excerpt,
                modifiedAt = note.modifiedAt,
                tags = note.tags,
            ),
            checklist = checklist,
            coverPath = resolveCover(root, file.parentFile, text),
            firstWikiLink = link,
            searchText = text,
        )
    }

    private fun bodyLines(text: String): List<String> {
        val lines = text.lines()
        if (lines.firstOrNull()?.trim() != "---") return lines
        val end = lines.drop(1).indexOfFirst { it.trim() == "---" }
        return if (end >= 0) lines.drop(end + 2) else lines
    }

    private fun extractTitle(lines: List<String>, fallback: String): String =
        lines.firstOrNull { it.startsWith("# ") }
            ?.removePrefix("# ")?.trim()?.takeIf { it.isNotBlank() }
            ?: fallback

    private fun resolveCover(root: File, parent: File, text: String): String? {
        val raw = MARKDOWN_IMAGE.find(text)?.groupValues?.get(1)
            ?: WIKI_IMAGE.find(text)?.groupValues?.get(1)
            ?: return null
        val src = raw.substringBefore('|').trim().replace("%20", " ")
        if (src.isBlank() || src.startsWith('/') || "://" in src || '\\' in src) return null
        val allowed = setOf("png", "jpg", "jpeg", "webp")
        return listOf(File(parent, src), File(root, src)).firstNotNullOfOrNull { candidate ->
            runCatching {
                candidate.canonicalFile.takeIf {
                    isInside(root, it) && it.isFile && it.extension.lowercase() in allowed
                }?.absolutePath
            }.getOrNull()
        }
    }

    private companion object {
        const val MAX_DEPTH = 40
        const val MAX_FILE_BYTES = 2L * 1024 * 1024
        val UUID_PATTERN = Regex("[0-9a-fA-F-]{36}")
        val HEADING = Regex("^#{1,6}\\s+.+")
        val CHECKLIST = Regex("^\\s*[-*+]\\s+\\[([ xX])\\]\\s+(.+)$")
        val TAG = Regex("(?<![\\p{L}\\p{N}])#([\\p{L}\\p{N}_/-]+)")
        val MARKDOWN_IMAGE = Regex("!\\[[^]]*]\\(([^)]+)\\)")
        val WIKI_IMAGE = Regex("!\\[\\[([^]]+)]]")
        val WIKI_LINK = Regex("(?<!!)\\[\\[([^]]+)]]")
    }
}

class NoteChangedExternallyException : IOException("笔记已被其他程序修改，请重新加载后再保存")
