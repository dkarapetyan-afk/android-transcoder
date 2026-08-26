package com.androidcompress.app.encode

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.os.Build
import com.androidcompress.app.util.AppLog
import com.androidcompress.app.util.runCatchingLog

object HardwareCodecProbe {
    private const val TAG = "HwCodecProbe"
    fun advertised(mime: String): HardwareAdvertisedCaps? {
        return try {
            val list = MediaCodecList(MediaCodecList.REGULAR_CODECS)
            var best: HardwareAdvertisedCaps? = null
            for (info in list.codecInfos) {
                if (!isHardwareEncoder(info)) continue
                if (!info.supportedTypes.any { it.equals(mime, ignoreCase = true) }) continue
                val codecCaps = runCatchingLog(TAG, "caps for $mime") {
                    info.getCapabilitiesForType(mime)
                }.getOrNull() ?: continue
                val video = codecCaps.videoCapabilities ?: continue
                val width = video.supportedWidths.upper
                val height = runCatchingLog(TAG, "height for $mime") {
                    video.getSupportedHeightsFor(width).upper
                }.getOrElse { video.supportedHeights.upper }
                val tenBit = codecCaps.profileLevels.any { HardwareProfiles.isTenBit(mime, it.profile) } ||
                    codecCaps.colorFormats.any { HardwareProfiles.isTenBitColor(it) }
                val hdr = codecCaps.profileLevels.any { HardwareProfiles.isHdr(mime, it.profile) }
                val next = HardwareAdvertisedCaps(
                    encoderName = info.name,
                    maxWidth = width,
                    maxHeight = height,
                    tenBit = tenBit,
                    hdr = hdr,
                )
                best = merge(best, next)
            }
            best
        } catch (t: Throwable) {
            AppLog.e(TAG, "advertised $mime", t)
            null
        }
    }

    fun isHardwareEncoder(info: MediaCodecInfo): Boolean {
        if (!info.isEncoder) return false
        if (Build.VERSION.SDK_INT >= 29) return !info.isSoftwareOnly
        val name = info.name.lowercase()
        return !name.startsWith("omx.google.") && !name.startsWith("c2.android.")
    }

    internal fun merge(current: HardwareAdvertisedCaps?, next: HardwareAdvertisedCaps): HardwareAdvertisedCaps {
        if (current == null) return next
        val larger = if (next.maxWidth * next.maxHeight > current.maxWidth * current.maxHeight) next else current
        return larger.copy(
            tenBit = current.tenBit || next.tenBit,
            hdr = current.hdr || next.hdr,
        )
    }
}
