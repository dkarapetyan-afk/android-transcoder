package com.androidcompress.app.util

import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.roundToInt

fun formatBytes(bytes: Long): String {
    if (bytes < 0) return "—"
    val units = arrayOf("B", "KB", "MB", "GB")
    var value = bytes.toDouble()
    var idx = 0
    while (value >= 1024 && idx < units.lastIndex) {
        value /= 1024
        idx++
    }
    val pattern = if (idx == 0) "%.0f %s" else "%.1f %s"
    return String.format(Locale.US, pattern, value, units[idx])
}

fun formatDuration(ms: Long): String {
    if (ms <= 0) return "0:00"
    val hours = TimeUnit.MILLISECONDS.toHours(ms)
    val minutes = TimeUnit.MILLISECONDS.toMinutes(ms) % 60
    val seconds = TimeUnit.MILLISECONDS.toSeconds(ms) % 60
    return if (hours > 0) {
        String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.US, "%d:%02d", minutes, seconds)
    }
}

fun parseDurationMs(raw: String): Long? {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return null
    val parts = trimmed.split(":")
    val millis = when (parts.size) {
        1 -> {
            val seconds = parts[0].toDoubleOrNull() ?: return null
            (seconds * 1000.0).toLong()
        }
        2 -> {
            val minutes = parts[0].toLongOrNull() ?: return null
            val seconds = parts[1].toDoubleOrNull() ?: return null
            (((minutes * 60) + seconds) * 1000.0).toLong()
        }
        3 -> {
            val hours = parts[0].toLongOrNull() ?: return null
            val minutes = parts[1].toLongOrNull() ?: return null
            val seconds = parts[2].toDoubleOrNull() ?: return null
            (((hours * 3600) + (minutes * 60) + seconds) * 1000.0).toLong()
        }
        else -> return null
    }
    return millis.coerceAtLeast(0L)
}

fun formatMegabytes(bytes: Long): String {
    val mb = bytes / (1024.0 * 1024.0)
    return if (abs(mb - mb.roundToInt()) < 0.0005) {
        mb.roundToInt().toString()
    } else {
        String.format(Locale.US, "%.2f", mb)
    }
}

fun parseMegabytesToBytes(raw: String): Long? {
    val mb = raw.trim().toDoubleOrNull() ?: return null
    if (mb <= 0.0) return null
    return (mb * 1024.0 * 1024.0).toLong()
}

fun formatResolution(width: Int, height: Int): String {
    if (width <= 0 || height <= 0) return "Unknown"
    return "${width}×$height"
}

fun even(value: Int): Int = value and 1.inv()
