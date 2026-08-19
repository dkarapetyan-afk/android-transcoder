package com.androidcompress.app.encode

import com.androidcompress.app.data.EncoderCapabilities
import org.json.JSONArray
import org.json.JSONObject

data class HardwareSize(
    val width: Int,
    val height: Int,
) {
    val pixels: Int get() = width * height
}

data class HardwareTarget(
    val id: String,
    val displayName: String,
    val kind: HardwareTargetKind,
    val mime: String,
    val ffmpegName: String?,
    val extension: String,
)

enum class HardwareTargetKind { FFMPEG, MEDIA3 }

enum class HardwareStep { SOURCE, ENCODE, TEN_BIT }

data class HardwareAdvertisedCaps(
    val encoderName: String,
    val maxWidth: Int,
    val maxHeight: Int,
    val tenBit: Boolean,
    val hdr: Boolean,
)

data class HardwareProgress(
    val targetId: String,
    val displayName: String,
    val step: HardwareStep,
    val width: Int,
    val height: Int,
    val index: Int,
    val total: Int,
)

data class HardwareEncoderResult(
    val targetId: String,
    val displayName: String,
    val available: Boolean,
    val codecName: String,
    val advertisedMaxWidth: Int,
    val advertisedMaxHeight: Int,
    val verifiedMaxWidth: Int,
    val verifiedMaxHeight: Int,
    val advertisedTenBit: Boolean,
    val advertisedHdr: Boolean,
    val tenBitVerified: Boolean?,
    val speedX: Float?,
    val encodeMs: Long?,
    val error: String?,
)

data class HardwareProfileReport(
    val startedAt: Long,
    val finishedAt: Long,
    val cancelled: Boolean,
    val results: List<HardwareEncoderResult>,
)

object HardwareProfiles {
    const val MIME_AVC = "video/avc"
    const val MIME_HEVC = "video/hevc"
    const val MIME_VP9 = "video/x-vnd.on2.vp9"
    const val MIME_AV1 = "video/av01"

    const val AVC_HIGH10 = 16
    const val HEVC_MAIN10 = 2
    const val HEVC_MAIN10_HDR10 = 4096
    const val HEVC_MAIN10_HDR10_PLUS = 8192
    const val VP9_PROFILE2 = 4
    const val VP9_PROFILE3 = 8
    const val VP9_PROFILE2_HDR = 4096
    const val VP9_PROFILE3_HDR = 8192
    const val VP9_PROFILE2_HDR10_PLUS = 16384
    const val VP9_PROFILE3_HDR10_PLUS = 32768
    const val AV1_MAIN8 = 1
    const val AV1_MAIN10 = 2
    const val AV1_MAIN10_HDR10 = 4096
    const val AV1_MAIN10_HDR10_PLUS = 8192
    const val COLOR_YUV_P010 = 54
    const val COLOR_ABGR_2101010 = 2130750114

    fun isTenBit(mime: String, profile: Int): Boolean = when {
        mime.equals(MIME_AVC, ignoreCase = true) -> profile == AVC_HIGH10
        mime.equals(MIME_HEVC, ignoreCase = true) ->
            profile == HEVC_MAIN10 || profile == HEVC_MAIN10_HDR10 || profile == HEVC_MAIN10_HDR10_PLUS
        mime.equals(MIME_VP9, ignoreCase = true) ->
            profile == VP9_PROFILE2 ||
                profile == VP9_PROFILE3 ||
                profile == VP9_PROFILE2_HDR ||
                profile == VP9_PROFILE3_HDR ||
                profile == VP9_PROFILE2_HDR10_PLUS ||
                profile == VP9_PROFILE3_HDR10_PLUS
        mime.equals(MIME_AV1, ignoreCase = true) ->
            profile == AV1_MAIN10 || profile == AV1_MAIN10_HDR10 || profile == AV1_MAIN10_HDR10_PLUS
        else -> false
    }

