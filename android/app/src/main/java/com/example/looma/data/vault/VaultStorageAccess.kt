package com.example.looma.data.vault

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import androidx.core.content.ContextCompat
import java.io.File

/**
 * File-based Vault 所需的共享存储权限，以及 SAF 目录 URI 到真实路径的转换。
 * 只接受本地 ExternalStorageProvider 的 primary 卷。
 */
class VaultStorageAccess(context: Context) {
    private val appContext = context.applicationContext

    fun hasAccess(): Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Environment.isExternalStorageManager()
    } else {
        LEGACY_PERMISSIONS.all { permission ->
            ContextCompat.checkSelfPermission(
                appContext,
                permission,
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    val accessRequiredMessage: String
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            "请先授予全部文件访问权限"
        } else {
            "请先授予存储空间读写权限"
        }

    fun isStorageMounted(): Boolean =
        Environment.getExternalStorageDirectory().exists()

    fun resolveVaultDirectory(uri: Uri): File {
        require(
            uri.authority == EXTERNAL_STORAGE_AUTHORITY,
        ) {
            "请选择设备本地共享存储中的文件夹"
        }

        val documentId = DocumentsContract.getTreeDocumentId(uri)

        val volume = documentId.substringBefore(':')
        val relativePath = documentId.substringAfter(':', "")

        require(volume.equals(PRIMARY_VOLUME, ignoreCase = true)) {
            "当前仅支持设备主共享存储空间"
        }

        require(relativePath.isNotBlank()) {
            "不能直接选择存储空间根目录"
        }

        val storageRoot =
            Environment.getExternalStorageDirectory().canonicalFile

        val directory =
            File(storageRoot, relativePath).canonicalFile

        require(
            directory != storageRoot &&
                    directory.toPath().startsWith(storageRoot.toPath()),
        ) {
            "无效的笔记库路径"
        }

        return directory
    }

    companion object {
        private const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"
        private const val PRIMARY_VOLUME = "primary"

        val LEGACY_PERMISSIONS: Array<String> = arrayOf(
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.WRITE_EXTERNAL_STORAGE,
        )
    }
}
