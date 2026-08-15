package com.androidcompress.app.ui.home

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.androidcompress.app.data.AudioOption
import com.androidcompress.app.data.CompressJob
import com.androidcompress.app.data.EncodeSettings
import com.androidcompress.app.data.JobStatus
import com.androidcompress.app.data.JobType
import com.androidcompress.app.data.OutputMode
import com.androidcompress.app.data.SettingsJson
import com.androidcompress.app.di.AppContainer
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID

class HomeViewModel(private val container: AppContainer) : ViewModel() {
    val jobs: StateFlow<List<CompressJob>> = container.jobs.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val recording = container.recording.state
    val hasLastLog: StateFlow<Boolean> = container.jobs.observeAll()
        .map { !container.jobLogs.lastJobId().isNullOrBlank() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), !container.jobLogs.lastJobId().isNullOrBlank())

    fun createImportJob(
        uri: Uri,
        onReady: (String) -> Unit,
        onError: (String) -> Unit,
    ) {
        viewModelScope.launch {
            try {
                container.inputs.takePersistableAccess(uri)
                val info = container.probe.probe(uri)
                val id = UUID.randomUUID().toString()
                val prefs = container.prefs.current()
                val base = EncodeSettings.forPreset(prefs.defaultPreset, prefs.defaultEngine)
                val audioOut = !info.hasVideo
                val prepared = base.copy(
                    output = if (audioOut) OutputMode.AUDIO else OutputMode.VIDEO,
                    audio = if (audioOut && base.audio == AudioOption.MUTE) AudioOption.AAC_128 else base.audio,
                )
                val settings = SettingsJson.encode(prepared)
                container.jobs.upsert(
                    CompressJob(
                        id = id,
                        type = JobType.IMPORT,
                        status = JobStatus.READY,
                        sourceUri = uri.toString(),
                        outputUri = null,
                        displayName = info.displayName,
                        sourceBytes = info.bytes,
                        outputBytes = null,
                        durationMs = info.durationMs,
                        width = info.width,
                        height = info.height,
                        settingsJson = settings,
                        error = null,
                        createdAt = System.currentTimeMillis(),
                        finishedAt = null,
                    ),
                )
                runCatching { container.history.prune() }
                onReady(id)
            } catch (t: Throwable) {
                onError(t.message ?: "Unable to read that file")
            }
        }
    }

    fun consumeRecordingEvent() {
        container.recording.consumeFinished()
    }
}
