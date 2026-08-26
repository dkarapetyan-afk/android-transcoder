package com.androidcompress.app.ui.record

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraManager
import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.androidcompress.app.capture.CaptureApp
import com.androidcompress.app.capture.CaptureApps
import com.androidcompress.app.capture.RecordOptions
import com.androidcompress.app.capture.RecordVideoCodec
import com.androidcompress.app.capture.RecordingSession
import com.androidcompress.app.capture.ScreenRecordService
import com.androidcompress.app.capture.TapHighlightService
import com.androidcompress.app.data.EncoderCapabilities
import com.androidcompress.app.data.RecordAudioMode
import com.androidcompress.app.data.RecordResolution
import com.androidcompress.app.di.AppContainer
import com.androidcompress.app.util.runCatchingLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class RecordUiState(
    val options: RecordOptions = RecordOptions(),
    val starting: Boolean = false,
    val apps: List<CaptureApp> = emptyList(),
    val appsLoaded: Boolean = false,
    val hasHevc: Boolean = false,
    val hasAv1: Boolean = false,
    val hasFrontCamera: Boolean = false,
    val hasBackCamera: Boolean = false,
    val tapsServiceEnabled: Boolean = TapHighlightService.isEnabled(),
)

class RecordViewModel(private val container: AppContainer) : ViewModel() {
    private val _ui = MutableStateFlow(RecordUiState())
    val ui = _ui.asStateFlow()
    val recording = container.recording.state

    init {
        viewModelScope.launch {
            val prefs = container.prefs.current()
            val stored = container.prefs.recordOptionsJson()
            val options = RecordOptions.fromJson(stored).let { parsed ->
                if (stored.isNullOrBlank()) {
                    parsed.copy(
                        audioMode = prefs.lastRecordAudioMode,
                        resolution = prefs.lastRecordResolution,
                    )
                } else {
                    parsed
                }
            }
            val caps = runCatchingLog("RecordVM", "encoder caps") { container.encoderCapabilities() }
                .getOrElse { EncoderCapabilities() }
            val cameras = container.appContext.getSystemService(CameraManager::class.java)
            _ui.value = _ui.value.copy(
                options = options,
                hasHevc = caps.hasHevcMediaCodec,
                hasAv1 = caps.hasAv1MediaCodec && Build.VERSION.SDK_INT >= 33,
                hasFrontCamera = cameras?.let { CaptureApps.hasFrontCamera(it) } == true,
                hasBackCamera = cameras?.let { CaptureApps.hasBackCamera(it) } == true,
                tapsServiceEnabled = TapHighlightService.isEnabled(),
            )
        }
    }

    fun refreshTapsService() {
        _ui.value = _ui.value.copy(tapsServiceEnabled = TapHighlightService.isEnabled())
    }

    fun update(transform: (RecordOptions) -> RecordOptions) {
        val next = transform(_ui.value.options)
        _ui.value = _ui.value.copy(options = next)
        viewModelScope.launch {
            container.prefs.setRecordOptionsJson(next.toJson())
            container.prefs.setRecordAudioMode(next.audioMode)
            container.prefs.setRecordResolution(next.resolution)
        }
    }

    fun setAudioMode(mode: RecordAudioMode) = update { it.copy(audioMode = mode) }

    fun setResolution(resolution: RecordResolution) = update { it.copy(resolution = resolution) }

    fun loadApps() {
        if (_ui.value.appsLoaded) return
        viewModelScope.launch {
            val apps = withContext(Dispatchers.IO) {
                CaptureApps.launchers(container.appContext.packageManager)
            }
            _ui.value = _ui.value.copy(apps = apps, appsLoaded = true)
        }
    }

    fun onConsentResult(context: Context, resultCode: Int, data: Intent?) {
        if (resultCode != Activity.RESULT_OK || data == null) {
            _ui.value = _ui.value.copy(starting = false)
            return
        }
        viewModelScope.launch {
            RecordingSession.startAfterConsent(
                context = context,
                container = container,
                resultCode = resultCode,
                data = data,
                options = _ui.value.options,
            )
            _ui.value = _ui.value.copy(starting = false)
        }
    }

    fun stop(context: Context) {
        ScreenRecordService.stop(context)
    }

    fun pause(context: Context) {
        ScreenRecordService.pause(context)
    }

    fun resume(context: Context) {
        ScreenRecordService.resume(context)
    }

    fun bookmark(context: Context) {
        ScreenRecordService.bookmark(context)
    }

    fun markStarting(starting: Boolean = true) {
        _ui.value = _ui.value.copy(starting = starting)
    }

    fun supportsInternalAudio(): Boolean = Build.VERSION.SDK_INT >= 29

    fun availableCodecs(): List<RecordVideoCodec> = buildList {
        add(RecordVideoCodec.H264)
        if (_ui.value.hasHevc) add(RecordVideoCodec.HEVC)
        if (_ui.value.hasAv1) add(RecordVideoCodec.AV1)
    }

    fun hasAnyCamera(): Boolean = _ui.value.hasFrontCamera || _ui.value.hasBackCamera
}
