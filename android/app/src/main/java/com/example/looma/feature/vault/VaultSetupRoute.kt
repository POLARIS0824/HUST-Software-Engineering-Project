package com.example.looma.feature.vault

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/** 首次启动的笔记库门禁：没有可用笔记库时整屏只做这一件事 */
@Composable
fun VaultSetupRoute(
    viewModel: VaultSetupViewModel,
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val picker = rememberVaultPickerActions(viewModel)

    VaultSetupScreen(
        selectedFolderName = uiState.selectedFolderName,
        currentFolderName = uiState.currentFolderName,
        isInitializing = uiState.isInitializing,
        isBusy = uiState.isBusy,
        error = uiState.error,
        onChooseFolderClick = picker.chooseFolder,
        onConfirmClick = viewModel::confirmSelection,
        modifier = modifier,
    )
}
