package com.androidcompress.app.capture

import com.androidcompress.app.encode.RecordingCrop
import java.util.Locale
import kotlin.math.roundToInt

/** Text for Result → View log on a screen recording. */
object RecordCaptureLog {
    const val HEADER = "recordCapture"
    const val FOOTER = "endRecordCapture"

    fun extract(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val start = raw.indexOf(HEADER)
        if (start < 0) return null
        val footer = raw.indexOf(FOOTER, start)
        val end = if (footer >= 0) footer + FOOTER.length else raw.length
        return raw.substring(start, end).trimEnd().ifBlank { null }
    }

    fun crop(crop: RecordingCrop): String = "${crop.x},${crop.y} ${crop.width}x${crop.height}"

    fun region(region: RecordRegion?): String = when {
        region == null -> "none"
        region.isFullScreen -> "full"
        else -> String.format(
            Locale.US,
            "%.3f,%.3f,%.3f,%.3f",
            region.left,
            region.top,
            region.right,
            region.bottom,
        )
    }

    fun overlayCrop(region: RecordRegion?, overlayWidth: Int, overlayHeight: Int): RecordingCrop? {
        if (region == null || region.isFullScreen) return null
        if (overlayWidth < 1 || overlayHeight < 1) return null
        val x = (region.left * overlayWidth).roundToInt().coerceAtLeast(0)
        val y = (region.top * overlayHeight).roundToInt().coerceAtLeast(0)
        val w = (region.width * overlayWidth).roundToInt().coerceAtLeast(0)
        val h = (region.height * overlayHeight).roundToInt().coerceAtLeast(0)
        if (w < 1 || h < 1) return null
        return RecordingCrop(x, y, w, h)
    }

    fun build(
        captureWidth: Int,
        captureHeight: Int,
        encodeWidth: Int,
        encodeHeight: Int,
        overlayWidth: Int = 0,
        overlayHeight: Int = 0,
        region: RecordRegion? = null,
        liveCrop: RecordingCrop? = null,
        softwareCrop: RecordingCrop? = null,
        liveApplied: Boolean = false,
        liveError: String? = null,
        coverDestPx: Int = 0,
        outputWidth: Int? = null,
        outputHeight: Int? = null,
        ffmpegCommand: String? = null,
        muxSuccess: Boolean? = null,
    ): String = buildString {
        appendLine(HEADER)
        appendLine("capture=${captureWidth}x$captureHeight")
        appendLine("encode=${encodeWidth}x$encodeHeight")
        if (overlayWidth > 0 && overlayHeight > 0) {
            appendLine("overlay=${overlayWidth}x$overlayHeight")
        }
        appendLine("region=${region(region)}")
        overlayCrop(region, overlayWidth, overlayHeight)?.let {
            appendLine("overlayCrop=${crop(it)}")
        }
        appendLine("liveCrop=${liveCrop?.let(::crop) ?: "none"}")
        appendLine("softwareCrop=${softwareCrop?.let(::crop) ?: "none"}")
        appendLine("liveApplied=$liveApplied")
        if (!liveError.isNullOrBlank()) appendLine("liveError=$liveError")
        appendLine("coverDestPx=$coverDestPx")
        if (outputWidth != null && outputHeight != null) {
            appendLine("output=${outputWidth}x$outputHeight")
        }
        if (ffmpegCommand != null) {
            appendLine("ffmpeg=${if (ffmpegCommand.isBlank()) "none" else ffmpegCommand}")
            if (muxSuccess != null) appendLine("ffmpegSuccess=$muxSuccess")
        }
        append(FOOTER)
    }.trimEnd()
}
