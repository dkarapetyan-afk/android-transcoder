package com.androidcompress.app.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "settings")

class PreferencesRepository(private val context: Context) {

    private val defaultPreset = stringPreferencesKey("default_preset")
    private val rememberAdvanced = booleanPreferencesKey("remember_advanced")
    private val lastSettings = stringPreferencesKey("last_settings")
    private val lastAudioMode = stringPreferencesKey("last_record_audio")
    private val lastRecordRes = stringPreferencesKey("last_record_res")
    private val autoCompress = booleanPreferencesKey("auto_compress_after_record")
    private val encoderCaps = stringPreferencesKey("encoder_caps")
    private val deleteOriginal = booleanPreferencesKey("delete_original_after_encode")
    private val defaultEngine = stringPreferencesKey("default_engine")
    private val geminiApiKey = stringPreferencesKey("gemini_api_key")

    val settings: Flow<UserSettings> = context.dataStore.data.map { prefs ->
        UserSettings(
            defaultPreset = prefs[defaultPreset]?.let { runCatching { Preset.valueOf(it) }.getOrNull() }
                ?: Preset.BALANCED,
            rememberAdvanced = prefs[rememberAdvanced] ?: false,
            lastSettingsJson = prefs[lastSettings],
            lastRecordAudioMode = prefs[lastAudioMode]?.let {
                runCatching { RecordAudioMode.valueOf(it) }.getOrNull()
            } ?: RecordAudioMode.NONE,
            lastRecordResolution = prefs[lastRecordRes]?.let {
                runCatching { RecordResolution.valueOf(it) }.getOrNull()
            } ?: RecordResolution.P1080,
            autoCompressAfterRecord = prefs[autoCompress] ?: false,
            encoderCapsJson = prefs[encoderCaps],
            deleteOriginalAfterEncode = prefs[deleteOriginal] ?: false,
            defaultEngine = prefs[defaultEngine]?.let {
                runCatching { EncodeEngine.valueOf(it) }.getOrNull()
            } ?: EncodeEngine.FFMPEG,
            geminiApiKey = prefs[geminiApiKey].orEmpty(),
        )
    }

    suspend fun current(): UserSettings = settings.first()

    suspend fun setDefaultPreset(preset: Preset) {
        context.dataStore.edit { it[defaultPreset] = preset.name }
    }

    suspend fun setRememberAdvanced(value: Boolean) {
        context.dataStore.edit { it[rememberAdvanced] = value }
    }

    suspend fun setLastSettingsJson(json: String) {
        context.dataStore.edit { it[lastSettings] = json }
    }

    suspend fun setRecordAudioMode(mode: RecordAudioMode) {
        context.dataStore.edit { it[lastAudioMode] = mode.name }
    }

    suspend fun setRecordResolution(resolution: RecordResolution) {
        context.dataStore.edit { it[lastRecordRes] = resolution.name }
    }

    suspend fun setAutoCompressAfterRecord(value: Boolean) {
        context.dataStore.edit { it[autoCompress] = value }
    }

    suspend fun setEncoderCapsJson(json: String) {
        context.dataStore.edit { it[encoderCaps] = json }
    }

    suspend fun setDeleteOriginalAfterEncode(value: Boolean) {
        context.dataStore.edit { it[deleteOriginal] = value }
    }

    suspend fun setDefaultEngine(engine: EncodeEngine) {
        context.dataStore.edit { it[defaultEngine] = engine.name }
    }

    suspend fun setGeminiApiKey(value: String) {
        context.dataStore.edit { it[geminiApiKey] = value.trim() }
    }
}

data class UserSettings(
    val defaultPreset: Preset,
    val rememberAdvanced: Boolean,
    val lastSettingsJson: String?,
    val lastRecordAudioMode: RecordAudioMode,
    val lastRecordResolution: RecordResolution,
    val autoCompressAfterRecord: Boolean,
    val encoderCapsJson: String?,
    val deleteOriginalAfterEncode: Boolean = false,
    val defaultEngine: EncodeEngine = EncodeEngine.FFMPEG,
    val geminiApiKey: String = "",
)
