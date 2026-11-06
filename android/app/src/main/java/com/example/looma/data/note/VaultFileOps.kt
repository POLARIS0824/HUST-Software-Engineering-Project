package com.example.looma.data.note

import com.example.looma.domain.model.FolderPath
import com.example.looma.domain.model.NotePath
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.time.Instant
import java.util.Properties
import java.util.UUID

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

internal data class TrashRecord(
    val trashId: String,
    val originalPath: NotePath,
    val deletedAt: Instant,
    val file: File,
    val metaFile: File,
)

internal class VaultFileOps(root: File) {
    private val root: File = root.canonicalFile

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

    fun renameFolder(path: FolderPath, newName: String): FolderPath {
        val directory = folderDirectory(path)
        if (directory == root) throw VaultFileException(VaultFileFailure.INVALID_PATH)
        if (!directory.isDirectory) throw VaultFileException(VaultFileFailure.NOT_FOUND)
        val parent = directory.parentFile ?: throw VaultFileException(VaultFileFailure.INVALID_PATH)
        val destination = File(parent, validateName(newName, folder = true)).canonicalFile
        if (!isInside(destination) || destination == root) {
            throw VaultFileException(VaultFileFailure.INVALID_PATH)
        }
        if (destination == directory) return path
        if (destination.exists()) throw VaultFileException(VaultFileFailure.NAME_TAKEN)
        if (!directory.renameTo(destination)) throw VaultFileException(VaultFileFailure.IO)
        return FolderPath(destination.relativeTo(root).invariantSeparatorsPath)
    }

    fun moveFolder(path: FolderPath, destination: FolderPath): FolderPath {
        val source = folderDirectory(path)
        if (source == root) throw VaultFileException(VaultFileFailure.INVALID_PATH)
        if (!source.isDirectory) throw VaultFileException(VaultFileFailure.NOT_FOUND)
        val target = folderDirectory(destination)
        if (!target.isDirectory) throw VaultFileException(VaultFileFailure.NOT_FOUND)
        if (target == source.parentFile) return path
        val sourcePath = source.toPath()
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

    fun rename(note: File, newName: String): File {
        val destination = File(note.parentFile, "${validateName(newName, folder = false)}$MARKDOWN_SUFFIX").canonicalFile
        if (!isInside(destination)) throw VaultFileException(VaultFileFailure.INVALID_PATH)
        if (destination == note) return note
        if (destination.exists()) throw VaultFileException(VaultFileFailure.NAME_TAKEN)
        if (!note.renameTo(destination)) throw VaultFileException(VaultFileFailure.IO)
        return destination
    }

    fun ensureFolder(path: FolderPath): File {
        val directory = folderDirectory(path)
        if (!directory.isDirectory && !directory.mkdirs()) {
            throw VaultFileException(VaultFileFailure.IO)
        }
        return directory
    }

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

    fun deletePermanently(trashId: String) = deletePermanently(recordOf(trashId))

    internal fun deletePermanently(record: TrashRecord) {
        if (!record.file.delete()) throw VaultFileException(VaultFileFailure.IO)
        record.metaFile.delete()
    }

    fun restore(trashId: String): File = restore(recordOf(trashId))

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

internal fun safeFilename(raw: String, fallback: String): String {
    val name = raw.trim()
        .removeSuffix(VaultFileOps.MARKDOWN_SUFFIX)
        .replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_")
        .trim(' ', '.')
        .take(72)
    return name.ifBlank { fallback }
}
