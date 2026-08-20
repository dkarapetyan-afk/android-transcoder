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
    val notice: String? = null,
    val openResult: Boolean = false,
    val phase: RecordPhase = RecordPhase.IDLE,
    val countdownRemaining: Int = 0,
) {
    val capturing: Boolean get() = active && phase == RecordPhase.RECORDING && !saving

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

    fun prepare(jobId: String, phase: RecordPhase) {
        _state.value = RecordingState(active = true, jobId = jobId, phase = phase)
    }

    fun setPhase(phase: RecordPhase, countdownRemaining: Int = 0) {
        val cur = _state.value
        if (!cur.active || cur.saving) return
        _state.value = cur.copy(phase = phase, countdownRemaining = countdownRemaining.coerceAtLeast(0))
    }

    fun start(jobId: String) {
        _state.value = RecordingState(
            active = true,
            jobId = jobId,
            startedAt = System.currentTimeMillis(),
            phase = RecordPhase.RECORDING,
        )
    }

    fun startCapturing() {
        val cur = _state.value
        if (!cur.active || cur.saving) return
        _state.value = cur.copy(
            phase = RecordPhase.RECORDING,
            startedAt = System.currentTimeMillis(),
            countdownRemaining = 0,
            paused = false,
            pausedAccumulatedMs = 0L,
            pauseStartedAt = 0L,
        )
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
            phase = RecordPhase.SAVING,
            pauseStartedAt = 0L,
            pausedAccumulatedMs = cur.pausedAccumulatedMs + extra,
            countdownRemaining = 0,
        )
    }

    fun finish(jobId: String, openResult: Boolean = false, notice: String? = null) {
        _state.value = RecordingState(
            finishedJobId = jobId,
            openResult = openResult,
            notice = notice,
        )
    }

    fun fail(message: String) {
        _state.value = RecordingState(error = message)
    }

    fun consumeFinished() {
        _state.value = RecordingState()
    }
}
