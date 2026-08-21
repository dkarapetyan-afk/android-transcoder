package com.androidcompress.app.encode

/** Interprets FFmpeg-Kit session text after a process that still exited 0. */
object FfmpegSessionLogs {

    fun encodedNoMedia(logs: String): Boolean {
        if (logs.contains("nothing was encoded", ignoreCase = true)) return true
        if (!logs.contains("Stream mapping:", ignoreCase = true)) return false
        return VIDEO_ZERO.containsMatchIn(logs)
    }

    private val VIDEO_ZERO = Regex("""video:\s*0kB""", RegexOption.IGNORE_CASE)
}
