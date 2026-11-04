package com.example.looma.data.note

import com.example.looma.domain.model.FolderPath
import com.example.looma.domain.model.NotePath
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.time.Instant
import java.util.Properties
import java.util.UUID

/** 文件操作失败的原因，界面据此挑文案，不用把中文塞进 data 层 */
enum class VaultFileFailure {
    INVALID_PATH,
    INVALID_NAME,
    NAME_TAKEN,
    NOT_FOUND,
    ALREADY_EXISTS,
    RESTORE_CONFLICT,
    IO,
}

class VaultFileException(
    val failure: VaultFileFailure,
    cause: Throwable? = null,
) : IOException(failure.name, cause)

/** 回收站里的一条记录：正文文件、原路径、删除时间 */
internal data class TrashRecord(
    val trashId: String,
    val originalPath: NotePath,
    val deletedAt: Instant,
    val file: File,
    val metaFile: File,
)

/**
 * 一个 Vault 目录内的文件操作：校验路径、新建目录、重命名、移动、进回收站、恢复。
 *
 * 只依赖 java.io，不读 Markdown 正文，所以能在纯 JVM 单测里跑完整闭环。
 * 所有对外方法都先确认目标仍在 Vault 内，避免 `..` 或符号链接把操作带出笔记库。
 */
internal class VaultFileOps(root: File) {
    private val root: File = root.canonicalFile

    /** Vault 根目录，仅供同包内扫描与 Markdown 读写复用 */
    val rootDir: File get() = root

    init {
        if (!this.root.isDirectory) throw VaultFileException(VaultFileFailure.NOT_FOUND)
    }

    fun noteFile(path: NotePath): File {
        val raw = path.relativePath
        if (raw.isBlank() || raw.startsWith('/') || '\\' in raw) {
            throw VaultFileException(VaultFileFailure.INVALID_PATH)
        }
        if (raw.split('/').any { it.isBlank() || it == "." || it == ".." }) {
            throw VaultFileException(VaultFileFailure.INVALID_PATH)
        }
        if (!raw.endsWith(MARKDOWN_SUFFIX, ignoreCase = true)) {
            throw VaultFileException(VaultFileFailure.INVALID_PATH)
        }
        val file = File(root, raw).canonicalFile
        if (file == root || !isInside(file)) throw VaultFileException(VaultFileFailure.INVALID_PATH)
        if (!file.isFile) throw VaultFileException(VaultFileFailure.NOT_FOUND)
        return file
    }

    fun folderDirectory(path: FolderPath): File {
        val relative = path.relativePath
        if (relative.isEmpty()) return root
        if (relative.startsWith('/') || '\\' in relative) {
            throw VaultFileException(VaultFileFailure.INVALID_PATH)
        }
        if (relative.split('/').any { it.isBlank() || it == "." || it == ".." || it.startsWith('.') }) {
            throw VaultFileException(VaultFileFailure.INVALID_PATH)
        }
        val directory = File(root, relative).canonicalFile
        if (directory == root || !isInside(directory)) {
            throw VaultFileException(VaultFileFailure.INVALID_PATH)
        }
        return directory
    }

    /** 建立一层目录；同名目录已存在时报错，让界面给出明确反馈 */
    fun createFolder(parent: FolderPath, name: String): File {
        val parentDir = folderDirectory(parent)
        if (!parentDir.isDirectory) throw VaultFileException(VaultFileFailure.NOT_FOUND)
        val normalized = validateName(name, folder = true)
        val candidate = File(parentDir, normalized).canonicalFile
        if (!isInside(candidate) || candidate == root) {
            throw VaultFileException(VaultFileFailure.INVALID_PATH)
        }
        if (candidate.exists()) throw VaultFileException(VaultFileFailure.NAME_TAKEN)
        if (!candidate.mkdir()) throw VaultFileException(VaultFileFailure.IO)
        return candidate
    }

    /** 文件夹改名；新名字已被同级占用时报错，返回改名后的相对路径 */
    fun renameFolder(path: FolderPath, newName: String): FolderPath {
        val directory = folderDirectory(path)
        if (directory == root) throw VaultFileException(VaultFileFailure.INVALID_PATH)
        if (!directory.isDirectory) throw VaultFileException(VaultFileFailure.NOT_FOUND)
        val parent = directory.parentFile ?: throw VaultFileException(VaultFileFailure.INVALID_PATH)
        // 与笔记名共用同一套清洗规则：去空白、去首尾点、拒绝非法字符
        val destination = File(parent, validateName(newName, folder = true)).canonicalFile
        if (!isInside(destination) || destination == root) {
            throw VaultFileException(VaultFileFailure.INVALID_PATH)
        }
        if (destination == directory) return path
        if (destination.exists()) throw VaultFileException(VaultFileFailure.NAME_TAKEN)
        if (!directory.renameTo(destination)) throw VaultFileException(VaultFileFailure.IO)
        return FolderPath(destination.relativeTo(root).invariantSeparatorsPath)
    }

