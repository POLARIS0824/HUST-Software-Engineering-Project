package com.example.looma.data.folder

import com.example.looma.data.vault.VaultRepository
import com.example.looma.domain.model.FolderPath
import com.example.looma.domain.model.VaultFolder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files

/** Only manages physical directories. Reading the folder tree happens in NoteFileStore's single scan. */
class FolderRepository(private val vaultRepository: VaultRepository) {

    suspend fun createFolder(parent: FolderPath, name: String): VaultFolder =
        withContext(Dispatchers.IO) {
            val vault = vaultRepository.requireCurrentVault()
            val root = File(vault.rootPath).canonicalFile
            val parentDir = resolveFolder(root, parent)
            require(parentDir.isDirectory) { "父文件夹不存在" }

            val normalized = name.trim()
            require(
                normalized.isNotEmpty() && normalized != "." && normalized != ".." &&
                        !normalized.startsWith('.') && normalized.length <= 80 &&
                        normalized.none { it in "/\\:*?\"<>|" || it.isISOControl() }
            ) { "文件夹名称无效" }

            val candidate = File(parentDir, normalized).canonicalFile
            require(candidate.toPath().startsWith(root.toPath()) && candidate != root) {
                "文件夹路径超出 Vault"
            }
            if (candidate.exists()) throw FileAlreadyExistsException(candidate.path)
            Files.createDirectory(candidate.toPath())
            VaultFolder(
                vaultId = vault.id,
                path = FolderPath(candidate.relativeTo(root).invariantSeparatorsPath),
                name = normalized,
            )
        }

    private fun resolveFolder(root: File, path: FolderPath): File {
        val relative = path.relativePath
        if (relative.isEmpty()) return root
        require(
            !relative.startsWith('/') && '\\' !in relative &&
                    relative.split('/').none { it.isBlank() || it == "." || it == ".." || it.startsWith('.') }
        ) { "文件夹路径无效" }
        val directory = File(root, relative).canonicalFile
        require(directory != root && directory.toPath().startsWith(root.toPath())) {
            "文件夹路径超出 Vault"
        }
        return directory
    }
}
