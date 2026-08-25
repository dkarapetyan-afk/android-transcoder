package com.androidcompress.app.asr

import kotlin.math.roundToInt

data class CaptionNoticeTick(
    val percent: Int,
    val message: String,
)

/** Drops Whisper's ~32 Hz ticks so the captions notification updates at most every [minIntervalMs]. */
class CaptionNoticeThrottle(
    private val minIntervalMs: Long = 200L,
) {
    private var lastAt = 0L
    private var lastPercent = -1
    private var lastMessage: String? = null
    private var started = false

    fun accept(nowMs: Long, fraction: Float, message: String): CaptionNoticeTick? {
        val percent = (fraction.coerceIn(0f, 1f) * 100f).roundToInt().coerceIn(0, 100)
        if (!started) {
            started = true
            lastAt = nowMs
            lastPercent = percent
            lastMessage = message
            return CaptionNoticeTick(percent, message)
        }
        val messageChanged = message != lastMessage
        val percentChanged = percent != lastPercent
        if (!messageChanged && !percentChanged) return null
        val elapsed = nowMs - lastAt
        val force = percent == 0 || percent == 100 || messageChanged
        if (!force && elapsed < minIntervalMs) return null
        lastAt = nowMs
        lastPercent = percent
        lastMessage = message
        return CaptionNoticeTick(percent, message)
    }

    fun reset() {
        started = false
        lastAt = 0L
        lastPercent = -1
        lastMessage = null
    }
}
