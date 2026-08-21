package com.androidcompress.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.androidcompress.app.data.EncodeEngine
import com.androidcompress.app.data.Preset
import com.androidcompress.app.data.UserSettings
import com.androidcompress.app.di.AppContainer
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(private val container: AppContainer) : ViewModel() {
    val settings = container.prefs.settings.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        UserSettings(
            defaultPreset = Preset.BALANCED,
            rememberAdvanced = false,
            lastSettingsJson = null,
            lastRecordAudioMode = com.androidcompress.app.data.RecordAudioMode.NONE,
            lastRecordResolution = com.androidcompress.app.data.RecordResolution.P1080,
            autoCompressAfterRecord = false,
            encoderCapsJson = null,
            deleteOriginalAfterEncode = false,
            defaultEngine = EncodeEngine.FFMPEG,
            geminiApiKey = "",
        ),
    )

    fun setPreset(preset: Preset) {
        viewModelScope.launch { container.prefs.setDefaultPreset(preset) }
    }

    fun setRememberAdvanced(value: Boolean) {
        viewModelScope.launch { container.prefs.setRememberAdvanced(value) }
    }

    fun setAutoCompress(value: Boolean) {
        viewModelScope.launch { container.prefs.setAutoCompressAfterRecord(value) }
    }

    fun setDeleteOriginal(value: Boolean) {
        viewModelScope.launch { container.prefs.setDeleteOriginalAfterEncode(value) }
    }

    fun setEngine(engine: EncodeEngine) {
        viewModelScope.launch { container.prefs.setDefaultEngine(engine) }
    }

    fun setGeminiApiKey(value: String) {
        viewModelScope.launch { container.prefs.setGeminiApiKey(value) }
    }

    fun setStallTimeoutSec(seconds: Int) {
        viewModelScope.launch { container.prefs.setStallTimeoutSec(seconds) }
    }

    fun setTwoPassStallTimeoutSec(seconds: Int) {
        viewModelScope.launch { container.prefs.setTwoPassStallTimeoutSec(seconds) }
    }
}
