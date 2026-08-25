package com.androidcompress.app.encode

import com.androidcompress.app.data.AudioOption
import com.androidcompress.app.data.BitrateMode
import com.androidcompress.app.data.EncodeSettings
import com.androidcompress.app.data.H264Profile
import com.androidcompress.app.data.HdrMode
import com.androidcompress.app.data.SourceVideo
import com.androidcompress.app.data.VideoCodec
import com.androidcompress.app.data.audioOutput
import com.androidcompress.app.data.canCopyAudio
import com.androidcompress.app.data.effectiveAudio
import com.androidcompress.app.data.effectiveVideoCodec
import com.androidcompress.app.data.hasTargetSize
import com.androidcompress.app.data.usesWebm

data class Media3EncodeSpec(
    val videoMimeType: String,
    val outputHeight: Int,
    val outputWidth: Int,
    val outputFps: Int,
    val originalFps: Float,
    val videoBitrateBps: Int,
    val audioBitrateBps: Int,
    val removeAudio: Boolean,
    val remuxAudio: Boolean,
    val encoderLabel: String,
    val preferCbr: Boolean,
    val iFrameIntervalSeconds: Float?,
    val h264Profile: H264Profile,
    val toneMapHdr: Boolean,
    val audioVolume: Float,
    val maxBFrames: Int?,
    val clipStartMs: Long = 0,
    val clipEndMs: Long? = null,
    val removeVideo: Boolean = false,
    val audioMimeType: String = Media3EncodePlanner.MIME_AAC,
    val webm: Boolean = false,
    val companionAudioUri: String? = null,
    val stillImage: Boolean = false,
    val imageDurationMs: Long = 0L,
    val grayscale: Boolean = false,
) {
    val clipActive: Boolean get() = clipStartMs > 0 || clipEndMs != null

    fun clipDurationMs(sourceDurationMs: Long): Long =
        Media3ClipWindow(clipStartMs, clipEndMs).durationMs(sourceDurationMs)
}

data class Media3ClipWindow(
    val startMs: Long,
    val endMs: Long?,
) {
    val active: Boolean get() = startMs > 0 || endMs != null

    fun durationMs(sourceDurationMs: Long): Long {
        val resolvedEnd = endMs
        val end = when {
            resolvedEnd != null -> resolvedEnd
            sourceDurationMs > 0 -> sourceDurationMs
            else -> startMs
        }
        val span = end - startMs
        return if (span > 0) span else sourceDurationMs.coerceAtLeast(0)
    }
}

/**
 * Maps app [EncodeSettings] onto Media3 Transformer parameters.
 * Mirrors Compressor Edge: device MediaCodec encode, optional scale / frame-drop.
 */
object Media3EncodePlanner {

    const val MIME_H264 = "video/avc"
    const val MIME_HEVC = "video/hevc"
    const val MIME_VP8 = "video/x-vnd.on2.vp8"
    const val MIME_VP9 = "video/x-vnd.on2.vp9"
    const val MIME_AV1 = "video/av01"
    const val MIME_AAC = "audio/mp4a-latm"
    const val MIME_OPUS = "audio/opus"
    const val MIN_CLIP_MS = 100L

    fun clipWindow(settings: EncodeSettings, sourceDurationMs: Long): Media3ClipWindow {
        val duration = sourceDurationMs.coerceAtLeast(0L)
        var start = settings.clipStartMs.coerceAtLeast(0L)
        var end = settings.clipEndMs?.takeIf { it > 0L }
        if (end != null && end <= start) {
            return Media3ClipWindow(0L, null)
        }
        if (duration > 0L) {
            if (start >= duration) return Media3ClipWindow(0L, null)
            start = start.coerceAtMost((duration - MIN_CLIP_MS).coerceAtLeast(0L))
            if (end != null) {
                if (end <= start) return Media3ClipWindow(0L, null)
                end = end.coerceAtMost(duration)
                if (end - start < MIN_CLIP_MS) {
                    end = (start + MIN_CLIP_MS).coerceAtMost(duration)
                }
                if (end >= duration) end = null
            }
            if (start <= 0L) start = 0L
        }
        if (start <= 0L && end == null) return Media3ClipWindow(0L, null)
        return Media3ClipWindow(start, end)
    }

    fun outputDurationMs(settings: EncodeSettings, sourceDurationMs: Long): Long =
        clipWindow(settings, sourceDurationMs).durationMs(sourceDurationMs)

    fun outputDurationMs(settings: EncodeSettings, source: SourceVideo): Long =
        outputDurationMs(settings, source.durationMs)

