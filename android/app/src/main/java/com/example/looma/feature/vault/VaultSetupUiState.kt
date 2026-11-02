package com.example.looma.feature.vault

import com.example.looma.domain.model.Vault

/**
 * 已保存笔记库的可用性，由存储校验推导
 * 界面只根据它决定显示已连接还是不可用，不再相信配置里存着的路径
 */
sealed interface VaultState {
    /** 首次读取配置并校验，尚未有结果 */
    data object Checking : VaultState

    data object None : VaultState

    data class Ready(val vault: Vault) : VaultState

    data class Unavailable(val vault: Vault, val reason: String) : VaultState
}

/** 笔记库操作失败的原因，文案由界面按资源映射，ViewModel 不持有字符串 */
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

    /** 界面只显示一条错误：正在挑选新文件夹时显示操作错误，否则笔记库不可用的原因优先 */
    val error: VaultSetupError?
        get() = if (pendingVaultName != null) {
            actionError
        } else {
            if (vaultState is VaultState.Unavailable) VaultSetupError.UNAVAILABLE else actionError
        }
}
