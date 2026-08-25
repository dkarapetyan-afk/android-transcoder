package com.androidcompress.app.asr

import java.util.Locale

object SrtWriter {
    fun render(cues: List<CaptionCue>): String {
        if (cues.isEmpty()) return ""
        return buildString {
            cues.forEachIndexed { index, cue ->
                if (index > 0) append('\n')
                append(index + 1)
                append('\n')
                append(timestamp(cue.startSec))
                append(" --> ")
                append(timestamp(cue.endSec))
                append('\n')
                append(cue.text.trim())
                append("\n")
            }
        }
    }

    fun timestamp(seconds: Double): String {
        val totalMs = (seconds.coerceAtLeast(0.0) * 1000.0).toLong()
        val hours = totalMs / 3_600_000
        val minutes = (totalMs % 3_600_000) / 60_000
        val secs = (totalMs % 60_000) / 1000
        val ms = totalMs % 1000
        return String.format(Locale.US, "%02d:%02d:%02d,%03d", hours, minutes, secs, ms)
    }

    fun usableText(raw: String): String? {
        val text = raw.trim().replace(Regex("\\s+"), " ")
        if (text.isEmpty()) return null
        val folded = text.lowercase(Locale.US).trim('.', ',', '!', '?', ' ')
        if (folded.isEmpty() || folded == "the") return null
        return text
    }
}
