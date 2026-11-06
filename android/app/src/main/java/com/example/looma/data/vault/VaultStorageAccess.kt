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
