package com.example.looma

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.example.looma.data.vault.VaultRepository
import com.example.looma.data.vault.VaultStorageAccess
import com.example.looma.feature.vault.VaultSetupScreen
import com.example.looma.feature.vault.VaultSetupViewModel
import com.example.looma.ui.theme.LoomaTheme

class MainActivity : ComponentActivity() {

    private val vaultRepository by lazy {
        VaultRepository(applicationContext)
    }

    private val vaultSetupViewModel: VaultSetupViewModel by viewModels {
        VaultSetupViewModel.factory(vaultRepository)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            LoomaTheme {
                Scaffold(Modifier.fillMaxSize()) { innerPadding ->
                    VaultSetupRoute(
                        viewModel = vaultSetupViewModel,
                        modifier = Modifier.padding(innerPadding),
                    )
                }
            }
        }
    }
}

@Composable
private fun VaultSetupRoute(
    viewModel: VaultSetupViewModel,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val storageAccess = remember(context) { VaultStorageAccess(context) }
    val uiState by viewModel.uiState.collectAsState()

    LaunchedEffect(uiState.selectionSaved) {
        if (uiState.selectionSaved) {
            Toast.makeText(
                context,
                "笔记库连接成功",
                Toast.LENGTH_SHORT,
            ).show()

            viewModel.onNavigationHandled()

            // TODO: 后续接入 Home 导航
        }
    }

    // 系统文件树选择器，只用来得到真实路径
    val folderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
    ) { uri: Uri? ->
        if (uri != null) {
            runCatching {
                storageAccess.resolveVaultDirectory(uri)
            }.onSuccess { directory ->
                viewModel.onFolderSelected(
                    directory.path,
                    directory.name,
                )
            }.onFailure { error ->
                viewModel.showError(
                    error.localizedMessage
                        ?: "无法识别所选文件夹",
                )
            }
        }
    }

    // Android 11+ 全部文件访问权限设置
    val settingsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) {
        if (storageAccess.hasAccess()) {
            folderPickerLauncher.launch(null)
        } else {
            viewModel.showError(storageAccess.accessRequiredMessage)
        }
    }

    // Android 10 及以下存储权限
    val legacyPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        if (storageAccess.hasAccess()) {
            folderPickerLauncher.launch(null)
        } else {
            viewModel.showError(storageAccess.accessRequiredMessage)
        }
    }

    VaultSetupScreen(
        selectedFolderName = uiState.selectedFolderName,
        currentFolderName = uiState.currentFolderName,
        isInitializing = uiState.isInitializing,
        isBusy = uiState.isBusy,
        errorMessage = uiState.errorMessage,
        onChooseFolderClick = {
            viewModel.clearError()

            when {
                storageAccess.hasAccess() -> folderPickerLauncher.launch(null)
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.R ->
                    openAllFilesAccessSettings(
                        context = context,
                        launcher = settingsLauncher,
                        onUnavailable = {
                            viewModel.showError("无法打开存储权限设置页面")
                        },
                    )
                else -> legacyPermissionLauncher.launch(
                    VaultStorageAccess.LEGACY_PERMISSIONS,
                )
            }
        },
        onConfirmClick = {
            viewModel.confirmSelection()
        },
        modifier = modifier,
    )
}

private fun openAllFilesAccessSettings(
    context: Context,
    launcher: ActivityResultLauncher<Intent>,
    onUnavailable: () -> Unit,
) {
    val appSettings = Intent(
        Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
    ).apply {
        data = Uri.parse("package:${context.packageName}")
    }

    try {
        launcher.launch(appSettings)
    } catch (_: ActivityNotFoundException) {
        try {
            launcher.launch(
                Intent(
                    Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION,
                ),
            )
        } catch (_: ActivityNotFoundException) {
            onUnavailable()
        }
    }
}