    fun plan(settings: EncodeSettings, source: SourceVideo): Media3EncodeSpec {
        val audioOnly = settings.audioOutput(source.hasVideo)
        val audio = settings.effectiveAudio(source.hasVideo)
        val clip = clipWindow(settings, source.durationMs)
        val outH = if (audioOnly) 0 else FfmpegCommandBuilder.outputHeight(source, settings)
        val outW = if (audioOnly) 0 else FfmpegCommandBuilder.outputWidth(source, outH)
        // Fit-to-size uses the same (targetBytes × 8 / duration) − audio − muxer overhead
        // formula as FfmpegCommandBuilder.scaledVideoBitrate.
        val videoKbps = if (audioOnly) 0 else FfmpegCommandBuilder.scaledVideoBitrate(source, settings)
        val codec = settings.effectiveVideoCodec()
        val webm = settings.usesWebm()
        val fpsCap = settings.fpsCap
        val outputFps = if (!audioOnly && fpsCap != null && source.frameRate > fpsCap + 0.1f) fpsCap else 0
        val volume = FfmpegCommandBuilder.audioVolume(settings)
        val removeAudio = !audioOnly && (audio == AudioOption.MUTE || !source.hasAudio)
        val remuxAudio = !removeAudio &&
            audio == AudioOption.COPY &&
            volume == 1f &&
            settings.canCopyAudio(source) &&
            !settings.hasTargetSize()
        val audioKbps = when {
            removeAudio || remuxAudio -> 0
            else -> FfmpegCommandBuilder.budgetAudioBitrateKbps(source, settings.copy(audio = audio))
        }
        val videoMime = when (codec) {
            VideoCodec.VP8 -> MIME_VP8
            VideoCodec.VP9 -> MIME_VP9
            VideoCodec.AV1 -> MIME_AV1
            VideoCodec.HEVC -> MIME_HEVC
            VideoCodec.H264 -> MIME_H264
        }
        val audioMime = if (webm) MIME_OPUS else MIME_AAC
        val combine = source.isCombine
        val companion = source.audioUri.takeIf { it.isNotBlank() }
        val combineRemoveAudio = combine && audio == AudioOption.MUTE
        val combineRemux = combine &&
            !combineRemoveAudio &&
            audio == AudioOption.COPY &&
            volume == 1f &&
            settings.canCopyAudio(source) &&
            !settings.hasTargetSize()
        return Media3EncodeSpec(
            videoMimeType = videoMime,
            outputHeight = outH,
            outputWidth = outW,
            outputFps = if (source.stillImage) 0 else outputFps,
            originalFps = source.frameRate.coerceAtLeast(1f),
            videoBitrateBps = videoKbps * 1000,
            audioBitrateBps = if (combine) {
                when {
                    combineRemoveAudio || combineRemux -> 0
                    else -> FfmpegCommandBuilder.budgetAudioBitrateKbps(source, settings.copy(audio = audio)) * 1000
                }
            } else {
                audioKbps * 1000
            },
            removeAudio = if (combine) combineRemoveAudio else removeAudio,
            remuxAudio = if (combine) combineRemux else remuxAudio,
            encoderLabel = when {
                audioOnly && remuxAudio -> "Media3 · audio copy"
                audioOnly && webm -> "Media3 · Opus"
                audioOnly -> "Media3 · AAC"
                source.stillImage && codec == VideoCodec.VP8 -> "Media3 · still VP8"
                source.stillImage && codec == VideoCodec.VP9 -> "Media3 · still VP9"
                source.stillImage && codec == VideoCodec.AV1 -> "Media3 · still AV1"
                source.stillImage && codec == VideoCodec.HEVC -> "Media3 · still HEVC"
                source.stillImage -> "Media3 · still H.264"
                codec == VideoCodec.VP8 -> "Media3 · VP8"
                codec == VideoCodec.VP9 -> "Media3 · VP9"
                codec == VideoCodec.AV1 -> "Media3 · AV1"
                codec == VideoCodec.HEVC -> "Media3 · HEVC"
                else -> "Media3 · H.264"
            },
            preferCbr = settings.bitrateMode == BitrateMode.CBR || settings.hasTargetSize(),
            iFrameIntervalSeconds = FfmpegCommandBuilder.keyframeSeconds(settings),
            h264Profile = settings.h264Profile,
            toneMapHdr = !audioOnly && !source.stillImage && settings.hdrMode == HdrMode.TONE_MAP,
            audioVolume = volume,
            maxBFrames = FfmpegCommandBuilder.maxBFrames(settings),
            clipStartMs = clip.startMs,
            clipEndMs = clip.endMs,
            removeVideo = audioOnly,
            audioMimeType = audioMime,
            webm = webm,
            companionAudioUri = companion,
            stillImage = source.stillImage,
            imageDurationMs = if (source.stillImage) clip.durationMs(source.durationMs).coerceAtLeast(MIN_CLIP_MS) else 0L,
            grayscale = !audioOnly && settings.grayscale,
        )
    }

    fun h264Fallback(spec: Media3EncodeSpec): Media3EncodeSpec? {
        if (spec.removeVideo || spec.webm || spec.videoMimeType == MIME_H264) return null
        return spec.copy(videoMimeType = MIME_H264, encoderLabel = "Media3 · H.264")
    }

    fun vp8Fallback(spec: Media3EncodeSpec): Media3EncodeSpec? {
        if (spec.removeVideo || !spec.webm || spec.videoMimeType == MIME_VP8) return null
        return spec.copy(videoMimeType = MIME_VP8, encoderLabel = "Media3 · VP8")
    }

    fun webmFallback(spec: Media3EncodeSpec): Media3EncodeSpec? {
        if (spec.removeVideo || !spec.webm) return null
        return when (spec.videoMimeType) {
            MIME_AV1 -> spec.copy(videoMimeType = MIME_VP9, encoderLabel = "Media3 · VP9")
            MIME_VP9 -> vp8Fallback(spec)
            else -> null
        }
    }
}
