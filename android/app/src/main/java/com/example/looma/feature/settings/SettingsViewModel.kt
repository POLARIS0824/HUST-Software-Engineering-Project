package com.example.looma.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.looma.data.settings.AppSettings
import com.example.looma.data.settings.ImageUploadTarget
import com.example.looma.data.settings.S3ImageBedConfig
import com.example.looma.data.settings.SettingsRepository
import com.example.looma.data.settings.ThemePreset
import com.example.looma.domain.markdown.ToolbarItem
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

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

    fun setThemePreset(preset: ThemePreset) {
        viewModelScope.launch {
            repository.setThemePreset(preset)
            repository.setDynamicColor(false)
        }
    }

    fun setKeepEmptyNotes(keep: Boolean) {
        viewModelScope.launch { repository.setKeepEmptyNotes(keep) }
    }

    fun setShowInlineTitle(show: Boolean) {
        viewModelScope.launch { repository.setShowInlineTitle(show) }
    }

    fun setToolbarOrder(order: List<ToolbarItem>) {
        viewModelScope.launch { repository.setToolbarOrder(order) }
    }

    fun setImageUploadTarget(target: ImageUploadTarget) {
        viewModelScope.launch { repository.setImageUploadTarget(target) }
    }

    fun setS3ImageBed(config: S3ImageBedConfig) {
        viewModelScope.launch { repository.setS3ImageBed(config) }
    }

    companion object {
        fun factory(repository: SettingsRepository): ViewModelProvider.Factory = viewModelFactory {
            initializer { SettingsViewModel(repository) }
        }
    }
}
