package com.androidcompress.app.encode

import java.util.Locale
import kotlin.math.abs

data class RecordingCrop(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
) {
    val isUsable: Boolean get() = width >= 2 && height >= 2
}

object FfmpegMuxCommands {
    fun copyVideoAac(videoPath: String, audioPath: String, outputPath: String): List<String> = listOf(
        "-y", "-hide_banner",
        "-i", videoPath,
        "-i", audioPath,
        "-c:v", "copy",
        "-c:a", "aac",
        "-b:a", "128k",
        "-shortest",
        "-movflags", "+faststart",
        outputPath,
    )

    fun mixMicAndInternalAac(
        videoPath: String,
        internalWav: String,
        micWav: String,
        outputPath: String,
    ): List<String> = listOf(
        "-y", "-hide_banner",
        "-i", videoPath,
        "-i", internalWav,
        "-i", micWav,
        "-filter_complex", MIX_FILTER,
        "-map", "0:v",
        "-map", "[a]",
        "-c:v", "copy",
        "-c:a", "aac",
        "-b:a", "160k",
        "-ac", "2",
        "-ar", "44100",
        "-shortest",
        "-movflags", "+faststart",
        outputPath,
    )

    /**
     * Post-process a screen recording: optional region crop, optional PCM mux/mix,
     * optional gain/duck. Returns null when the MediaRecorder file is already final.
     */
    fun recordingPostProcess(
        videoPath: String,
        outputPath: String,
        internalWav: String? = null,
        micWav: String? = null,
        crop: RecordingCrop? = null,
        videoHasAudio: Boolean = false,
        internalGainPercent: Int = 100,
        micGainPercent: Int = 100,
        duckAppAudio: Boolean = false,
        videoEncoder: String = "libopenh264",
        videoBitrateKbps: Int = 4_000,
    ): List<String>? {
        val hasInternal = !internalWav.isNullOrBlank()
        val hasMicWav = !micWav.isNullOrBlank()
        val cropOn = crop?.takeIf { it.isUsable }
        val iGain = formatGain(internalGainPercent)
        val mGain = formatGain(micGainPercent)
        val micVol = !gainIsUnity(micGainPercent)
        val needsWork = cropOn != null ||
            hasInternal ||
            hasMicWav ||
            (videoHasAudio && (micVol || duckAppAudio))
        if (!needsWork) return null

        val args = mutableListOf("-y", "-hide_banner", "-i", videoPath)
        val internalPath = internalWav.takeIf { hasInternal }
        val micPath = micWav.takeIf { hasMicWav }
        if (internalPath != null) args += listOf("-i", internalPath)
        if (micPath != null) args += listOf("-i", micPath)
        val cropFilter = cropOn?.let { "crop=${it.width}:${it.height}:${it.x}:${it.y}" }
        val vEncode = cropOn != null

        when {
            hasInternal && hasMicWav -> {
                val mix = mixFilter(internalGainPercent, micGainPercent, duckAppAudio)
                val fc = if (cropFilter != null) "[0:v]$cropFilter[v];$mix" else mix
                args += listOf("-filter_complex", fc)
                args += listOf("-map", if (cropFilter != null) "[v]" else "0:v", "-map", "[a]")
                if (vEncode) args += videoEncode(videoEncoder, videoBitrateKbps) else args += listOf("-c:v", "copy")
                args += listOf("-c:a", "aac", "-b:a", "160k", "-ac", "2", "-ar", "44100", "-shortest")
            }
            hasInternal || hasMicWav -> {
                val wavIndex = "1:a"
                val vol = if (hasInternal) iGain else mGain
                if (cropFilter != null) {
                    val af = if (vol != "1.00") "[$wavIndex]volume=$vol[a]" else null
                    val fc = if (af == null) "[0:v]$cropFilter[v]" else "[0:v]$cropFilter[v];$af"
                    args += listOf("-filter_complex", fc)
                    args += listOf("-map", "[v]")
                    args += if (af == null) listOf("-map", wavIndex) else listOf("-map", "[a]")
                    args += videoEncode(videoEncoder, videoBitrateKbps)
                } else {
                    args += listOf("-map", "0:v", "-map", wavIndex, "-c:v", "copy")
                    if (vol != "1.00") args += listOf("-filter:a", "volume=$vol")
                }
                args += listOf("-c:a", "aac", "-b:a", "128k", "-shortest")
            }
            else -> {
                when {
                    cropFilter != null && videoHasAudio && micVol -> {
                        args += listOf(
                            "-filter_complex",
                            "[0:v]$cropFilter[v];[0:a]volume=$mGain[a]",
                            "-map", "[v]",
                            "-map", "[a]",
                        )
                        args += videoEncode(videoEncoder, videoBitrateKbps)
                        args += listOf("-c:a", "aac", "-b:a", "128k")
                    }
                    cropFilter != null -> {
                        args += listOf("-vf", cropFilter)
                        args += videoEncode(videoEncoder, videoBitrateKbps)
                        if (videoHasAudio) args += listOf("-c:a", "copy")
                    }
                    else -> {
                        args += listOf("-c:v", "copy", "-filter:a", "volume=$mGain", "-c:a", "aac", "-b:a", "128k")
                    }
                }
            }
        }
        args += listOf("-movflags", "+faststart", outputPath)
        return args
    }

    fun mixFilter(
        internalGainPercent: Int = 100,
        micGainPercent: Int = 100,
        duckAppAudio: Boolean = false,
    ): String {
        val gI = formatGain(internalGainPercent)
        val gM = formatGain(micGainPercent)
        if (gI == "1.00" && gM == "1.00" && !duckAppAudio) return MIX_FILTER
        return buildString {
            append("[1:a]aformat=sample_fmts=fltp:sample_rates=44100:channel_layouts=stereo,volume=$gI[i];")
            append("[2:a]aformat=sample_fmts=fltp:sample_rates=44100:channel_layouts=stereo,volume=$gM[m];")
            if (duckAppAudio) {
                append("[m]asplit=2[mic][sc];")
                append("[i][sc]sidechaincompress=threshold=0.05:ratio=8:attack=50:release=300:makeup=1[ducked];")
                append("[ducked][mic]amix=inputs=2:duration=longest:dropout_transition=0:normalize=0[a]")
            } else {
                append("[i][m]amix=inputs=2:duration=longest:dropout_transition=0:normalize=0[a]")
            }
        }
    }

    fun formatGain(percent: Int): String =
        String.format(Locale.US, "%.2f", percent.coerceIn(0, 200) / 100.0)

    fun gainIsUnity(percent: Int): Boolean = abs(percent - 100) < 1

    const val MIX_FILTER =
        "[1:a]aformat=sample_fmts=fltp:sample_rates=44100:channel_layouts=stereo[i];" +
            "[2:a]aformat=sample_fmts=fltp:sample_rates=44100:channel_layouts=stereo[m];" +
            "[i][m]amix=inputs=2:duration=longest:dropout_transition=0:normalize=0[a]"

    private fun videoEncode(encoder: String, bitrateKbps: Int): List<String> = listOf(
        "-c:v", encoder.ifBlank { "libopenh264" },
        "-pix_fmt", "yuv420p",
        "-b:v", "${bitrateKbps.coerceIn(400, 20_000)}k",
        "-r", "30",
    )
}
