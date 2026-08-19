package com.androidcompress.app.ui.hardware

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.androidcompress.app.di.AppContainer
import com.androidcompress.app.encode.HardwareCodecProfiler
import com.androidcompress.app.encode.HardwareProfileJson
import com.androidcompress.app.encode.HardwareProfileReport
import com.androidcompress.app.encode.HardwareProgress
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

data class HardwareTestUi(
    val running: Boolean = false,
    val progress: HardwareProgress? = null,
    val report: HardwareProfileReport? = null,
    val error: String? = null,
)

class HardwareTestViewModel(private val container: AppContainer) : ViewModel() {
    private val _ui = MutableStateFlow(HardwareTestUi())
    val ui = _ui.asStateFlow()

    private val cancelled = AtomicBoolean(false)
    private var work: Job? = null
    private val profiler = HardwareCodecProfiler(container.appContext, container.ffmpeg, container.media3)

    init {
        viewModelScope.launch {
            container.prefs.lastHardwareProfileJson.collect { json ->
                if (_ui.value.running) return@collect
                val report = HardwareProfileJson.decode(json)
                if (report != null) {
                    _ui.update { it.copy(report = report) }
                }
            }
        }
    }

    fun start() {
        if (_ui.value.running) return
        cancelled.set(false)
        work = viewModelScope.launch {
            _ui.update { it.copy(running = true, error = null, progress = null) }
            val report = runCatching {
                profiler.run(
                    caps = container.encoderCapabilities(),
                    onProgress = { step -> _ui.update { it.copy(progress = step) } },
                    cancelled = cancelled,
                )
            }.onFailure { error ->
                _ui.update {
                    it.copy(running = false, error = error.message, progress = null)
                }
            }.getOrNull() ?: return@launch
            if (!report.cancelled) {
                container.prefs.setLastHardwareProfileJson(HardwareProfileJson.encode(report))
            }
            _ui.update { it.copy(running = false, report = report, progress = null) }
        }
    }

    fun cancel() {
        cancelled.set(true)
    }

    override fun onCleared() {
        cancelled.set(true)
        work?.cancel()
        super.onCleared()
    }
}