    fun isHdr(mime: String, profile: Int): Boolean = when {
        mime.equals(MIME_HEVC, ignoreCase = true) ->
            profile == HEVC_MAIN10_HDR10 || profile == HEVC_MAIN10_HDR10_PLUS
        mime.equals(MIME_VP9, ignoreCase = true) ->
            profile == VP9_PROFILE2_HDR ||
                profile == VP9_PROFILE3_HDR ||
                profile == VP9_PROFILE2_HDR10_PLUS ||
                profile == VP9_PROFILE3_HDR10_PLUS
        mime.equals(MIME_AV1, ignoreCase = true) ->
            profile == AV1_MAIN10_HDR10 || profile == AV1_MAIN10_HDR10_PLUS
        else -> false
    }

    fun isTenBitColor(colorFormat: Int): Boolean =
        colorFormat == COLOR_YUV_P010 || colorFormat == COLOR_ABGR_2101010
}

object HardwareProfilePlan {
    val LADDER = listOf(
        HardwareSize(3840, 2160),
        HardwareSize(2560, 1440),
        HardwareSize(1920, 1080),
        HardwareSize(1280, 720),
        HardwareSize(854, 480),
    )

    const val CLIP_MS = 1_000L
    const val FPS = 30
    const val DEFAULT_MAX_WIDTH = 1920
    const val DEFAULT_MAX_HEIGHT = 1080

    fun targets(caps: EncoderCapabilities): List<HardwareTarget> {
        val out = ArrayList<HardwareTarget>(6)
        if (caps.hasH264MediaCodec) {
            out += HardwareTarget(
                id = "h264_mediacodec",
                displayName = "h264_mediacodec",
                kind = HardwareTargetKind.FFMPEG,
                mime = HardwareProfiles.MIME_AVC,
                ffmpegName = "h264_mediacodec",
                extension = "mp4",
            )
        }
        if (caps.hasHevcMediaCodec) {
            out += HardwareTarget(
                id = "hevc_mediacodec",
                displayName = "hevc_mediacodec",
                kind = HardwareTargetKind.FFMPEG,
                mime = HardwareProfiles.MIME_HEVC,
                ffmpegName = "hevc_mediacodec",
                extension = "mp4",
            )
        }
        if (caps.hasVp9MediaCodec) {
            out += HardwareTarget(
                id = "vp9_mediacodec",
                displayName = "vp9_mediacodec",
                kind = HardwareTargetKind.FFMPEG,
                mime = HardwareProfiles.MIME_VP9,
                ffmpegName = "vp9_mediacodec",
                extension = "webm",
            )
        }
        if (caps.hasAv1MediaCodec) {
            out += HardwareTarget(
                id = "av1_mediacodec",
                displayName = "av1_mediacodec",
                kind = HardwareTargetKind.FFMPEG,
                mime = HardwareProfiles.MIME_AV1,
                ffmpegName = "av1_mediacodec",
                extension = "mp4",
            )
        }
        val media3Mime = when {
            caps.hasH264MediaCodec -> HardwareProfiles.MIME_AVC
            caps.hasHevcMediaCodec -> HardwareProfiles.MIME_HEVC
            caps.hasAv1MediaCodec -> HardwareProfiles.MIME_AV1
            caps.hasVp9MediaCodec -> HardwareProfiles.MIME_VP9
            else -> HardwareProfiles.MIME_AVC
        }
        out += HardwareTarget(
            id = "media3",
            displayName = "Media3",
            kind = HardwareTargetKind.MEDIA3,
            mime = media3Mime,
            ffmpegName = null,
            extension = if (media3Mime == HardwareProfiles.MIME_VP9) "webm" else "mp4",
        )
        if (caps.hasAv1MediaCodec && media3Mime != HardwareProfiles.MIME_AV1) {
            out += HardwareTarget(
                id = "media3_av1",
                displayName = "Media3 · AV1",
                kind = HardwareTargetKind.MEDIA3,
                mime = HardwareProfiles.MIME_AV1,
                ffmpegName = null,
                extension = "mp4",
            )
        }
        return out
    }

