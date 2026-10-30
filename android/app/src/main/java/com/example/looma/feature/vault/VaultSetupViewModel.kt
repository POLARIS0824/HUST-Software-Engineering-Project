
package com.example.looma.feature.vault

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.looma.data.vault.VaultRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class VaultSetupViewModel(
    private val vaultRepository: VaultRepository,
) : ViewModel() {

    // 1. 页面临时状态
    private val _formState = MutableStateFlow(
        VaultSetupUiState()
    )

    // 2. 合并 Repository 数据和页面状态
    val uiState: StateFlow<VaultSetupUiState> = combine(
        vaultRepository.currentVaultFlow,
        _formState,
    ) { currentVault, formState ->
        formState.copy(
            currentVault = currentVault,
            isInitializing = false,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = VaultSetupUiState(isInitializing = true),
    )

    // 3. 选择新的文件夹
    fun onFolderSelected(
        path: String,
        folderName: String,
    ) {
        if (_formState.value.isBusy) return

        _formState.update {
            it.copy(
                pendingVaultPath = path,
                pendingVaultName = folderName,
                errorMessage = null,
                selectionSaved = false,
            )
        }
    }

    // 4. 确认选择或继续使用已有 Vault
    fun confirmSelection() {
        val currentState = _formState.value

        if (currentState.isBusy) return

        val path = currentState.pendingVaultPath

        _formState.update {
            it.copy(
                isBusy = true,
                errorMessage = null,
                selectionSaved = false,
            )
        }

        viewModelScope.launch {
            try {
                if (path != null) {
                    // 用户选择了新文件夹
                    vaultRepository.setCurrentVault(path)
                } else {
                    // 用户继续使用原来的 Vault
                    vaultRepository.requireCurrentVault()
                }

                _formState.update {
                    it.copy(
                        pendingVaultPath = null,
                        pendingVaultName = null,
                        isBusy = false,
                        selectionSaved = true,
                    )
                }
            } catch (e: CancellationException) {
                _formState.update {
                    it.copy(isBusy = false)
                }
                throw e
            } catch (e: Exception) {
                _formState.update {
                    it.copy(
                        isBusy = false,
                        errorMessage = e.localizedMessage
                            ?.takeIf { it.isNotBlank() }
                            ?: "连接笔记库失败，请重试",
                    )
                }
            }
        }
    }

    // 5. 外部错误，例如文件选择器不支持的 URI
    fun showError(message: String) {
        _formState.update {
            it.copy(
                errorMessage = message,
                selectionSaved = false,
            )
        }
    }

    // 6. 清除错误
    fun clearError() {
        _formState.update {
            it.copy(
                errorMessage = null,
            )
        }
    }

    // 7. 页面已处理连接成功事件
    fun onNavigationHandled() {
        _formState.update {
            it.copy(
                selectionSaved = false,
            )
        }
    }

    // 8. ViewModel Factory
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
