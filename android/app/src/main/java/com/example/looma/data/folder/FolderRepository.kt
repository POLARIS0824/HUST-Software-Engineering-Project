package com.example.looma.data.folder

import com.example.looma.data.note.VaultFileOps
import com.example.looma.data.vault.VaultRepository
import com.example.looma.domain.model.FolderPath
import com.example.looma.domain.model.VaultFolder
import com.example.looma.domain.model.VaultId
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 只管理物理目录。目录树读取由 [com.example.looma.data.note.NoteFileStore] 的那一次扫描给出，
 * 这里只处理目录本身的新建、改名、移动与删除，校验与落盘都在 [VaultFileOps] 里。
 */
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

    /** 删除结果只在回收站里留得下笔记数，界面用它告诉用户移走了几篇 */
    suspend fun deleteFolder(path: FolderPath): Int = withContext(Dispatchers.IO) {
        val vault = vaultRepository.requireCurrentVault()
        VaultFileOps(File(vault.rootPath)).deleteFolder(path)
    }

    private fun vaultFolder(vaultId: VaultId, path: FolderPath): VaultFolder = VaultFolder(
        vaultId = vaultId,
        path = path,
        name = path.relativePath.substringAfterLast('/'),
    )
}
