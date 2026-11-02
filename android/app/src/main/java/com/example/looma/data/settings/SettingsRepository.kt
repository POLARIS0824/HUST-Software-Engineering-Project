package com.example.looma.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "looma_app_settings",
)

/** 主题与新建笔记行为的唯一读写入口 */
class SettingsRepository(context: Context) {

    private val dataStore = context.applicationContext.settingsDataStore

    val settings: Flow<AppSettings> = dataStore.data
        .catch { exception ->
            if (exception is IOException) emit(emptyPreferences()) else throw exception
        }
        .map { preferences ->
            AppSettings(
                dynamicColor = preferences[KEY_DYNAMIC_COLOR] ?: true,
                themePreset = preferences[KEY_THEME_PRESET]
                    ?.let { stored -> ThemePreset.entries.firstOrNull { it.name == stored } }
                    ?: ThemePreset.Default,
                keepEmptyNotes = preferences[KEY_KEEP_EMPTY_NOTES] ?: true,
                isLoaded = true,
            )
        }

    suspend fun setDynamicColor(enabled: Boolean) {
        dataStore.edit { it[KEY_DYNAMIC_COLOR] = enabled }
    }

    suspend fun setThemePreset(preset: ThemePreset) {
        dataStore.edit { it[KEY_THEME_PRESET] = preset.name }
    }

    suspend fun setKeepEmptyNotes(keep: Boolean) {
        dataStore.edit { it[KEY_KEEP_EMPTY_NOTES] = keep }
    }

    private companion object {
        val KEY_DYNAMIC_COLOR = booleanPreferencesKey("key_dynamic_color")
        val KEY_THEME_PRESET = stringPreferencesKey("key_theme_preset")
        val KEY_KEEP_EMPTY_NOTES = booleanPreferencesKey("key_keep_empty_notes")
    }
}
