package com.androidcompress.app.encode

import com.androidcompress.app.data.AudioOption
import com.androidcompress.app.data.BFrameSetting
import com.androidcompress.app.data.BitrateMode
import com.androidcompress.app.data.EncodeSettings
import com.androidcompress.app.data.EncoderCapabilities
import com.androidcompress.app.data.H264Profile
import com.androidcompress.app.data.HdrMode
import com.androidcompress.app.data.KeyframeInterval
import com.androidcompress.app.data.Preset
import com.androidcompress.app.data.SourceVideo
import com.androidcompress.app.data.VideoCodec
import com.androidcompress.app.data.audioOutput
import com.androidcompress.app.data.effectiveAudio
import com.androidcompress.app.util.even
import java.util.Locale
import kotlin.math.roundToInt

data class EncodePlan(
    val args: List<String>,
    val videoEncoder: String,
    val pixFmt: String?,
    val outputHeight: Int,
    val outputWidth: Int,
    val videoBitrateKbps: Int,
    val audioBitrateKbps: Int,
)

object FfmpegCommandBuilder {

    fun outputHeight(source: SourceVideo, settings: EncodeSettings): Int {
        val sourceH = source.height.coerceAtLeast(2)
        val cap = settings.maxHeight
        val raw = if (cap != null && sourceH > cap) cap else sourceH
        return even(raw.coerceAtLeast(2))
    }

    fun outputWidth(source: SourceVideo, outputHeight: Int): Int {
        if (source.height <= 0) return even(source.width.coerceAtLeast(2))
        val scaled = (source.width.toDouble() * outputHeight / source.height).roundToInt()
        return even(scaled.coerceAtLeast(2))
    }

    fun scaledVideoBitrate(source: SourceVideo, settings: EncodeSettings): Int {
        val outH = outputHeight(source, settings)
        val referenceHeight = when (settings.preset) {
            Preset.SMALLER -> 720
            Preset.BALANCED -> 1080
            Preset.HIGHER -> 1440
        }
        val referenceBitrate = when (settings.preset) {
            Preset.SMALLER -> 1500
            Preset.BALANCED -> 2500
            Preset.HIGHER -> 6000
        }
        val user = settings.videoBitrateKbps.coerceAtLeast(100)
        if (outH == referenceHeight) return user
        val scaled = (user.toDouble() * outH * outH / (referenceHeight * referenceHeight)).roundToInt()
        return scaled.coerceIn(200, 40_000)
    }

    fun audioBitrateKbps(settings: EncodeSettings): Int = when (settings.audio) {
        AudioOption.MUTE -> 0
        AudioOption.COPY -> 0
        AudioOption.AAC_64 -> 64
        AudioOption.AAC_96 -> 96
        AudioOption.AAC_128 -> 128
        AudioOption.AAC_192 -> 192
    }

    fun outputFrameRate(source: SourceVideo, settings: EncodeSettings): Float {
        val cap = settings.fpsCap
        return if (cap != null && source.frameRate > cap + 0.1f) {
            cap.toFloat()
        } else {
            source.frameRate.coerceAtLeast(1f)
        }
    }

    fun keyframeSeconds(settings: EncodeSettings): Float? = when (settings.keyframeInterval) {
        KeyframeInterval.AUTO -> null
        KeyframeInterval.SEC_1 -> 1f
        KeyframeInterval.SEC_2 -> 2f
        KeyframeInterval.SEC_5 -> 5f
    }

    fun maxBFrames(settings: EncodeSettings): Int? = when (settings.bFrames) {
        BFrameSetting.AUTO -> null
        BFrameSetting.NONE -> 0
        BFrameSetting.ONE -> 1
        BFrameSetting.TWO -> 2
    }

    fun audioVolume(settings: EncodeSettings): Float =
        (settings.audioVolumePercent.coerceIn(10, 400) / 100f)

    fun estimateOutputBytes(source: SourceVideo, settings: EncodeSettings): Long {
        val audioOnly = settings.audioOutput(source.hasVideo)
        val video = if (audioOnly) 0 else scaledVideoBitrate(source, settings)
        val audio = when (settings.effectiveAudio(source.hasVideo)) {
            AudioOption.MUTE -> 0
            AudioOption.COPY -> 128
            AudioOption.AAC_64 -> 64
            AudioOption.AAC_96 -> 96
            AudioOption.AAC_128 -> 128
            AudioOption.AAC_192 -> 192
        }
        val seconds = (source.durationMs / 1000.0).coerceAtLeast(1.0)
        return ((video + audio) * 1000.0 / 8.0 * seconds).toLong() + 64_000
    }

