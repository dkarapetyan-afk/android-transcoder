package com.androidcompress.app.capture

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class RecordingState(
    val active: Boolean = false,
    val jobId: String? = null,
    val startedAt: Long = 0L,
    val finishedJobId: String? = null,
    val error: String? = null,
)

class RecordingStore {
    private val _state = MutableStateFlow(RecordingState())
    val state: StateFlow<RecordingState> = _state.asStateFlow()

    fun start(jobId: String) {
        _state.value = RecordingState(active = true, jobId = jobId, startedAt = System.currentTimeMillis())
    }

    fun finish(jobId: String) {
        _state.value = RecordingState(active = false, finishedJobId = jobId)
    }

    fun fail(message: String) {
        _state.value = RecordingState(active = false, error = message)
    }

    fun consumeFinished() {
        _state.value = _state.value.copy(finishedJobId = null, error = null)
    }
}
