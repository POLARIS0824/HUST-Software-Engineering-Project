package com.example.looma.feature.vault

import com.example.looma.domain.model.Vault

data class VaultSetupUiState(
    val currentVault: Vault? = null,
    val pendingVaultPath: String? = null,
    val pendingVaultName: String? = null,
    val isBusy: Boolean = false,
    val errorMessage: String? = null,
    val selectionSaved: Boolean = false,
) {
    val currentFolderName: String?
        get() = currentVault?.name

    val selectedFolderName: String?
        get() = pendingVaultName
}
