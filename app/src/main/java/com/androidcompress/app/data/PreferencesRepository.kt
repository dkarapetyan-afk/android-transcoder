package com.androidcompress.app.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.androidcompress.app.encode.EncodeStallTimeout
import com.androidcompress.app.util.runCatchingLog
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
    private val lastHardwareProfile = stringPreferencesKey("last_hardware_profile")
    private val lastRecordOptions = stringPreferencesKey("last_record_options")
    private val stallTimeoutSec = intPreferencesKey("stall_timeout_sec")
    private val twoPassStallTimeoutSec = intPreferencesKey("two_pass_stall_timeout_sec")

    val settings: Flow<UserSettings> = context.dataStore.data.map { prefs ->
        UserSettings(
            defaultPreset = prefs[defaultPreset]?.let {
                runCatchingLog(TAG, "unknown preset $it") { Preset.valueOf(it) }.getOrNull()
            } ?: Preset.BALANCED,
            rememberAdvanced = prefs[rememberAdvanced] ?: false,
            lastSettingsJson = prefs[lastSettings],
            lastRecordAudioMode = prefs[lastAudioMode]?.let {
                runCatchingLog(TAG, "unknown record audio $it") { RecordAudioMode.valueOf(it) }.getOrNull()
            } ?: RecordAudioMode.NONE,
            lastRecordResolution = prefs[lastRecordRes]?.let {
                runCatchingLog(TAG, "unknown record resolution $it") { RecordResolution.valueOf(it) }.getOrNull()
            } ?: RecordResolution.P1080,
            autoCompressAfterRecord = prefs[autoCompress] ?: false,
            encoderCapsJson = prefs[encoderCaps],
            deleteOriginalAfterEncode = prefs[deleteOriginal] ?: false,
            defaultEngine = prefs[defaultEngine]?.let {
                runCatchingLog(TAG, "unknown engine $it") { EncodeEngine.valueOf(it) }.getOrNull()
            } ?: EncodeEngine.FFMPEG,
            geminiApiKey = prefs[geminiApiKey].orEmpty(),
            stallTimeoutSec = prefs[stallTimeoutSec] ?: EncodeStallTimeout.DEFAULT_SEC,
            twoPassStallTimeoutSec = prefs[twoPassStallTimeoutSec]
                ?: EncodeStallTimeout.DEFAULT_TWO_PASS_SEC,
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

    suspend fun setStallTimeoutSec(seconds: Int) {
        context.dataStore.edit { it[stallTimeoutSec] = seconds }
    }

    suspend fun setTwoPassStallTimeoutSec(seconds: Int) {
        context.dataStore.edit { it[twoPassStallTimeoutSec] = seconds }
    }

    val lastHardwareProfileJson: Flow<String?> = context.dataStore.data.map { it[lastHardwareProfile] }

    suspend fun setLastHardwareProfileJson(json: String) {
        context.dataStore.edit { it[lastHardwareProfile] = json }
    }

    suspend fun recordOptionsJson(): String? = context.dataStore.data.map { it[lastRecordOptions] }.first()

    suspend fun setRecordOptionsJson(json: String) {
        context.dataStore.edit { it[lastRecordOptions] = json }
    }

    private companion object {
        const val TAG = "PrefsRepo"
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
    val stallTimeoutSec: Int = EncodeStallTimeout.DEFAULT_SEC,
    val twoPassStallTimeoutSec: Int = EncodeStallTimeout.DEFAULT_TWO_PASS_SEC,
)