    fun selectVideoEncoder(
        settings: EncodeSettings,
        capabilities: EncoderCapabilities,
    ): String {
        if (settings.codec == VideoCodec.HEVC &&
            settings.preferHardware &&
            capabilities.hasHevcMediaCodec
        ) {
            return "hevc_mediacodec"
        }
        if (settings.preferHardware && capabilities.hasH264MediaCodec) {
            return "h264_mediacodec"
        }
        if (capabilities.hasOpenH264) return "libopenh264"
        if (capabilities.hasMpeg4) return "mpeg4"
        return "mpeg4"
    }

    fun build(
        input: String,
        output: String,
        settings: EncodeSettings,
        source: SourceVideo,
        capabilities: EncoderCapabilities,
        pixFmtOverride: String? = null,
        encoderOverride: String? = null,
    ): EncodePlan {
        if (settings.audioOutput(source.hasVideo)) {
            return buildAudio(input, output, settings, source)
        }
        val encoder = encoderOverride ?: selectVideoEncoder(settings, capabilities)
        val outH = outputHeight(source, settings)
        val outW = outputWidth(source, outH)
        val videoBitrate = scaledVideoBitrate(source, settings)
        val needsScale = outH != source.height || even(source.width) != source.width
        val needsFps = settings.fpsCap != null && source.frameRate > settings.fpsCap + 0.1f
        val toneMap = settings.hdrMode == HdrMode.TONE_MAP
        val isHardware = encoder.endsWith("_mediacodec")
        val pixFmt = when {
            pixFmtOverride != null -> pixFmtOverride
            isHardware -> "nv12"
            else -> null
        }
        val volume = audioVolume(settings)
        val changeVolume = volume != 1f && settings.audio != AudioOption.MUTE && source.hasAudio

        val args = mutableListOf("-y", "-hide_banner", "-i", input)
        val vf = mutableListOf<String>()
        if (toneMap) vf += "format=yuv420p"
        if (needsScale) vf += "scale=$outW:$outH"
        if (vf.isNotEmpty()) {
            args += listOf("-vf", vf.joinToString(","))
        }
        if (needsFps) {
            args += listOf("-r", settings.fpsCap!!.toString())
        }
        args += listOf("-c:v", encoder, "-b:v", "${videoBitrate}k")
        if (settings.bitrateMode == BitrateMode.CBR) {
            args += listOf("-maxrate", "${videoBitrate}k", "-bufsize", "${videoBitrate * 2}k")
        }
        if (pixFmt != null) {
            args += listOf("-pix_fmt", pixFmt)
        }
        if (encoder == "hevc_mediacodec") {
            args += listOf("-tag:v", "hvc1")
        }
        if (settings.codec == VideoCodec.H264) {
            when (settings.h264Profile) {
                H264Profile.BASELINE -> args += listOf("-profile:v", "baseline")
                H264Profile.MAIN -> args += listOf("-profile:v", "main")
                H264Profile.HIGH -> args += listOf("-profile:v", "high")
                H264Profile.AUTO -> Unit
            }
        }
        keyframeSeconds(settings)?.let { seconds ->
            val gop = (outputFrameRate(source, settings) * seconds).roundToInt().coerceAtLeast(1)
            args += listOf("-g", gop.toString())
        }
        maxBFrames(settings)?.let { frames ->
            args += listOf("-bf", frames.toString())
        }
        if (toneMap) {
            args += listOf("-colorspace", "bt709", "-color_primaries", "bt709", "-color_trc", "bt709")
        }
        if (changeVolume) {
            args += listOf("-filter:a", "volume=$volume")
        }
        when (settings.audio) {
            AudioOption.MUTE -> args += "-an"
            AudioOption.COPY -> {
                if (!source.hasAudio) {
                    args += "-an"
                } else if (changeVolume) {
                    args += listOf("-c:a", "aac", "-b:a", "128k")
                } else if (source.audioCodec?.contains("aac", ignoreCase = true) == true) {
                    args += listOf("-c:a", "copy")
                } else {
                    args += listOf("-c:a", "aac", "-b:a", "128k")
                }
            }
            AudioOption.AAC_64, AudioOption.AAC_96, AudioOption.AAC_128, AudioOption.AAC_192 -> {
                if (source.hasAudio) {
                    args += listOf("-c:a", "aac", "-b:a", "${audioBitrateKbps(settings)}k")
                } else {
                    args += "-an"
                }
            }
        }
        if (settings.fastStart) {
            args += listOf("-movflags", "+faststart")
        }
        args += output
        return EncodePlan(
            args = ExtraArgsSanitizer.insert(args, settings.ffmpegExtraArgs),
            videoEncoder = encoder,
            pixFmt = pixFmt,
            outputHeight = outH,
            outputWidth = outW,
            videoBitrateKbps = videoBitrate,
            audioBitrateKbps = audioBitrateKbps(settings),
        )
    }

