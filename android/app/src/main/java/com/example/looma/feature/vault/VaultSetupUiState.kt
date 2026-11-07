package com.example.looma.feature.vault

import com.example.looma.domain.model.Vault

sealed interface VaultState {
    data object Checking : VaultState

    data object None : VaultState

    data class Ready(val vault: Vault) : VaultState

    data class Unavailable(val vault: Vault, val reason: String) : VaultState
}

enum class VaultSetupError {
    INVALID_FOLDER,
    STORAGE_SETTINGS,
    PERMISSION_ALL_FILES,
    PERMISSION_STORAGE,
    UNAVAILABLE,
    GENERIC,
}

data class VaultSetupUiState(
    val vaultState: VaultState = VaultState.Checking,
    val pendingVaultPath: String? = null,
    val pendingVaultName: String? = null,
    val isBusy: Boolean = false,
    val actionError: VaultSetupError? = null,
    val selectionSaved: Boolean = false,
) {
    val isInitializing: Boolean
        get() = vaultState is VaultState.Checking

    val currentFolderName: String?
        get() = (vaultState as? VaultState.Ready)?.vault?.name

    val currentFolderPath: String?
        get() = (vaultState as? VaultState.Ready)?.vault?.rootPath

    val selectedFolderName: String?
        get() = pendingVaultName

    val error: VaultSetupError?
        get() = if (pendingVaultName != null) {
            actionError
        } else {
            if (vaultState is VaultState.Unavailable) VaultSetupError.UNAVAILABLE else actionError
        }
}
