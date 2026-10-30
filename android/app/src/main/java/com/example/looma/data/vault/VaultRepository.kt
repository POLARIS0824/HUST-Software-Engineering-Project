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
) {

    constructor(context: Context) : this(
        VaultPreferences(context.applicationContext)
    )

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

    suspend fun getCurrentVault(): Vault? {
        return currentVaultFlow.first()
    }

    suspend fun setCurrentVault(rootPath: String): Vault =
        withContext(Dispatchers.IO) {

            // 1. 获取规范化路径
            val directory = File(rootPath).canonicalFile

            // 2. 获取设备共享存储空间根目录
            val storageRoot = Environment.getExternalStorageDirectory().canonicalFile

            // 3. 检查目录范围
            require(
                directory.toPath().startsWith(storageRoot.toPath())
            ) {
                "只能选择设备共享存储空间中的文件夹"
            }

            require(directory != storageRoot) {
                "不能直接将整个存储空间作为笔记库"
            }

            // 4. 检查目录是否合法
            require(directory.isDirectory) {
                "所选文件夹不存在或不是目录"
            }

            require(directory.canRead() && directory.canWrite()) {
                "没有该文件夹的读写权限"
            }

            // 5. 获取旧 Vault
            val currentVault = getCurrentVault()

            // 6. 相同路径复用 ID，不同路径生成新 ID
            val id = if (
                currentVault?.rootPath == directory.path
            ) {
                currentVault.id.value
            } else {
                UUID.randomUUID().toString()
            }

            // 7. 构造领域对象
            val vault = Vault(
                id = VaultId(id),
                name = extractVaultName(directory.path),
                rootPath = directory.path,
            )

            // 8. 持久化配置
            preferences.saveVault(
                id = vault.id.value,
                name = vault.name,
                path = vault.rootPath,
            )

            vault
        }

    suspend fun clearCurrentVault() {
        preferences.clearVault()
    }

    private fun extractVaultName(path: String): String {
        return File(path).name.ifBlank { "Vault" }
    }
}