    fun sizesFor(maxWidth: Int, maxHeight: Int): List<HardwareSize> {
        val width = maxWidth.coerceAtLeast(2) and 1.inv()
        val height = maxHeight.coerceAtLeast(2) and 1.inv()
        val filtered = LADDER.filter { it.width <= width && it.height <= height }
        return filtered.ifEmpty { listOf(HardwareSize(width.coerceAtMost(1920), height.coerceAtMost(1080))) }
    }

    fun bitrateKbps(width: Int, height: Int): Int {
        val pixels = width * height
        return when {
            pixels >= 3840 * 2160 -> 25_000
            pixels >= 2560 * 1440 -> 14_000
            pixels >= 1920 * 1080 -> 8_000
            pixels >= 1280 * 720 -> 4_000
            else -> 2_000
        }
    }

    fun speedX(sourceMs: Long, encodeMs: Long): Float {
        if (encodeMs <= 0L) return 0f
        return sourceMs.toFloat() / encodeMs.toFloat()
    }
}

object HardwareProfileJson {
    fun encode(report: HardwareProfileReport): String = JSONObject().apply {
        put("startedAt", report.startedAt)
        put("finishedAt", report.finishedAt)
        put("cancelled", report.cancelled)
        put(
            "results",
            JSONArray().apply {
                for (item in report.results) {
                    put(
                        JSONObject().apply {
                            put("targetId", item.targetId)
                            put("displayName", item.displayName)
                            put("available", item.available)
                            put("codecName", item.codecName)
                            put("advertisedMaxWidth", item.advertisedMaxWidth)
                            put("advertisedMaxHeight", item.advertisedMaxHeight)
                            put("verifiedMaxWidth", item.verifiedMaxWidth)
                            put("verifiedMaxHeight", item.verifiedMaxHeight)
                            put("advertisedTenBit", item.advertisedTenBit)
                            put("advertisedHdr", item.advertisedHdr)
                            if (item.tenBitVerified != null) put("tenBitVerified", item.tenBitVerified)
                            if (item.speedX != null) put("speedX", item.speedX.toDouble())
                            if (item.encodeMs != null) put("encodeMs", item.encodeMs)
                            if (item.error != null) put("error", item.error)
                        },
                    )
                }
            },
        )
    }.toString()

    fun decode(raw: String?): HardwareProfileReport? {
        if (raw.isNullOrBlank()) return null
        return runCatching {
            val obj = JSONObject(raw)
            val rows = obj.optJSONArray("results") ?: JSONArray()
            val results = ArrayList<HardwareEncoderResult>(rows.length())
            for (i in 0 until rows.length()) {
                val row = rows.getJSONObject(i)
                results += HardwareEncoderResult(
                    targetId = row.getString("targetId"),
                    displayName = row.getString("displayName"),
                    available = row.optBoolean("available", true),
                    codecName = row.optString("codecName"),
                    advertisedMaxWidth = row.optInt("advertisedMaxWidth"),
                    advertisedMaxHeight = row.optInt("advertisedMaxHeight"),
                    verifiedMaxWidth = row.optInt("verifiedMaxWidth"),
                    verifiedMaxHeight = row.optInt("verifiedMaxHeight"),
                    advertisedTenBit = row.optBoolean("advertisedTenBit"),
                    advertisedHdr = row.optBoolean("advertisedHdr"),
                    tenBitVerified = if (row.has("tenBitVerified")) row.getBoolean("tenBitVerified") else null,
                    speedX = if (row.has("speedX")) row.getDouble("speedX").toFloat() else null,
                    encodeMs = if (row.has("encodeMs")) row.getLong("encodeMs") else null,
                    error = row.optString("error").takeIf { it.isNotBlank() },
                )
            }
            HardwareProfileReport(
                startedAt = obj.optLong("startedAt"),
                finishedAt = obj.optLong("finishedAt"),
                cancelled = obj.optBoolean("cancelled"),
                results = results,
            )
        }.getOrNull()
    }
}
