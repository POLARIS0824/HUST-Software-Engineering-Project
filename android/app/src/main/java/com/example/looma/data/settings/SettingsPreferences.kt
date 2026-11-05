package com.example.looma.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "looma_settings_preferences")

data class SettingsPreferencesData(
    val dynamicColor: Boolean?,
    val themePreset: String?,
    val keepEmptyNotes: Boolean?,
    val toolbarOrderString: String?,
)

class SettingsPreferences(context: Context) {

    private val dataStore = context.applicationContext.settingsDataStore

    val settingsDataFlow: Flow<SettingsPreferencesData> =
        dataStore.data
            .catch { exception ->
                if (exception is IOException) {
                    emit(emptyPreferences())
                } else {
                    throw exception
                }
            }
            .map { preferences ->
                SettingsPreferencesData(
                    dynamicColor = preferences[KEY_DYNAMIC_COLOR],
                    themePreset = preferences[KEY_THEME_PRESET],
                    keepEmptyNotes = preferences[KEY_KEEP_EMPTY_NOTES],
                    toolbarOrderString = preferences[KEY_TOOLBAR_ORDER],
                )
            }

    suspend fun saveToolbarOrder(orderString: String) {
        dataStore.edit { it[KEY_TOOLBAR_ORDER] = orderString }
    }
    suspend fun saveDynamicColor(enabled: Boolean) {
        dataStore.edit { it[KEY_DYNAMIC_COLOR] = enabled }
    }
    suspend fun saveThemePreset(presetName: String) {
        dataStore.edit { it[KEY_THEME_PRESET] = presetName }
    }
    suspend fun saveKeepEmptyNotes(keep: Boolean) {
        dataStore.edit { it[KEY_KEEP_EMPTY_NOTES] = keep }
    }

    companion object {
        val KEY_DYNAMIC_COLOR = booleanPreferencesKey("key_dynamic_color")
        val KEY_THEME_PRESET = stringPreferencesKey("key_theme_preset")
        val KEY_KEEP_EMPTY_NOTES = booleanPreferencesKey("key_keep_empty_notes")
        val KEY_TOOLBAR_ORDER = stringPreferencesKey("key_toolbar_order")
    }
}