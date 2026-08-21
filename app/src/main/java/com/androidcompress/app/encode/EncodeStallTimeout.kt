package com.androidcompress.app.encode

/** Inactivity window before a running FFmpeg encode is treated as hung. */
object EncodeStallTimeout {
    const val DEFAULT_SEC = 20
    const val DEFAULT_TWO_PASS_SEC = 120

    fun toMs(seconds: Int): Long = seconds * 1000L
}
