package com.example.looma.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.looma.data.settings.AppSettings
import com.example.looma.data.settings.SettingsRepository
import com.example.looma.data.settings.ThemePreset
import com.example.looma.domain.markdown.ToolbarItem
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** 设置页的状态与写入；写入后 DataStore 会回流，MainActivity 据此换主题 */
class SettingsViewModel(
    private val repository: SettingsRepository,
) : ViewModel() {

    val settings: StateFlow<AppSettings> = repository.settings.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = AppSettings(),
    )

    fun setDynamicColor(enabled: Boolean) {
        viewModelScope.launch { repository.setDynamicColor(enabled) }
    }

    /**
     * 选预设就意味着要这套颜色，所以同时关掉动态取色，
     * 否则系统壁纸配色会盖住用户刚选的主题。
     */
    fun setThemePreset(preset: ThemePreset) {
        viewModelScope.launch {
            repository.setThemePreset(preset)
            repository.setDynamicColor(false)
        }
    }

    fun setKeepEmptyNotes(keep: Boolean) {
        viewModelScope.launch { repository.setKeepEmptyNotes(keep) }
    }

    fun setToolbarOrder(order: List<ToolbarItem>) {
        viewModelScope.launch { repository.setToolbarOrder(order) }
    }

    companion object {
        fun factory(repository: SettingsRepository): ViewModelProvider.Factory = viewModelFactory {
            initializer { SettingsViewModel(repository) }
        }
    }
}