    fun fallbackPlan(previous: EncodePlan, input: String, output: String, settings: EncodeSettings, source: SourceVideo, capabilities: EncoderCapabilities): EncodePlan? {
        if (settings.audioOutput(source.hasVideo) || previous.videoEncoder.isBlank()) return null
        return when {
            previous.pixFmt == "nv12" -> build(
                input, output, settings, source, capabilities,
                pixFmtOverride = "yuv420p",
                encoderOverride = previous.videoEncoder,
            )
            previous.videoEncoder.endsWith("_mediacodec") && capabilities.hasOpenH264 -> build(
                input, output, settings, source, capabilities,
                encoderOverride = "libopenh264",
            )
            previous.videoEncoder != "mpeg4" && capabilities.hasMpeg4 -> build(
                input, output, settings, source, capabilities,
                encoderOverride = "mpeg4",
            )
            else -> null
        }
    }

    private fun buildAudio(
        input: String,
        output: String,
        settings: EncodeSettings,
        source: SourceVideo,
    ): EncodePlan {
        val audio = settings.effectiveAudio(source.hasVideo)
        val volume = audioVolume(settings)
        val changeVolume = volume != 1f && source.hasAudio
        val args = mutableListOf("-y", "-hide_banner", "-i", input, "-vn")
        appendClip(args, settings, source)
        if (changeVolume) {
            args += listOf("-filter:a", "volume=$volume")
        }
        when (audio) {
            AudioOption.MUTE -> args += "-an"
            AudioOption.COPY -> {
                if (!source.hasAudio) {
                    args += "-an"
                } else if (changeVolume) {
                    args += listOf("-c:a", "aac", "-b:a", "128k")
                } else if (source.audioCodec?.contains("aac", ignoreCase = true) == true) {
                    args += listOf("-c:a", "copy")
                } else {
                    args += listOf("-c:a", "aac", "-b:a", "128k")
                }
            }
            AudioOption.AAC_64, AudioOption.AAC_96, AudioOption.AAC_128, AudioOption.AAC_192 -> {
                if (source.hasAudio) {
                    args += listOf("-c:a", "aac", "-b:a", "${audioBitrateKbps(settings.copy(audio = audio))}k")
                } else {
                    args += "-an"
                }
            }
        }
        if (settings.fastStart) {
            args += listOf("-movflags", "+faststart")
        }
        args += output
        return EncodePlan(
            args = ExtraArgsSanitizer.insert(args, settings.ffmpegExtraArgs),
            videoEncoder = "",
            pixFmt = null,
            outputHeight = 0,
            outputWidth = 0,
            videoBitrateKbps = 0,
            audioBitrateKbps = if (audio == AudioOption.COPY) 128 else audioBitrateKbps(settings.copy(audio = audio)),
        )
    }

    private fun appendClip(args: MutableList<String>, settings: EncodeSettings, source: SourceVideo) {
        val start = settings.clipStartMs.coerceAtLeast(0L)
        val end = settings.clipEndMs?.takeIf { it > start }
        val duration = source.durationMs
        if (start <= 0L && (end == null || (duration > 0L && end >= duration))) return
        if (start > 0L) args += listOf("-ss", formatFfmpegSeconds(start))
        val span = when {
            end != null -> end - start
            duration > start -> duration - start
            else -> 0L
        }
        if (span > 0L) args += listOf("-t", formatFfmpegSeconds(span))
    }

    private fun formatFfmpegSeconds(ms: Long): String =
        String.format(Locale.US, "%.3f", ms / 1000.0)
}