    /**
     * 把文件夹整体移到另一个目录下，笔记文件跟着走，不需要逐个搬运。
     *
     * 目标在文件夹自身内部时拒绝，否则目录会被搬进自己、路径瞬间失效；
     * 目标已有同名目录时同样拒绝，避免和别人的文件夹悄悄合并。
     */
    fun moveFolder(path: FolderPath, destination: FolderPath): FolderPath {
        val source = folderDirectory(path)
        if (source == root) throw VaultFileException(VaultFileFailure.INVALID_PATH)
        if (!source.isDirectory) throw VaultFileException(VaultFileFailure.NOT_FOUND)
        val target = folderDirectory(destination)
        if (!target.isDirectory) throw VaultFileException(VaultFileFailure.NOT_FOUND)
        if (target == source.parentFile) return path
        val sourcePath = source.toPath()
        // startsWith 同时覆盖“移到自己”和“移到自己的子孙目录”
        if (target.toPath().startsWith(sourcePath)) {
            throw VaultFileException(VaultFileFailure.INVALID_PATH)
        }
        val moved = File(target, source.name).canonicalFile
        if (!isInside(moved) || moved == root) {
            throw VaultFileException(VaultFileFailure.INVALID_PATH)
        }
        if (moved.exists()) throw VaultFileException(VaultFileFailure.NAME_TAKEN)
        if (!source.renameTo(moved)) throw VaultFileException(VaultFileFailure.IO)
        return FolderPath(moved.relativeTo(root).invariantSeparatorsPath)
    }

    /**
     * 删除一个文件夹：先把其中每一篇 Markdown 笔记移进回收站（保留原相对路径），
     * 再自底向上删掉已经空了的目录。
     *
     * 点开头的目录（`.looma`）整棵跳过：回收站元数据不该被当成笔记，也不该被删。
     * 文件夹里还有非 Markdown 文件时，对应目录不算“空了”，会留在原处。
     * 任何一步搬运失败都直接抛出，后续笔记与目录保持原样。
     *
     * @return 移入回收站的记录 id，撤回时按这些 id 把笔记搬回原路径
     */
    fun deleteFolder(path: FolderPath): List<String> {
        val directory = folderDirectory(path)
        if (directory == root) throw VaultFileException(VaultFileFailure.INVALID_PATH)
        if (!directory.isDirectory) throw VaultFileException(VaultFileFailure.NOT_FOUND)
        val notes = mutableListOf<File>()
        collectNotes(directory, notes)
        val trashIds = notes.map { note -> trash(note).trashId }
        deleteEmptyDirectories(directory)
        return trashIds
    }

    /** 改名，目标同名时拒绝，避免悄悄覆盖别人的笔记 */
    fun rename(note: File, newName: String): File {
        val destination = File(note.parentFile, "${validateName(newName, folder = false)}$MARKDOWN_SUFFIX").canonicalFile
        if (!isInside(destination)) throw VaultFileException(VaultFileFailure.INVALID_PATH)
        if (destination == note) return note
        if (destination.exists()) throw VaultFileException(VaultFileFailure.NAME_TAKEN)
        if (!note.renameTo(destination)) throw VaultFileException(VaultFileFailure.IO)
        return destination
    }

    /** 目录不存在就建出来；新建笔记与移动都依赖它 */
    fun ensureFolder(path: FolderPath): File {
        val directory = folderDirectory(path)
        if (!directory.isDirectory && !directory.mkdirs()) {
            throw VaultFileException(VaultFileFailure.IO)
        }
        return directory
    }

    /** 在目录里按 `标题.md` 建一个空文件；重名时续号为 `标题 (1).md` */
    fun newNoteFile(folder: FolderPath, title: String, fallbackName: String): File {
        val directory = ensureFolder(folder)
        val base = safeFilename(title, fallbackName)
        for (index in 0..MAX_DUPLICATES) {
            val name = if (index == 0) "$base$MARKDOWN_SUFFIX" else "$base ($index)$MARKDOWN_SUFFIX"
            val candidate = File(directory, name).canonicalFile
            if (!isInside(candidate)) throw VaultFileException(VaultFileFailure.INVALID_PATH)
            if (candidate.createNewFile()) return candidate
        }
        throw VaultFileException(VaultFileFailure.NAME_TAKEN)
    }

