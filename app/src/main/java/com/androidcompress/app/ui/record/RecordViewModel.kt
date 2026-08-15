package com.androidcompress.app.ui.record

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.androidcompress.app.capture.ScreenRecordService
import com.androidcompress.app.data.CompressJob
import com.androidcompress.app.data.EncodeSettings
import com.androidcompress.app.data.JobStatus
import com.androidcompress.app.data.JobType
import com.androidcompress.app.data.RecordAudioMode
import com.androidcompress.app.data.RecordResolution
import com.androidcompress.app.data.SettingsJson
import com.androidcompress.app.di.AppContainer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

data class RecordUiState(
    val audioMode: RecordAudioMode = RecordAudioMode.NONE,
    val resolution: RecordResolution = RecordResolution.P1080,
    val starting: Boolean = false,
)

class RecordViewModel(private val container: AppContainer) : ViewModel() {
    private val _ui = MutableStateFlow(RecordUiState())
    val ui = _ui.asStateFlow()
    val recording = container.recording.state

    init {
        viewModelScope.launch {
            val prefs = container.prefs.current()
            _ui.value = _ui.value.copy(
                audioMode = prefs.lastRecordAudioMode,
                resolution = prefs.lastRecordResolution,
            )
        }
    }

    fun setAudioMode(mode: RecordAudioMode) {
        _ui.value = _ui.value.copy(audioMode = mode)
        viewModelScope.launch { container.prefs.setRecordAudioMode(mode) }
    }

    fun setResolution(resolution: RecordResolution) {
        _ui.value = _ui.value.copy(resolution = resolution)
        viewModelScope.launch { container.prefs.setRecordResolution(resolution) }
    }

    fun onConsentResult(context: Context, resultCode: Int, data: Intent?) {
        if (resultCode != Activity.RESULT_OK || data == null) {
            _ui.value = _ui.value.copy(starting = false)
            return
        }
        viewModelScope.launch {
            val id = UUID.randomUUID().toString()
            val prefs = container.prefs.current()
            container.jobs.upsert(
                CompressJob(
                    id = id,
                    type = JobType.RECORD,
                    status = JobStatus.DRAFT,
                    sourceUri = "",
                    outputUri = null,
                    displayName = "Screen recording",
                    sourceBytes = 0,
                    outputBytes = null,
                    durationMs = 0,
                    width = 0,
                    height = 0,
                    settingsJson = SettingsJson.encode(EncodeSettings.forPreset(prefs.defaultPreset, prefs.defaultEngine)),
                    error = null,
                    createdAt = System.currentTimeMillis(),
                    finishedAt = null,
                ),
            )
            runCatching { container.history.prune() }
            ScreenRecordService.start(
                context = context,
                jobId = id,
                resultCode = resultCode,
                data = data,
                audioMode = _ui.value.audioMode,
                resolution = _ui.value.resolution,
            )
            _ui.value = _ui.value.copy(starting = false)
        }
    }

    fun stop(context: Context) {
        ScreenRecordService.stop(context)
    }

    fun markStarting() {
        _ui.value = _ui.value.copy(starting = true)
    }

    fun supportsInternalAudio(): Boolean = Build.VERSION.SDK_INT >= 29
}
