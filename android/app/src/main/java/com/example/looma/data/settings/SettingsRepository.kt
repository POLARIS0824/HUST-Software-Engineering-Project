package com.example.looma.data.settings

import android.content.Context
import com.example.looma.domain.markdown.ToolbarItem
import com.example.looma.domain.markdown.ToolbarOrder
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class SettingsRepository(
    private val preferences: SettingsPreferences,
) {

    constructor(context: Context) : this(
        SettingsPreferences(context.applicationContext)
    )

    val settings: Flow<AppSettings> =
        preferences.settingsDataFlow.map { data ->

            AppSettings(
                dynamicColor = data.dynamicColor ?: true,
                themePreset = data.themePreset
                    ?.let { stored -> ThemePreset.entries.firstOrNull { it.name == stored } }
                    ?: ThemePreset.Default,
                keepEmptyNotes = data.keepEmptyNotes ?: false,
                toolbarOrder = ToolbarOrder.restore(data.toolbarOrderString),
                isLoaded = true,
            )
        }

    suspend fun setToolbarOrder(order: List<ToolbarItem>) {
        preferences.saveToolbarOrder(ToolbarOrder.encode(order))
    }

    suspend fun setDynamicColor(enabled: Boolean) {
        preferences.saveDynamicColor(enabled)
    }

    suspend fun setThemePreset(preset: ThemePreset) {
        preferences.saveThemePreset(preset.name)
    }

    suspend fun setKeepEmptyNotes(keep: Boolean) {
        preferences.saveKeepEmptyNotes(keep)
    }
}