    /** 移动到另一个目录；同名文件已存在时保留两者，按 `名字 (1).md` 续号 */
    fun move(note: File, target: FolderPath): File {
        val directory = folderDirectory(target)
        if (!directory.isDirectory && !directory.mkdirs()) {
            throw VaultFileException(VaultFileFailure.IO)
        }
        if (directory == note.parentFile) return note
        val destination = uniqueIn(directory, note.name)
        if (!note.renameTo(destination)) throw VaultFileException(VaultFileFailure.IO)
        return destination
    }

    fun trash(note: File, deletedAt: Instant = Instant.now()): TrashRecord {
        val trashDir = trashDirectory(create = true)
        val trashId = UUID.randomUUID().toString()
        val trashFile = File(trashDir, "$trashId$MARKDOWN_SUFFIX")
        val metaFile = File(trashDir, "$trashId$META_SUFFIX")
        val originalPath = NotePath(note.relativeTo(root).invariantSeparatorsPath)
        writeProperties(
            metaFile,
            Properties().apply {
                setProperty(KEY_ORIGINAL_PATH, originalPath.relativePath)
                setProperty(KEY_DELETED_AT, deletedAt.toEpochMilli().toString())
            },
        )
        if (!note.renameTo(trashFile)) {
            metaFile.delete()
            throw VaultFileException(VaultFileFailure.IO)
        }
        return TrashRecord(trashId, originalPath, deletedAt, trashFile, metaFile)
    }

    fun trashed(): List<TrashRecord> {
        val trashDir = trashDirectory(create = false) ?: return emptyList()
        return trashDir.listFiles().orEmpty()
            .asSequence()
            .filter { it.isFile && it.extension == "properties" && isInside(it) }
            .mapNotNull { meta -> readTrashRecord(trashDir, meta) }
            .sortedByDescending { it.deletedAt }
            .toList()
    }

    /** 彻底删除一条回收站记录，正文与元数据一起删掉，不可恢复 */
    fun deletePermanently(trashId: String) = deletePermanently(recordOf(trashId))

    /** 彻底删除一条已经查到的记录，不再重扫回收站目录 */
    internal fun deletePermanently(record: TrashRecord) {
        if (!record.file.delete()) throw VaultFileException(VaultFileFailure.IO)
        record.metaFile.delete()
    }

    /** 回到原路径；原位置已有同名文件时拒绝，交由用户先处理冲突 */
    fun restore(trashId: String): File = restore(recordOf(trashId))

    /** 恢复一条已经查到的记录，不再重扫回收站目录 */
    internal fun restore(record: TrashRecord): File {
        val destination = File(root, record.originalPath.relativePath).canonicalFile
        if (!isInside(destination) || destination == root) {
            throw VaultFileException(VaultFileFailure.INVALID_PATH)
        }
        if (destination.exists()) throw VaultFileException(VaultFileFailure.RESTORE_CONFLICT)
        destination.parentFile?.let { parent ->
            if (!parent.isDirectory && !parent.mkdirs()) throw VaultFileException(VaultFileFailure.IO)
        }
        if (!record.file.renameTo(destination)) throw VaultFileException(VaultFileFailure.IO)
        record.metaFile.delete()
        return destination
    }

    /**
     * 按 id 查出记录。回收站要逐个读 .properties，所以批量操作拿到 [trashed] 快照后
     * 直接走记录版的重载，不要为每一篇再调一次单 id 版。
     */
    private fun recordOf(trashId: String): TrashRecord {
        if (!UUID_PATTERN.matches(trashId)) throw VaultFileException(VaultFileFailure.INVALID_PATH)
        return trashed().firstOrNull { it.trashId == trashId }
            ?: throw VaultFileException(VaultFileFailure.NOT_FOUND)
    }

    private fun readTrashRecord(trashDir: File, metaFile: File): TrashRecord? = runCatching {
        val trashId = metaFile.nameWithoutExtension
        if (!UUID_PATTERN.matches(trashId)) return@runCatching null
        val file = File(trashDir, "$trashId$MARKDOWN_SUFFIX")
        if (!file.isFile || !isInside(file)) return@runCatching null
        val properties = readProperties(metaFile)
        val original = properties.getProperty(KEY_ORIGINAL_PATH) ?: return@runCatching null
        val deletedAt = properties.getProperty(KEY_DELETED_AT)?.toLongOrNull()
            ?: return@runCatching null
        TrashRecord(trashId, NotePath(original), Instant.ofEpochMilli(deletedAt), file, metaFile)
    }.getOrNull()

