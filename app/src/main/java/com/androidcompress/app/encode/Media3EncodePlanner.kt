package com.androidcompress.app.encode

import com.androidcompress.app.data.AudioOption
import com.androidcompress.app.data.BitrateMode
import com.androidcompress.app.data.EncodeSettings
import com.androidcompress.app.data.H264Profile
import com.androidcompress.app.data.HdrMode
import com.androidcompress.app.data.SourceVideo
import com.androidcompress.app.data.VideoCodec

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
)

/**
 * Maps app [EncodeSettings] onto Media3 Transformer parameters.
 * Mirrors Compressor Edge: device MediaCodec encode, optional scale / frame-drop.
 */
object Media3EncodePlanner {

    const val MIME_H264 = "video/avc"
    const val MIME_HEVC = "video/hevc"
    const val MIME_AAC = "audio/mp4a-latm"

    fun plan(settings: EncodeSettings, source: SourceVideo): Media3EncodeSpec {
        val outH = FfmpegCommandBuilder.outputHeight(source, settings)
        val outW = FfmpegCommandBuilder.outputWidth(source, outH)
        val videoKbps = FfmpegCommandBuilder.scaledVideoBitrate(source, settings)
        val hevc = settings.codec == VideoCodec.HEVC
        val fpsCap = settings.fpsCap
        val outputFps = if (fpsCap != null && source.frameRate > fpsCap + 0.1f) fpsCap else 0
        val volume = FfmpegCommandBuilder.audioVolume(settings)
        val removeAudio = settings.audio == AudioOption.MUTE || !source.hasAudio
        val remuxAudio = !removeAudio && settings.audio == AudioOption.COPY && volume == 1f
        val audioKbps = when {
            removeAudio || remuxAudio -> 0
            settings.audio == AudioOption.COPY -> 128
            else -> FfmpegCommandBuilder.audioBitrateKbps(settings).coerceAtLeast(64)
        }
        return Media3EncodeSpec(
            videoMimeType = if (hevc) MIME_HEVC else MIME_H264,
            outputHeight = outH,
            outputWidth = outW,
            outputFps = outputFps,
            originalFps = source.frameRate.coerceAtLeast(1f),
            videoBitrateBps = videoKbps * 1000,
            audioBitrateBps = audioKbps * 1000,
            removeAudio = removeAudio,
            remuxAudio = remuxAudio,
            encoderLabel = if (hevc) "Media3 · HEVC" else "Media3 · H.264",
            preferCbr = settings.bitrateMode == BitrateMode.CBR,
            iFrameIntervalSeconds = FfmpegCommandBuilder.keyframeSeconds(settings),
            h264Profile = settings.h264Profile,
            toneMapHdr = settings.hdrMode == HdrMode.TONE_MAP,
            audioVolume = volume,
            maxBFrames = FfmpegCommandBuilder.maxBFrames(settings),
        )
    }

    fun h264Fallback(spec: Media3EncodeSpec): Media3EncodeSpec? {
        if (spec.videoMimeType == MIME_H264) return null
        return spec.copy(videoMimeType = MIME_H264, encoderLabel = "Media3 · H.264")
    }
}
