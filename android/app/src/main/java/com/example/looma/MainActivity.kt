package com.example.looma

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.example.looma.data.vault.VaultRepository
import com.example.looma.feature.vault.VaultSetupScreen
import com.example.looma.feature.vault.VaultSetupViewModel
import com.example.looma.ui.theme.LoomaTheme
import java.io.File

class MainActivity : ComponentActivity() {

    private val vaultRepository by lazy { VaultRepository(applicationContext) }
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
    val uiState by viewModel.uiState.collectAsState()

    LaunchedEffect(uiState.selectionSaved) {
        if (uiState.selectionSaved) {
            Toast.makeText(context, "笔记库连接成功", Toast.LENGTH_SHORT).show()
            viewModel.onNavigationHandled()
            // 未来此处可触发导航，如：navController.navigate("home")
        }
    }

    val folderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            } catch (_: Exception) {
                // Ignore if permission taking is not supported for specific providers
            }
            // 临时测试：将系统选择器返回的 SAF Uri 映射到本地共享存储路径供测试
            val relativePath = uri.lastPathSegment?.substringAfterLast(':')?.trim('/') ?: ""
            val resolvedFile = if (relativePath.isNotEmpty()) {
                File(Environment.getExternalStorageDirectory(), relativePath)
            } else {
                Environment.getExternalStorageDirectory()
            }
            viewModel.onFolderSelected(resolvedFile.canonicalPath, resolvedFile.name.ifBlank { "Vault" })
        }
    }

    VaultSetupScreen(
        selectedFolderName = uiState.selectedFolderName,
        currentFolderName = uiState.currentFolderName,
        isBusy = uiState.isBusy,
        errorMessage = uiState.errorMessage,
        onChooseFolderClick = {
            folderPickerLauncher.launch(null)
        },
        onConfirmClick = {
            viewModel.confirmSelection()
        },
        modifier = modifier,
    )
}
