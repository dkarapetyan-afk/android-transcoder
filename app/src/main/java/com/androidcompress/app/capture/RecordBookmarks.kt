package com.androidcompress.app.capture

import java.util.Locale
import kotlin.math.abs

data class RecordSegment(
    val startMs: Long,
    val endMs: Long,
    val index: Int,
    val total: Int,
) {
    val durationMs: Long get() = (endMs - startMs).coerceAtLeast(0L)
}

object RecordBookmarks {
    const val MIN_SEGMENT_MS = 400L

    fun normalizedMarks(markers: List<Long>, durationMs: Long): List<Long> =
        markers.filter { it in 1 until durationMs }.distinct().sorted()

    fun segments(markers: List<Long>, durationMs: Long, minMs: Long = MIN_SEGMENT_MS): List<RecordSegment> {
        if (durationMs < minMs) return emptyList()
        val bounds = (normalizedMarks(markers, durationMs) + durationMs).distinct()
        var start = 0L
        val raw = mutableListOf<Pair<Long, Long>>()
        for (end in bounds) {
            if (end - start >= minMs) raw += start to end
            start = end
        }
        if (raw.size <= 1) return emptyList()
        return raw.mapIndexed { i, pair ->
            RecordSegment(pair.first, pair.second, i + 1, raw.size)
        }
    }

    fun ffmetadata(markers: List<Long>, durationMs: Long): String? {
        val parts = segments(markers, durationMs).ifEmpty {
            val marks = normalizedMarks(markers, durationMs)
            if (marks.isEmpty() || durationMs < MIN_SEGMENT_MS) return null
            val bounds = marks + durationMs
            var start = 0L
            val raw = mutableListOf<Pair<Long, Long>>()
            for (end in bounds) {
                if (end - start >= MIN_SEGMENT_MS) raw += start to end
                start = end
            }
            raw.mapIndexed { i, pair -> RecordSegment(pair.first, pair.second, i + 1, raw.size) }
        }
        if (parts.isEmpty()) return null
        return buildString {
            append(";FFMETADATA1\n")
            parts.forEach { seg ->
                append("[CHAPTER]\n")
                append("TIMEBASE=1/1000\n")
                append("START=${seg.startMs}\n")
                append("END=${seg.endMs}\n")
                append("title=Chapter ${seg.index}\n")
            }
        }
    }

    fun seconds(ms: Long): String = String.format(Locale.US, "%.3f", ms.coerceAtLeast(0L) / 1000.0)

    fun nearExisting(markers: List<Long>, timeMs: Long, windowMs: Long = 400L): Boolean =
        markers.any { abs(it - timeMs) < windowMs }
}
