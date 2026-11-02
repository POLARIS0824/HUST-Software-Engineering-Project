package com.example.looma.data.vault

import android.content.Context
import android.os.Environment
import com.example.looma.domain.model.Vault
import com.example.looma.domain.model.VaultId
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

class VaultRepository(
    private val preferences: VaultPreferences,
    private val storageAccess: VaultStorageAccess,
) {

    constructor(context: Context) : this(
        VaultPreferences(context.applicationContext),
        VaultStorageAccess(context),
    )

    // 1. 观察当前 Vault
    val currentVaultFlow: Flow<Vault?> =
        preferences.vaultDataFlow.map { data ->

            val id = data.id
                ?.takeIf { it.isNotBlank() }
                ?: return@map null

            val path = data.path
                ?.takeIf { it.isNotBlank() }
                ?: return@map null

            val name = data.name
                ?.takeIf { it.isNotBlank() }
                ?: extractVaultName(path)

            Vault(
                id = VaultId(id),
                name = name,
                rootPath = path,
            )
        }

    // 2. 获取当前 Vault
    suspend fun getCurrentVault(): Vault? {
        return currentVaultFlow.first()
    }

    // 3. 设置当前 Vault
    suspend fun setCurrentVault(rootPath: String): Vault =
        withContext(Dispatchers.IO) {

            val directory = requireValidDirectory(rootPath)

            val currentVault = getCurrentVault()

            // 比较规范化路径，避免重复创建 ID
            val currentPath = currentVault?.rootPath?.let { path ->
                runCatching {
                    File(path).canonicalPath
                }.getOrNull()
            }

            val id = if (
                currentVault != null &&
                currentPath == directory.path
            ) {
                currentVault.id.value
            } else {
                UUID.randomUUID().toString()
            }

            val vault = Vault(
                id = VaultId(id),
                name = extractVaultName(directory.path),
                rootPath = directory.path,
            )

            preferences.saveVault(
                id = vault.id.value,
                name = vault.name,
                path = vault.rootPath,
            )

            vault
        }

    // 4. 获取并验证已经保存的 Vault
    suspend fun requireCurrentVault(): Vault =
        withContext(Dispatchers.IO) {

            val vault = getCurrentVault()
                ?: error("no vault has been selected yet")

            requireValidDirectory(vault.rootPath)

            vault
        }

    // 5. 检查指定 Vault 是否可访问，可访问返回 null
    suspend fun vaultAccessError(
        vault: Vault,
    ): String? = withContext(Dispatchers.IO) {
        val error = runCatching {
            requireValidDirectory(vault.rootPath)
        }.exceptionOrNull() ?: return@withContext null

        error.localizedMessage?.takeIf { it.isNotBlank() }
            ?: "the vault folder is not accessible"
    }

    // 6. 清除当前 Vault
    suspend fun clearCurrentVault() {
        preferences.clearVault()
    }

    // 7. 验证真实文件夹和存储权限
    private fun requireValidDirectory(rootPath: String): File {
        require(rootPath.isNotBlank()) {
            "the vault path is empty"
        }

        require(!rootPath.contains("://")) {
            "the vault must be a real file path, not a URI"
        }

        check(storageAccess.hasAccess()) {
            "all files access is required"
        }

        check(storageAccess.isStorageMounted()) {
            "shared storage is not mounted"
        }

        val directory = File(rootPath).canonicalFile

        val storageRoot =
            Environment.getExternalStorageDirectory().canonicalFile

        require(
            directory != storageRoot &&
                    directory.toPath().startsWith(storageRoot.toPath())
        ) {
            "the vault must be a sub folder of primary shared storage"
        }

        require(directory.isDirectory) {
            "the vault folder does not exist"
        }

        require(
            directory.canRead() &&
                    directory.canWrite()
        ) {
            "the vault folder is not readable and writable"
        }

        return directory
    }

    private fun extractVaultName(path: String): String {
        return File(path).name.ifBlank { "Vault" }
    }
}
