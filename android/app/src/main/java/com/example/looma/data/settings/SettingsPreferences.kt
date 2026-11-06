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
    val imageUploadTarget: String?,
    val s3Endpoint: String?,
    val s3Region: String?,
    val s3Bucket: String?,
    val s3AccessKey: String?,
    val s3SecretKey: String?,
    val s3PathPrefix: String?,
    val s3PublicBaseUrl: String?,
    val s3PathStyle: Boolean?,
    val s3PublicRead: Boolean?,
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
                    imageUploadTarget = preferences[KEY_IMAGE_UPLOAD_TARGET],
                    s3Endpoint = preferences[KEY_S3_ENDPOINT],
                    s3Region = preferences[KEY_S3_REGION],
                    s3Bucket = preferences[KEY_S3_BUCKET],
                    s3AccessKey = preferences[KEY_S3_ACCESS_KEY],
                    s3SecretKey = preferences[KEY_S3_SECRET_KEY],
                    s3PathPrefix = preferences[KEY_S3_PATH_PREFIX],
                    s3PublicBaseUrl = preferences[KEY_S3_PUBLIC_BASE_URL],
                    s3PathStyle = preferences[KEY_S3_PATH_STYLE],
                    s3PublicRead = preferences[KEY_S3_PUBLIC_READ],
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

    suspend fun saveImageUploadTarget(target: String) {
        dataStore.edit { it[KEY_IMAGE_UPLOAD_TARGET] = target }
    }

    suspend fun saveS3ImageBed(config: S3ImageBedConfig) {
        dataStore.edit {
            it[KEY_S3_ENDPOINT] = config.endpoint
            it[KEY_S3_REGION] = config.region
            it[KEY_S3_BUCKET] = config.bucket
            it[KEY_S3_ACCESS_KEY] = config.accessKey
            it[KEY_S3_SECRET_KEY] = config.secretKey
            it[KEY_S3_PATH_PREFIX] = config.pathPrefix
            it[KEY_S3_PUBLIC_BASE_URL] = config.publicBaseUrl
            it[KEY_S3_PATH_STYLE] = config.pathStyle
            it[KEY_S3_PUBLIC_READ] = config.publicRead
        }
    }

    companion object {
        val KEY_DYNAMIC_COLOR = booleanPreferencesKey("key_dynamic_color")
        val KEY_THEME_PRESET = stringPreferencesKey("key_theme_preset")
        val KEY_KEEP_EMPTY_NOTES = booleanPreferencesKey("key_keep_empty_notes")
        val KEY_TOOLBAR_ORDER = stringPreferencesKey("key_toolbar_order")
        val KEY_IMAGE_UPLOAD_TARGET = stringPreferencesKey("key_image_upload_target")
        val KEY_S3_ENDPOINT = stringPreferencesKey("key_s3_endpoint")
        val KEY_S3_REGION = stringPreferencesKey("key_s3_region")
        val KEY_S3_BUCKET = stringPreferencesKey("key_s3_bucket")
        val KEY_S3_ACCESS_KEY = stringPreferencesKey("key_s3_access_key")
        val KEY_S3_SECRET_KEY = stringPreferencesKey("key_s3_secret_key")
        val KEY_S3_PATH_PREFIX = stringPreferencesKey("key_s3_path_prefix")
        val KEY_S3_PUBLIC_BASE_URL = stringPreferencesKey("key_s3_public_base_url")
        val KEY_S3_PATH_STYLE = booleanPreferencesKey("key_s3_path_style")
        val KEY_S3_PUBLIC_READ = booleanPreferencesKey("key_s3_public_read")
    }
}
