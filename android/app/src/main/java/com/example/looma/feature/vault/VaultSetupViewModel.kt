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

    // 1. 当前页面可修改的临时状态
    private val _formState = MutableStateFlow(VaultSetupUiState())

    // 2. 合并 Repository 数据和页面状态
    val uiState: StateFlow<VaultSetupUiState> = combine(
        vaultRepository.currentVaultFlow,
        _formState,
    ) { currentVault, formState ->
        formState.copy(currentVault = currentVault)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = VaultSetupUiState(),
    )

    // 3. 用户选择文件夹
    fun onFolderSelected(pathOrUri: String, folderName: String) {
        if (_formState.value.isBusy) return

        _formState.update {
            it.copy(
                pendingVaultPath = pathOrUri,
                pendingVaultName = folderName,
                errorMessage = null,
                selectionSaved = false,
            )
        }
    }

    // 4. 确认保存或继续
    fun confirmSelection() {
        val currentState = _formState.value

        if (currentState.isBusy) return

        val path = currentState.pendingVaultPath
        if (path == null) {
            // 已存在已连接的笔记库，点击“继续”时标记成功并进入后续流程
            if (uiState.value.currentVault != null) {
                _formState.update {
                    it.copy(selectionSaved = true)
                }
            }
            return
        }

        _formState.update {
            it.copy(
                isBusy = true,
                errorMessage = null,
                selectionSaved = false,
            )
        }

        viewModelScope.launch {
            try {
                vaultRepository.setCurrentVault(path)

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
                        errorMessage = e.localizedMessage?.takeIf { it.isNotBlank() } ?: "保存笔记库失败，请重试",
                    )
                }
            }
        }
    }

    // 5. 页面已处理保存成功状态
    fun onNavigationHandled() {
        _formState.update {
            it.copy(selectionSaved = false)
        }
    }

    // 6. 清除错误
    fun clearError() {
        _formState.update {
            it.copy(errorMessage = null)
        }
    }

    // 7. ViewModel Factory
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
