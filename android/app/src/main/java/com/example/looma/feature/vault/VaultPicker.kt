package com.example.looma.feature.vault

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.example.looma.data.vault.VaultStorageAccess

@Immutable
data class VaultPickerActions(
    val hasAccess: () -> Boolean,
    val chooseFolder: () -> Unit,
)

@Composable
fun rememberVaultPickerActions(viewModel: VaultSetupViewModel): VaultPickerActions {
    val context = LocalContext.current
    val storageAccess = remember(context) { VaultStorageAccess(context) }

    val folderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
    ) { uri: Uri? ->
        if (uri != null) {
            runCatching { storageAccess.resolveVaultDirectory(uri) }
                .onSuccess { directory ->
                    viewModel.onFolderSelected(directory.path, directory.name)
                }
                .onFailure { viewModel.showError(VaultSetupError.INVALID_FOLDER) }
        }
    }

    val settingsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) {
        if (storageAccess.hasAccess()) {
            folderPickerLauncher.launch(null)
        } else {
            viewModel.showError(
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    VaultSetupError.PERMISSION_ALL_FILES
                } else {
                    VaultSetupError.PERMISSION_STORAGE
                },
            )
        }
    }

    val legacyPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        if (storageAccess.hasAccess()) {
            folderPickerLauncher.launch(null)
        } else {
            viewModel.showError(VaultSetupError.PERMISSION_STORAGE)
        }
    }

    return VaultPickerActions(
        hasAccess = storageAccess::hasAccess,
        chooseFolder = {
            viewModel.clearError()
            when {
                storageAccess.hasAccess() -> folderPickerLauncher.launch(null)
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.R ->
                    openAllFilesAccessSettings(
                        context = context,
                        launcher = settingsLauncher,
                        onUnavailable = { viewModel.showError(VaultSetupError.STORAGE_SETTINGS) },
                    )
                else -> legacyPermissionLauncher.launch(VaultStorageAccess.LEGACY_PERMISSIONS)
            }
        },
    )
}

private fun openAllFilesAccessSettings(
    context: Context,
    launcher: ActivityResultLauncher<Intent>,
    onUnavailable: () -> Unit,
) {
    val appSettings = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
        data = Uri.parse("package:${context.packageName}")
    }
    try {
        launcher.launch(appSettings)
    } catch (_: ActivityNotFoundException) {
        try {
            launcher.launch(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
        } catch (_: ActivityNotFoundException) {
            onUnavailable()
        }
    }
}
