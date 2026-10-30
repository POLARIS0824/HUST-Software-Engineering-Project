package com.example.looma.data.vault

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

private val Context.vaultDataStore: DataStore<Preferences> by preferencesDataStore(name = "looma_vault_settings")

data class VaultPreferencesData(
    val id: String?,
    val name: String?,
    val path: String?,
)

class VaultPreferences(context: Context) {

    private val dataStore = context.applicationContext.vaultDataStore

    val vaultDataFlow: Flow<VaultPreferencesData> =
        dataStore.data
            .catch { exception ->
                if (exception is IOException) {
                    emit(emptyPreferences())
                } else {
                    throw exception
                }
            }
            .map { preferences ->
                VaultPreferencesData(
                    id = preferences[KEY_VAULT_ID],
                    name = preferences[KEY_VAULT_NAME],
                    path = preferences[KEY_VAULT_PATH],
                )
            }

    suspend fun saveVault(
        id: String,
        name: String,
        path: String,
    ) {
        dataStore.edit { preferences ->
            preferences[KEY_VAULT_ID] = id
            preferences[KEY_VAULT_NAME] = name
            preferences[KEY_VAULT_PATH] = path
        }
    }

    suspend fun clearVault() {
        dataStore.edit { preferences ->
            preferences.remove(KEY_VAULT_ID)
            preferences.remove(KEY_VAULT_NAME)
            preferences.remove(KEY_VAULT_PATH)
        }
    }

    companion object {
        private val KEY_VAULT_ID = stringPreferencesKey("key_vault_id")
        private val KEY_VAULT_NAME = stringPreferencesKey("key_vault_name")
        private val KEY_VAULT_PATH = stringPreferencesKey("key_vault_path")
    }
}