    private fun trashDirectory(create: Boolean): File? {
        val directory = File(root, TRASH_DIRECTORY).canonicalFile
        if (!isInside(directory)) throw VaultFileException(VaultFileFailure.INVALID_PATH)
        if (directory.isDirectory) return directory
        if (!create) return null
        if (!directory.mkdirs()) throw VaultFileException(VaultFileFailure.IO)
        return directory
    }

    /**
     * 收集目录下所有 Markdown 笔记，点开头的目录整棵跳过。
     *
     * 排序只为让回收站里的先后顺序稳定；读不了的目录直接报 IO，
     * 宁可整次删除失败，也不能漏掉没看见的笔记。
     */
    private fun collectNotes(directory: File, into: MutableList<File>) {
        val children = directory.listFiles() ?: throw VaultFileException(VaultFileFailure.IO)
        children.sortedBy { it.name }.forEach { child ->
            if (child.isDirectory) {
                if (child.name.startsWith('.') || !isInside(child)) return@forEach
                collectNotes(child, into)
            } else if (child.isFile && child.extension.equals("md", ignoreCase = true) && isInside(child)) {
                into += child
            }
        }
    }

    /** 自底向上删掉空目录；点开头目录与含非 Markdown 文件的目录都保留 */
    private fun deleteEmptyDirectories(directory: File) {
        directory.listFiles().orEmpty()
            .filter { it.isDirectory && !it.name.startsWith('.') }
            .forEach(::deleteEmptyDirectories)
        val remaining = directory.listFiles() ?: return
        if (remaining.isEmpty()) directory.delete()
    }

    private fun uniqueIn(directory: File, filename: String): File {
        val base = filename.removeSuffix(MARKDOWN_SUFFIX)
        for (index in 0..MAX_DUPLICATES) {
            val name = if (index == 0) "$base$MARKDOWN_SUFFIX" else "$base ($index)$MARKDOWN_SUFFIX"
            val candidate = File(directory, name).canonicalFile
            if (!isInside(candidate)) throw VaultFileException(VaultFileFailure.INVALID_PATH)
            if (!candidate.exists()) return candidate
        }
        throw VaultFileException(VaultFileFailure.NAME_TAKEN)
    }

    private fun validateName(raw: String, folder: Boolean): String {
        val name = raw.trim().removeSuffix(MARKDOWN_SUFFIX).trim(' ', '.')
        if (name.isEmpty()) throw VaultFileException(VaultFileFailure.INVALID_NAME)
        if (name == "." || name == ".." || name.startsWith('.')) {
            throw VaultFileException(VaultFileFailure.INVALID_NAME)
        }
        if (name.length > MAX_NAME_LENGTH || name.any { it in FORBIDDEN_CHARS || it.isISOControl() }) {
            throw VaultFileException(VaultFileFailure.INVALID_NAME)
        }
        if (folder && name.contains('/')) throw VaultFileException(VaultFileFailure.INVALID_NAME)
        return name
    }

    fun isInside(candidate: File): Boolean =
        candidate.canonicalFile.toPath().startsWith(root.toPath())

    private fun writeProperties(file: File, data: Properties) {
        val temp = File(file.parentFile, "${file.name}.tmp")
        try {
            temp.outputStream().use { data.store(it, "Looma trash metadata") }
            if (!temp.renameTo(file)) throw IOException("rename failed")
        } catch (e: IOException) {
            temp.delete()
            throw VaultFileException(VaultFileFailure.IO, e)
        }
    }

    private fun readProperties(file: File): Properties = Properties().apply {
        file.inputStream().use { load(it) }
    }

    companion object {
        const val TRASH_DIRECTORY = ".looma/trash"
        const val MARKDOWN_SUFFIX = ".md"
        private const val META_SUFFIX = ".properties"
        private const val MAX_NAME_LENGTH = 72
        private const val MAX_DUPLICATES = 999
        private const val KEY_ORIGINAL_PATH = "originalPath"
        private const val KEY_DELETED_AT = "deletedAt"
        private const val FORBIDDEN_CHARS = "\\/:*?\"<>|"
        private val UUID_PATTERN = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
    }
}

/** 把用户输入变成安全的文件名；只处理文件系统规则，不猜内容 */
internal fun safeFilename(raw: String, fallback: String): String {
    val name = raw.trim()
        .removeSuffix(VaultFileOps.MARKDOWN_SUFFIX)
        .replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_")
        .trim(' ', '.')
        .take(72)
    return name.ifBlank { fallback }
}
