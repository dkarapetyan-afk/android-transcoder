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
import com.androidcompress.app.data.canCopyAudio
import com.androidcompress.app.data.effectiveAudio
import com.androidcompress.app.data.effectiveVideoCodec
import com.androidcompress.app.data.hasTargetSize
import com.androidcompress.app.data.usesWebm
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
    val firstPassArgs: List<String>? = null,
)

object FfmpegCommandBuilder {

    /** Pass 1 must not write to stdout (`-`). FFmpeg-Kit owns that pipe, and libvpx then encodes 0 frames. */
    const val TWO_PASS_NULL_OUTPUT = "/dev/null"

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
        val targetBytes = settings.targetSizeBytes?.takeIf { settings.hasTargetSize() }
        if (targetBytes != null) {
            val durationMs = Media3EncodePlanner.clipWindow(settings, source.durationMs)
                .durationMs(source.durationMs)
            return TargetSizeBitrate.videoKbps(
                targetBytes,
                durationMs,
                budgetAudioBitrateKbps(source, settings),
            )
        }
        val outH = outputHeight(source, settings)
        val referenceHeight = when (settings.preset) {
            Preset.SMALLER -> 720
            Preset.BALANCED -> 1080
            Preset.HIGHER -> 1440
        }
        val user = settings.videoBitrateKbps.coerceAtLeast(100)
        if (outH == referenceHeight) return user
        val scaled = (user.toDouble() * outH * outH / (referenceHeight * referenceHeight)).roundToInt()
        return scaled.coerceIn(TargetSizeBitrate.MIN_VIDEO_KBPS, TargetSizeBitrate.MAX_VIDEO_KBPS)
    }

    fun audioBitrateKbps(settings: EncodeSettings): Int = when (settings.audio) {
        AudioOption.MUTE -> 0
        AudioOption.COPY -> 0
        AudioOption.AAC_64 -> 64
        AudioOption.AAC_96 -> 96
        AudioOption.AAC_128 -> 128
        AudioOption.AAC_192 -> 192
    }

    /** Audio bitrate subtracted from a fit-to-size budget. COPY is treated as 128 kbps. */
    fun budgetAudioBitrateKbps(source: SourceVideo, settings: EncodeSettings): Int {
        val audio = settings.effectiveAudio(source.hasVideo)
        if (audio == AudioOption.MUTE) return 0
        if (!source.hasAudio && !source.isCombine) return 0
        val targetBytes = settings.targetSizeBytes
        if (targetBytes != null && settings.hasTargetSize() && settings.audioOutput(source.hasVideo)) {
            val durationMs = Media3EncodePlanner.clipWindow(settings, source.durationMs)
                .durationMs(source.durationMs)
            return TargetSizeBitrate.audioKbps(targetBytes, durationMs)
        }
        return when (audio) {
            AudioOption.MUTE -> 0
            AudioOption.COPY -> 128
            AudioOption.AAC_64 -> 64
            AudioOption.AAC_96 -> 96
            AudioOption.AAC_128 -> 128
            AudioOption.AAC_192 -> 192
        }
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
        val audio = budgetAudioBitrateKbps(source, settings)
        val durationMs = Media3EncodePlanner.clipWindow(settings, source.durationMs)
            .durationMs(source.durationMs)
        val seconds = (durationMs / 1000.0).coerceAtLeast(1.0)
        return ((video + audio) * 1000.0 / 8.0 * seconds).toLong() + TargetSizeBitrate.MUXER_OVERHEAD_BYTES
    }

    fun selectVideoEncoder(
        settings: EncodeSettings,
        capabilities: EncoderCapabilities,
    ): String {
        return when (settings.effectiveVideoCodec()) {
            // FFmpeg's vp8/vp9_mediacodec path writes garbage frames on many
            // devices (including Pixel) even when the process exits 0. Prefer libvpx.
            VideoCodec.VP9 -> when {
                capabilities.hasLibvpxVp9 || capabilities.hasLibvpx -> "libvpx-vp9"
                capabilities.hasVp9MediaCodec -> "vp9_mediacodec"
                else -> "libvpx-vp9"
            }
            VideoCodec.VP8 -> when {
                capabilities.hasLibvpx -> "libvpx"
                capabilities.hasVp8MediaCodec -> "vp8_mediacodec"
                else -> "libvpx"
            }
            VideoCodec.AV1 -> selectAv1Encoder(settings, capabilities)
            VideoCodec.HEVC -> when {
                settings.preferHardware && capabilities.hasHevcMediaCodec -> "hevc_mediacodec"
                capabilities.hasOpenH264 -> "libopenh264"
                else -> "mpeg4"
            }
            // FFmpeg 8 h264_mediacodec writes scrambled frames (gbr graph,
            // NV12 layout, dropped PTS) while exiting 0. Same class of bug as
            // vp8/vp9_mediacodec. Use software H.264; Media3 is the hardware path.
            VideoCodec.H264 -> when {
                capabilities.hasOpenH264 -> "libopenh264"
                else -> "mpeg4"
            }
        }
    }

    private fun selectAv1Encoder(
        settings: EncodeSettings,
        capabilities: EncoderCapabilities,
    ): String {
        if (settings.preferHardware && capabilities.hasAv1MediaCodec) return "av1_mediacodec"
        if (capabilities.hasLibaomAv1) return "libaom-av1"
        if (capabilities.hasLibSvtAv1) return "libsvtav1"
        if (settings.usesWebm()) {
            return when {
                capabilities.hasLibvpxVp9 || capabilities.hasLibvpx -> "libvpx-vp9"
                capabilities.hasVp9MediaCodec -> "vp9_mediacodec"
                else -> "libvpx-vp9"
            }
        }
        return when {
            capabilities.hasOpenH264 -> "libopenh264"
            else -> "mpeg4"
        }
    }

    fun audioEncoderName(settings: EncodeSettings): String =
        if (settings.usesWebm()) "libopus" else "aac"

    fun supportsTwoPass(encoder: String): Boolean = when (encoder) {
        "mpeg4", "libvpx", "libvpx-vp9", "libaom-av1", "libsvtav1" -> true
        else -> false
    }

    fun usesTwoPass(settings: EncodeSettings, source: SourceVideo, encoder: String): Boolean =
        settings.twoPass &&
            !settings.audioOutput(source.hasVideo) &&
            !source.stillImage &&
            supportsTwoPass(encoder)

    fun build(
        input: String,
        output: String,
        settings: EncodeSettings,
        source: SourceVideo,
        capabilities: EncoderCapabilities,
        pixFmtOverride: String? = null,
        encoderOverride: String? = null,
        audioInput: String? = null,
        passLogPrefix: String? = null,
    ): EncodePlan {
        val companion = audioInput?.takeIf { it.isNotBlank() }
            ?: source.audioUri.takeIf { it.isNotBlank() }
        val combine = source.isCombine && !companion.isNullOrBlank()
        if (settings.audioOutput(source.hasVideo) && !combine) {
            return buildAudio(input, output, settings, source)
        }
        val encoder = encoderOverride ?: selectVideoEncoder(settings, capabilities)
        val twoPass = usesTwoPass(settings, source, encoder)
        val outH = outputHeight(source, settings)
        val outW = outputWidth(source, outH)
        val videoBitrate = scaledVideoBitrate(source, settings)
        val needsScale = outH != source.height || even(source.width) != source.width
        val toneMap = settings.hdrMode == HdrMode.TONE_MAP
        val isHardware = encoder.endsWith("_mediacodec")
        val isLibvpx = encoder == "libvpx" || encoder == "libvpx-vp9"
        val pixFmt = pixFmtOverride ?: "yuv420p"
        val fpsOut = outputFrameRate(source, settings).roundToInt().coerceIn(1, 120)
        // Software must set -r: screen recordings remuxed at a 90k timescale
        // report tbr=90k and otherwise drop/duplicate frames. Do not force -r
        // on mediacodec; that can hang the encoder after the first stats tick.
        val needsFps = !source.stillImage && (
            !isHardware ||
                (settings.fpsCap != null && source.frameRate > settings.fpsCap + 0.1f)
            )
        val volume = audioVolume(settings)
        val changeVolume = volume != 1f && settings.audio != AudioOption.MUTE && source.hasAudio
        val clip = Media3EncodePlanner.clipWindow(settings, source.durationMs)
        val outputDurationMs = if (combine) clip.durationMs(source.durationMs) else 0L

        val args = mutableListOf("-y", "-hide_banner")
        if (combine) {
            appendCombineInputs(args, input, companion!!, source, clip, outputDurationMs)
        } else {
            args += listOf("-i", input)
        }
        val vf = mutableListOf<String>()
        if (needsScale) {
            vf += "scale=$outW:$outH:in_color_matrix=bt709:out_color_matrix=bt709:in_range=tv:out_range=tv"
        }
        if (settings.grayscale) vf += FfmpegMuxCommands.GRAYSCALE_FILTER
        if (toneMap || settings.usesWebm() || pixFmt == "yuv420p") vf += "format=yuv420p"
        if (pixFmt == "nv12") vf += "format=nv12"
        // libvpx 2-pass needs CFR timestamps before the encoder. `-r` alone with
        // `-f null` can dup/drop every frame and report `nothing was encoded`.
        if (twoPass && needsFps) vf += "fps=$fpsOut"
        val useComplex = combine && vf.isNotEmpty()
        if (useComplex) {
            // Two inputs: `-vf` + `-map 0:v:0` can leave the picture unfiltered.
            args += listOf("-filter_complex", "[0:v]${vf.joinToString(",")}[v]")
        } else if (vf.isNotEmpty()) {
            args += listOf("-vf", vf.joinToString(","))
        }
        val videoMap = if (useComplex) "[v]" else "0:v:0"
        if (needsFps) {
            args += listOf("-r", fpsOut.toString())
        }
        args += listOf("-c:v", encoder, "-b:v", "${videoBitrate}k")
        if (isLibvpx) {
            args += listOf("-deadline", "good", "-cpu-used", if (twoPass) "2" else "5", "-row-mt", "1")
            if (encoder == "libvpx" && !twoPass) {
                args += listOf("-auto-alt-ref", "0")
            }
        }
        if (encoder == "libaom-av1") {
            if (twoPass) {
                args += listOf("-cpu-used", "6", "-row-mt", "1", "-tiles", "2x2")
            } else {
                args += listOf("-usage", "realtime", "-cpu-used", "8", "-row-mt", "1", "-tiles", "2x2")
            }
        }
        if (encoder == "libsvtav1") {
            args += listOf("-preset", "10")
        }
        if ((settings.bitrateMode == BitrateMode.CBR || settings.hasTargetSize()) && !twoPass) {
            if (encoder == "libvpx" || encoder == "libvpx-vp9" || encoder == "libaom-av1") {
                args += listOf("-minrate", "${videoBitrate}k", "-maxrate", "${videoBitrate}k")
            } else {
                args += listOf("-maxrate", "${videoBitrate}k", "-bufsize", "${videoBitrate * 2}k")
            }
        }
        args += listOf("-pix_fmt", pixFmt)
        if (encoder == "hevc_mediacodec") {
            args += listOf("-tag:v", "hvc1")
        }
        if (encoder == "av1_mediacodec" && !settings.usesWebm()) {
            args += listOf("-tag:v", "av01")
        }
        if (settings.effectiveVideoCodec() == VideoCodec.H264) {
            when (settings.h264Profile) {
                H264Profile.BASELINE -> args += listOf("-profile:v", "baseline")
                H264Profile.MAIN -> args += listOf("-profile:v", "main")
                H264Profile.HIGH -> args += listOf("-profile:v", "high")
                H264Profile.AUTO -> Unit
            }
        }
        val gopSeconds = keyframeSeconds(settings)
        if (gopSeconds != null) {
            val gop = (outputFrameRate(source, settings) * gopSeconds).roundToInt().coerceAtLeast(1)
            args += listOf("-g", gop.toString())
        } else if (isHardware) {
            args += listOf("-g", fpsOut.toString())
        }
        if (!settings.usesWebm() && settings.effectiveVideoCodec() != VideoCodec.AV1) {
            maxBFrames(settings)?.let { frames ->
                args += listOf("-bf", frames.toString())
            }
        }
        args += listOf("-colorspace", "bt709", "-color_primaries", "bt709", "-color_trc", "bt709", "-color_range", "tv")
        if (twoPass) {
            args += listOf("-stats_period", "0.25")
        }
        val logPrefix = passLogPrefix ?: FfmpegCommandTemplate.PASSLOG
        val firstPassArgs = if (twoPass) {
            val pass1 = args.toMutableList()
            pass1 += listOf("-pass", "1", "-passlogfile", logPrefix)
            pass1 += listOf("-map", videoMap, "-an", "-f", "null", TWO_PASS_NULL_OUTPUT)
            FfmpegMuxCommands.ensureGrayscale(
                ExtraArgsSanitizer.insert(pass1, settings.ffmpegExtraArgs),
                settings.grayscale,
            )
        } else {
            null
        }
        if (twoPass) {
            args += listOf("-pass", "2", "-passlogfile", logPrefix)
        }
        if (changeVolume) {
            args += listOf("-filter:a", "volume=$volume")
        }
        if (combine) {
            args += listOf("-map", videoMap)
            if (settings.audio != AudioOption.MUTE) {
                args += listOf("-map", "1:a:0")
            }
            if (outputDurationMs > 0L) {
                args += listOf("-t", formatFfmpegSeconds(outputDurationMs))
            }
            args += "-shortest"
        } else if (settings.audio == AudioOption.MUTE || !source.hasAudio) {
            args += listOf("-map", "0:v:0")
        } else {
            // Keep every audio stream (isolated Voice + System recordings).
            args += listOf("-map", "0:v:0", "-map", "0:a")
        }
        args += audioCodecArgs(settings, source, settings.audio, changeVolume)
        if (settings.fastStart && !settings.usesWebm()) {
            args += listOf("-movflags", "+faststart")
        }
        args += output
        return EncodePlan(
            args = FfmpegMuxCommands.ensureGrayscale(
                ExtraArgsSanitizer.insert(args, settings.ffmpegExtraArgs),
                settings.grayscale,
            ),
            videoEncoder = encoder,
            pixFmt = pixFmt,
            outputHeight = outH,
            outputWidth = outW,
            videoBitrateKbps = videoBitrate,
            audioBitrateKbps = budgetAudioBitrateKbps(source, settings).let { kbps ->
                if (settings.audio == AudioOption.COPY && !settings.hasTargetSize()) 0 else kbps
            },
            firstPassArgs = firstPassArgs,
        )
    }

    fun fallbackPlan(
        previous: EncodePlan,
        input: String,
        output: String,
        settings: EncodeSettings,
        source: SourceVideo,
        capabilities: EncoderCapabilities,
        audioInput: String? = null,
        passLogPrefix: String? = null,
    ): EncodePlan? {
        if (settings.audioOutput(source.hasVideo) || previous.videoEncoder.isBlank()) return null
        fun next(encoder: String? = null, pix: String? = null) = build(
            input, output, settings, source, capabilities,
            pixFmtOverride = pix,
            encoderOverride = encoder,
            audioInput = audioInput,
            passLogPrefix = passLogPrefix,
        )
        if (settings.usesWebm()) {
            return when {
                previous.videoEncoder == "av1_mediacodec" && capabilities.hasLibaomAv1 -> next("libaom-av1")
                previous.videoEncoder == "av1_mediacodec" && capabilities.hasLibSvtAv1 -> next("libsvtav1")
                previous.videoEncoder == "av1_mediacodec" &&
                    (capabilities.hasLibvpxVp9 || capabilities.hasLibvpx) -> next("libvpx-vp9")
                previous.videoEncoder == "libaom-av1" && (capabilities.hasLibvpxVp9 || capabilities.hasLibvpx) ->
                    next("libvpx-vp9")
                previous.videoEncoder == "libsvtav1" && (capabilities.hasLibvpxVp9 || capabilities.hasLibvpx) ->
                    next("libvpx-vp9")
                previous.videoEncoder == "vp9_mediacodec" && (capabilities.hasLibvpxVp9 || capabilities.hasLibvpx) ->
                    next("libvpx-vp9")
                previous.videoEncoder == "vp8_mediacodec" && capabilities.hasLibvpx -> next("libvpx")
                previous.videoEncoder == "libvpx-vp9" && capabilities.hasLibvpx -> next("libvpx")
                else -> null
            }
        }
        return when {
            previous.pixFmt == "nv12" -> next(previous.videoEncoder, "yuv420p")
            previous.videoEncoder == "av1_mediacodec" && capabilities.hasLibaomAv1 -> next("libaom-av1")
            previous.videoEncoder == "av1_mediacodec" && capabilities.hasLibSvtAv1 -> next("libsvtav1")
            previous.videoEncoder.endsWith("_mediacodec") && capabilities.hasOpenH264 -> next("libopenh264")
            previous.videoEncoder != "mpeg4" && capabilities.hasMpeg4 -> next("mpeg4")
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
        args += audioCodecArgs(settings, source, audio, changeVolume)
        if (settings.fastStart && !settings.usesWebm()) {
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
            audioBitrateKbps = budgetAudioBitrateKbps(source, settings.copy(audio = audio)),
        )
    }

    private fun audioCodecArgs(
        settings: EncodeSettings,
        source: SourceVideo,
        audio: AudioOption,
        changeVolume: Boolean,
    ): List<String> {
        val encoder = audioEncoderName(settings)
        if (audio == AudioOption.MUTE || !source.hasAudio) return listOf("-an")
        if (audio == AudioOption.COPY &&
            !settings.hasTargetSize() &&
            !changeVolume &&
            settings.canCopyAudio(source)
        ) {
            return listOf("-c:a", "copy")
        }
        val kbps = budgetAudioBitrateKbps(source, settings.copy(audio = audio))
        return listOf("-c:a", encoder, "-b:a", "${kbps}k")
    }

    private fun appendCombineInputs(
        args: MutableList<String>,
        visual: String,
        audio: String,
        source: SourceVideo,
        clip: Media3ClipWindow,
        outputDurationMs: Long,
    ) {
        val duration = if (outputDurationMs > 0L) formatFfmpegSeconds(outputDurationMs) else null
        if (source.stillImage) {
            val fps = stillFrameRate(source)
            args += listOf("-loop", "1", "-framerate", fps)
            if (duration != null) args += listOf("-t", duration)
            args += listOf("-i", visual)
            if (clip.startMs > 0L) args += listOf("-ss", formatFfmpegSeconds(clip.startMs))
            args += listOf("-i", audio)
        } else {
            if (clip.startMs > 0L) args += listOf("-ss", formatFfmpegSeconds(clip.startMs))
            if (duration != null) args += listOf("-t", duration)
            args += listOf("-i", visual)
            if (clip.startMs > 0L) args += listOf("-ss", formatFfmpegSeconds(clip.startMs))
            if (duration != null) args += listOf("-t", duration)
            args += listOf("-i", audio)
        }
    }

    private fun stillFrameRate(source: SourceVideo): String {
        val fps = source.frameRate.takeIf { it >= 1f } ?: 30f
        return fps.roundToInt().coerceIn(1, 60).toString()
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
