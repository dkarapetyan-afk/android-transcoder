package com.androidcompress.app.encode

import kotlin.math.roundToInt

/**
 * Fit-to-size bitrate:
 *
 *     video_bps = (targetBytes × 8 / duration_sec) − audio_bps − muxer_overhead_bps
 *
 * Muxer overhead is the container header ([MUXER_OVERHEAD_BYTES], the same 64 KB used by
 * [FfmpegCommandBuilder.estimateOutputBytes]) plus a 4% encoder-overshoot allowance so
 * Discord / WhatsApp / Gmail still accept the file.
 */
object TargetSizeBitrate {
    const val MUXER_OVERHEAD_BYTES = 64_000L
    const val OVERSHOOT_RATIO = 0.04
    const val MIN_VIDEO_KBPS = 200
    const val MAX_VIDEO_KBPS = 40_000
    const val MIN_AUDIO_KBPS = 32
    const val MAX_AUDIO_KBPS = 512

    fun durationSeconds(durationMs: Long): Double =
        (durationMs.coerceAtLeast(100L) / 1000.0).coerceAtLeast(0.1)

    fun muxerOverheadBps(targetBytes: Long, durationSec: Double): Double {
        val seconds = durationSec.coerceAtLeast(0.1)
        val headerBps = MUXER_OVERHEAD_BYTES * 8.0 / seconds
        val overshootBps = targetBytes.coerceAtLeast(0L) * 8.0 / seconds * OVERSHOOT_RATIO
        return headerBps + overshootBps
    }

    fun videoKbps(targetBytes: Long, durationMs: Long, audioKbps: Int): Int {
        val seconds = durationSeconds(durationMs)
        val totalBps = targetBytes.coerceAtLeast(0L) * 8.0 / seconds
        val audioBps = audioKbps.coerceAtLeast(0) * 1000.0
        val videoBps = totalBps - audioBps - muxerOverheadBps(targetBytes, seconds)
        return (videoBps / 1000.0).roundToInt().coerceIn(MIN_VIDEO_KBPS, MAX_VIDEO_KBPS)
    }

    fun audioKbps(targetBytes: Long, durationMs: Long): Int {
        val seconds = durationSeconds(durationMs)
        val totalBps = targetBytes.coerceAtLeast(0L) * 8.0 / seconds
        val audioBps = totalBps - muxerOverheadBps(targetBytes, seconds)
        return (audioBps / 1000.0).roundToInt().coerceIn(MIN_AUDIO_KBPS, MAX_AUDIO_KBPS)
    }
}
