package com.example.looma.feature.vault

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.looma.data.vault.VaultRepository
import com.example.looma.domain.model.Vault
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class VaultSetupViewModel(
    private val vaultRepository: VaultRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(VaultSetupUiState())

    val uiState: StateFlow<VaultSetupUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            vaultRepository.currentVaultFlow.collect { vault ->
                applyStoredVault(vault)
            }
        }
    }

    /** 页面回到前台时重新校验，权限或文件夹可能已经变化 */
    fun refreshVaultStatus() {
        viewModelScope.launch {
            applyStoredVault(vaultRepository.getCurrentVault())
        }
    }

    fun onFolderSelected(
        path: String,
        folderName: String,
    ) {
        if (_uiState.value.isBusy) return

        _uiState.update {
            it.copy(
                pendingVaultPath = path,
                pendingVaultName = folderName,
                actionError = null,
                selectionSaved = false,
            )
        }
    }

    fun confirmSelection() {
        val currentState = _uiState.value

        if (currentState.isBusy) return

        val path = currentState.pendingVaultPath

        _uiState.update {
            it.copy(
                isBusy = true,
                actionError = null,
                selectionSaved = false,
            )
        }

        viewModelScope.launch {
            try {
                val vault = if (path != null) {
                    vaultRepository.setCurrentVault(path)
                } else {
                    vaultRepository.requireCurrentVault()
                }

                _uiState.update {
                    it.copy(
                        vaultState = VaultState.Ready(vault),
                        pendingVaultPath = null,
                        pendingVaultName = null,
                        isBusy = false,
                        selectionSaved = true,
                    )
                }
            } catch (e: CancellationException) {
                _uiState.update {
                    it.copy(isBusy = false)
                }
                throw e
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isBusy = false,
                        actionError = e.localizedMessage
                            ?.takeIf { message -> message.isNotBlank() }
                            ?: "连接笔记库失败，请重试",
                    )
                }

                // 失败可能来自已保存的笔记库本身，例如权限被撤销，重新校验后让界面与存储保持一致
                applyStoredVault(vaultRepository.getCurrentVault())
            }
        }
    }

    /** 文件选择器返回了无法识别的目录 */
    fun showError(message: String) {
        _uiState.update {
            it.copy(
                actionError = message,
                selectionSaved = false,
            )
        }
    }

    fun clearError() {
        _uiState.update {
            it.copy(actionError = null)
        }
    }

    fun onNavigationHandled() {
        _uiState.update {
            it.copy(selectionSaved = false)
        }
    }

    private suspend fun applyStoredVault(vault: Vault?) {
        val nextState = if (vault == null) {
            VaultState.None
        } else {
            val reason = vaultRepository.vaultAccessError(vault)

            if (reason == null) {
                VaultState.Ready(vault)
            } else {
                VaultState.Unavailable(vault, reason)
            }
        }

        _uiState.update { state ->
            state.copy(
                vaultState = nextState,
                // 笔记库从不可用恢复到可用后，之前那条错误就不成立了
                actionError = if (nextState is VaultState.Ready &&
                    state.vaultState is VaultState.Unavailable
                ) {
                    null
                } else {
                    state.actionError
                },
            )
        }
    }

    companion object {
        fun factory(
            vaultRepository: VaultRepository,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                VaultSetupViewModel(vaultRepository)
            }
        }
    }
}
