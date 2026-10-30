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
                ?: error("尚未选择笔记库")

            requireValidDirectory(vault.rootPath)

            vault
        }

    // 5. 检查指定 Vault 是否可访问
    suspend fun isVaultAccessible(
        vault: Vault,
    ): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            requireValidDirectory(vault.rootPath)
        }.isSuccess
    }

    // 6. 清除当前 Vault
    suspend fun clearCurrentVault() {
        preferences.clearVault()
    }

    // 7. 验证真实文件夹和存储权限
    private fun requireValidDirectory(rootPath: String): File {
        require(rootPath.isNotBlank()) {
            "笔记库路径不能为空"
        }

        require(!rootPath.contains("://")) {
            "笔记库必须使用真实文件路径，而不是 URI"
        }

        check(storageAccess.hasAccess()) {
            storageAccess.accessRequiredMessage
        }

        check(storageAccess.isStorageMounted()) {
            "系统还没有挂载共享存储，请重启手机或重新安装应用"
        }

        val directory = File(rootPath).canonicalFile

        val storageRoot =
            Environment.getExternalStorageDirectory().canonicalFile

        require(
            directory != storageRoot &&
                    directory.toPath().startsWith(storageRoot.toPath())
        ) {
            "只能选择主共享存储空间内的子文件夹"
        }

        require(directory.isDirectory) {
            "所选笔记库不存在或不是文件夹"
        }

        require(
            directory.canRead() &&
                    directory.canWrite()
        ) {
            "没有笔记库的读写权限"
        }

        return directory
    }

    private fun extractVaultName(path: String): String {
        return File(path).name.ifBlank { "Vault" }
    }
}
