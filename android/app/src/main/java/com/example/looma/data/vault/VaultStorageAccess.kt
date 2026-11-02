package com.example.looma.data.vault

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import androidx.core.content.ContextCompat
import com.example.looma.domain.model.FolderPath
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

    fun isStorageMounted(): Boolean =
        Environment.getExternalStorageDirectory().exists()

    fun resolveVaultDirectory(uri: Uri): File {
        require(
            uri.authority == EXTERNAL_STORAGE_AUTHORITY,
        ) {
            "only folders on local shared storage are supported"
        }

        val documentId = DocumentsContract.getTreeDocumentId(uri)

        val volume = documentId.substringBefore(':')
        val relativePath = documentId.substringAfter(':', "")

        require(volume.equals(PRIMARY_VOLUME, ignoreCase = true)) {
            "only primary shared storage is supported"
        }

        require(relativePath.isNotBlank()) {
            "the storage root cannot be used as a vault"
        }

        val storageRoot =
            Environment.getExternalStorageDirectory().canonicalFile

        val directory =
            File(storageRoot, relativePath).canonicalFile

        require(
            directory != storageRoot &&
                    directory.toPath().startsWith(storageRoot.toPath()),
        ) {
            "invalid vault path"
        }

        return directory
    }

    /**
     * 把目录选择器的结果换算成笔记库内的相对路径。
     *
     * 选中笔记库外、或不是共享存储里的目录时返回 null，调用方据此提示而不是抛异常：
     * 用户取消或选错都是正常操作。
     */
    fun resolveFolderWithin(uri: Uri, vaultRoot: String?): FolderPath? {
        val root = vaultRoot?.takeIf { it.isNotBlank() }
            ?.let { runCatching { File(it).canonicalFile }.getOrNull() }
            ?: return null
        val directory = runCatching { resolveVaultDirectory(uri) }.getOrNull() ?: return null
        val relative = runCatching { directory.canonicalFile.relativeTo(root) }.getOrNull() ?: return null
        val path = relative.invariantSeparatorsPath
        if (path == ".." || path.startsWith("../")) return null
        return FolderPath(path)
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
