package com.androidcompress.app.capture

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class RecordingState(
    val active: Boolean = false,
    val paused: Boolean = false,
    val saving: Boolean = false,
    val jobId: String? = null,
    val startedAt: Long = 0L,
    val pausedAccumulatedMs: Long = 0L,
    val pauseStartedAt: Long = 0L,
    val finishedJobId: String? = null,
    val error: String? = null,
) {
    fun elapsedMs(now: Long = System.currentTimeMillis()): Long {
        if (!active || startedAt <= 0L) return 0L
        val extraPaused = if (paused && pauseStartedAt > 0L) {
            (now - pauseStartedAt).coerceAtLeast(0L)
        } else {
            0L
        }
        return (now - startedAt - pausedAccumulatedMs - extraPaused).coerceAtLeast(0L)
    }
}

class RecordingStore {
    private val _state = MutableStateFlow(RecordingState())
    val state: StateFlow<RecordingState> = _state.asStateFlow()

    fun start(jobId: String) {
        _state.value = RecordingState(active = true, jobId = jobId, startedAt = System.currentTimeMillis())
    }

    fun setPaused(paused: Boolean, now: Long = System.currentTimeMillis()) {
        val cur = _state.value
        if (!cur.active || cur.saving || cur.paused == paused) return
        _state.value = if (paused) {
            cur.copy(paused = true, pauseStartedAt = now)
        } else {
            val extra = if (cur.pauseStartedAt > 0L) (now - cur.pauseStartedAt).coerceAtLeast(0L) else 0L
            cur.copy(
                paused = false,
                pauseStartedAt = 0L,
                pausedAccumulatedMs = cur.pausedAccumulatedMs + extra,
            )
        }
    }

    fun markSaving() {
        val cur = _state.value
        if (!cur.active) return
        val now = System.currentTimeMillis()
        val extra = if (cur.paused && cur.pauseStartedAt > 0L) {
            (now - cur.pauseStartedAt).coerceAtLeast(0L)
        } else {
            0L
        }
        _state.value = cur.copy(
            paused = false,
            saving = true,
            pauseStartedAt = 0L,
            pausedAccumulatedMs = cur.pausedAccumulatedMs + extra,
        )
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
