package com.example.looma.data.folder

import com.example.looma.data.note.VaultFileOps
import com.example.looma.data.vault.VaultRepository
import com.example.looma.domain.model.FolderPath
import com.example.looma.domain.model.VaultFolder
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 只管理物理目录。目录树读取由 [com.example.looma.data.note.NoteFileStore] 的那一次扫描给出，
 * 这里只处理“新建一层目录”，校验与落盘都在 [VaultFileOps] 里。
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
}
