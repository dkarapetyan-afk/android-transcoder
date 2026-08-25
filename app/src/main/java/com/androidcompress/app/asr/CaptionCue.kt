package com.androidcompress.app.asr

data class CaptionCue(
    val startSec: Double,
    val endSec: Double,
    val text: String,
) {
    val startMs: Long get() = (startSec * 1000.0).toLong().coerceAtLeast(0L)
    val endMs: Long get() = (endSec * 1000.0).toLong().coerceAtLeast(startMs + 1L)
}
