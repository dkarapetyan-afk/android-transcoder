package com.androidcompress.app.encode

import com.androidcompress.app.util.parseDurationMs
import com.androidcompress.app.util.runCatchingLog
import java.io.File

/**
 * Progress from FFmpeg-Kit stats, stderr, and `-progress` dumps.
 * Null-muxer pass 1 reports `time=N/A` and FFmpeg overwrites stderr with `\r`,
 * so the log callback often never fires until the pass ends.
 */
object FfmpegEncodeProgress {
    private const val TAG = "FfmpegProgress"
    private val FRAME = Regex("""frame=\s*(\d+)""")
    private val TIME = Regex("""time=(-?\d+:\d{2}:\d{2}(?:\.\d+)?)""")

    data class Snapshot(
        val timeMs: Long? = null,
        val frame: Int? = null,
        val continuing: Boolean = false,
    )

    fun parseLine(line: String): Snapshot? {
        val timeMs = TIME.findAll(line).lastOrNull()?.groupValues?.get(1)?.let { token ->
            parseDurationMs(token.removePrefix("-"))
        }
        val frame = FRAME.findAll(line).lastOrNull()?.groupValues?.get(1)?.toIntOrNull()
        if (timeMs == null && frame == null) return null
        return Snapshot(timeMs = timeMs, frame = frame)
    }

    fun parseDump(text: String): Snapshot? {
        if (text.isBlank()) return null
        var frame: Int? = null
        var timeMs: Long? = null
        var continuing = false
        var sawProgress = false
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            val eq = line.indexOf('=')
            if (eq <= 0) continue
            val key = line.substring(0, eq)
            val value = line.substring(eq + 1)
            when (key) {
                "frame" -> value.toIntOrNull()?.let { frame = it }
                "out_time_us" -> value.toLongOrNull()?.takeIf { it > 0L }?.let { timeMs = it / 1000L }
                "out_time" -> if (!value.equals("N/A", ignoreCase = true)) {
                    parseDurationMs(value.removePrefix("-"))?.takeIf { it > 0L }?.let { timeMs = it }
                }
                "progress" -> {
                    sawProgress = true
                    continuing = value.equals("continue", ignoreCase = true)
                }
            }
        }
        if (frame == null && timeMs == null && !sawProgress) return null
        return Snapshot(timeMs = timeMs, frame = frame, continuing = continuing)
    }

    fun readDump(file: File, maxBytes: Int = 8192): String {
        if (!file.exists()) return ""
        val length = file.length()
        if (length <= 0L) return ""
        return runCatchingLog(TAG, "read progress dump") {
            file.inputStream().use { input ->
                val skip = (length - maxBytes).coerceAtLeast(0L)
                if (skip > 0L) input.skip(skip)
                input.readBytes().toString(Charsets.US_ASCII)
            }
        }.getOrDefault("")
    }

    fun withProgressArg(args: List<String>, progressPath: String): List<String> {
        if (args.isEmpty() || progressPath.isBlank()) return args
        val existing = args.indexOf("-progress")
        if (existing >= 0) {
            val next = args.toMutableList()
            if (existing + 1 < next.size && !next[existing + 1].startsWith("-")) {
                next[existing + 1] = progressPath
            } else {
                next.add(existing + 1, progressPath)
            }
            return next
        }
        return args.dropLast(1) + listOf("-progress", progressPath) + args.last()
    }

    fun timeMs(
        statsTimeMs: Long,
        videoFrameNumber: Int,
        fps: Float,
        logTimeMs: Long = 0L,
        logFrame: Int = 0,
    ): Long {
        if (statsTimeMs > 0L) return statsTimeMs
        if (logTimeMs > 0L) return logTimeMs
        val frames = maxOf(videoFrameNumber, logFrame)
        if (frames > 0 && fps > 0.1f) {
            return (frames * 1000.0 / fps).toLong()
        }
        return 0L
    }

    fun wallClockTimeMs(startedAt: Long, durationMs: Long, now: Long): Long {
        if (durationMs <= 0L) return 0L
        val cap = (durationMs * 0.95).toLong().coerceAtLeast(1L)
        return (now - startedAt).coerceIn(0L, cap)
    }
}
