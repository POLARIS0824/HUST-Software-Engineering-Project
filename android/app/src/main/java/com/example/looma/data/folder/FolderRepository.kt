package com.example.looma.data.folder

import com.example.looma.data.note.VaultFileOps
import com.example.looma.data.vault.VaultRepository
import com.example.looma.domain.model.FolderPath
import com.example.looma.domain.model.VaultFolder
import com.example.looma.domain.model.VaultId
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class FolderRepository(private val vaultRepository: VaultRepository) {

    suspend fun createFolder(parent: FolderPath, name: String): VaultFolder =
        withContext(Dispatchers.IO) {
            val vault = vaultRepository.requireCurrentVault()
            val ops = VaultFileOps(File(vault.rootPath))
            val directory = ops.createFolder(parent, name)
            VaultFolder(
                vaultId = vault.id,
                path = FolderPath(directory.relativeTo(ops.rootDir).invariantSeparatorsPath),
                name = directory.name,
            )
        }

    suspend fun renameFolder(path: FolderPath, newName: String): VaultFolder =
        withContext(Dispatchers.IO) {
            val vault = vaultRepository.requireCurrentVault()
            val ops = VaultFileOps(File(vault.rootPath))
            vaultFolder(vault.id, ops.renameFolder(path, newName))
        }

    suspend fun moveFolder(path: FolderPath, destination: FolderPath): VaultFolder =
        withContext(Dispatchers.IO) {
            val vault = vaultRepository.requireCurrentVault()
            val ops = VaultFileOps(File(vault.rootPath))
            vaultFolder(vault.id, ops.moveFolder(path, destination))
        }

    suspend fun deleteFolder(path: FolderPath): List<String> = withContext(Dispatchers.IO) {
        val vault = vaultRepository.requireCurrentVault()
        VaultFileOps(File(vault.rootPath)).deleteFolder(path)
    }

    private fun vaultFolder(vaultId: VaultId, path: FolderPath): VaultFolder = VaultFolder(
        vaultId = vaultId,
        path = path,
        name = path.relativePath.substringAfterLast('/'),
    )
}
