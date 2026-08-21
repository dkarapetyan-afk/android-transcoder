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
     * Post-process a screen recording: optional region crop, optional status-bar
     * cover, optional PCM mux/mix, optional gain/duck. Returns null when the
     * MediaRecorder file is already final.
     */
    fun recordingPostProcess(
        videoPath: String,
        outputPath: String,
        internalWav: String? = null,
        micWav: String? = null,
        crop: RecordingCrop? = null,
        coverTopPx: Int = 0,
        videoHasAudio: Boolean = false,
        internalGainPercent: Int = 100,
        micGainPercent: Int = 100,
        duckAppAudio: Boolean = false,
        videoEncoder: String = "libopenh264",
        videoBitrateKbps: Int = 4_000,
        frameRate: Int = 30,
        containerWebm: Boolean = false,
        applyGain: Boolean = true,
        isolateTracks: Boolean = false,
    ): List<String>? {
        val hasInternal = !internalWav.isNullOrBlank()
        val hasMicWav = !micWav.isNullOrBlank()
        val cropOn = crop?.takeIf { it.isUsable }
        val coverPx = coverTopPx.coerceAtLeast(0)
        val iGain = formatGain(internalGainPercent)
        val mGain = formatGain(micGainPercent)
        val micVol = applyGain && !gainIsUnity(micGainPercent)
        val intVol = applyGain && !gainIsUnity(internalGainPercent)
        val isolate = isolateTracks && hasInternal && hasMicWav
        val videoFilter = buildVideoFilter(cropOn, coverPx)
        val vEncode = videoFilter != null
        val needsWork = videoFilter != null ||
            hasInternal ||
            hasMicWav ||
            (videoHasAudio && (micVol || duckAppAudio))
        if (!needsWork) return null

        val args = mutableListOf("-y", "-hide_banner", "-i", videoPath)
        val internalPath = internalWav.takeIf { hasInternal }
        val micPath = micWav.takeIf { hasMicWav }
        if (isolate) {
            args += listOf("-i", micPath!!, "-i", internalPath!!)
        } else {
            if (internalPath != null) args += listOf("-i", internalPath)
            if (micPath != null) args += listOf("-i", micPath)
        }

        when {
            isolate -> {
                val micAf = if (applyGain && micVol) "[1:a]volume=$mGain[a0]" else null
                val intAf = if (applyGain && intVol) "[2:a]volume=$iGain[a1]" else null
                val filters = buildList {
                    if (videoFilter != null) add("[0:v]$videoFilter[v]")
                    if (micAf != null) add(micAf)
                    if (intAf != null) add(intAf)
                }
                if (filters.isNotEmpty()) {
                    args += listOf("-filter_complex", filters.joinToString(";"))
                }
                args += listOf("-map", if (videoFilter != null) "[v]" else "0:v")
                args += listOf("-map", if (micAf != null) "[a0]" else "1:a")
                args += listOf("-map", if (intAf != null) "[a1]" else "2:a")
                if (vEncode) {
                    args += videoEncode(videoEncoder, videoBitrateKbps, frameRate)
                } else {
                    args += listOf("-c:v", "copy")
                }
                args += audioEncode(containerWebm, "128k")
                args += listOf(
                    "-metadata:s:a:0", "title=$TRACK_TITLE_VOICE",
                    "-metadata:s:a:1", "title=$TRACK_TITLE_SYSTEM",
                    "-disposition:a:0", "default",
                    "-disposition:a:1", "0",
                )
                args += "-shortest"
            }
            hasInternal && hasMicWav -> {
                val mix = mixFilter(internalGainPercent, micGainPercent, duckAppAudio)
                val fc = if (videoFilter != null) "[0:v]$videoFilter[v];$mix" else mix
                args += listOf("-filter_complex", fc)
                args += listOf("-map", if (videoFilter != null) "[v]" else "0:v", "-map", "[a]")
                if (vEncode) args += videoEncode(videoEncoder, videoBitrateKbps, frameRate) else args += listOf("-c:v", "copy")
                args += audioEncode(containerWebm, "160k")
                args += "-shortest"
            }
            hasInternal || hasMicWav -> {
                val wavIndex = "1:a"
                val vol = if (hasInternal) iGain else mGain
                val applyVol = applyGain && vol != "1.00" && (if (hasInternal) intVol else micVol)
                if (videoFilter != null) {
                    val af = if (applyVol) "[$wavIndex]volume=$vol[a]" else null
                    val fc = if (af == null) "[0:v]$videoFilter[v]" else "[0:v]$videoFilter[v];$af"
                    args += listOf("-filter_complex", fc)
                    args += listOf("-map", "[v]")
                    args += if (af == null) listOf("-map", wavIndex) else listOf("-map", "[a]")
                    args += videoEncode(videoEncoder, videoBitrateKbps, frameRate)
                } else {
                    args += listOf("-map", "0:v", "-map", wavIndex, "-c:v", "copy")
                    if (applyVol) args += listOf("-filter:a", "volume=$vol")
                }
                args += audioEncode(containerWebm, "128k")
                args += "-shortest"
            }
            else -> {
                when {
                    videoFilter != null && videoHasAudio && micVol -> {
                        args += listOf(
                            "-filter_complex",
                            "[0:v]$videoFilter[v];[0:a]volume=$mGain[a]",
                            "-map", "[v]",
                            "-map", "[a]",
                        )
                        args += videoEncode(videoEncoder, videoBitrateKbps, frameRate)
                        args += audioEncode(containerWebm, "128k")
                    }
                    videoFilter != null -> {
                        args += listOf("-vf", videoFilter)
                        args += videoEncode(videoEncoder, videoBitrateKbps, frameRate)
                        if (videoHasAudio) args += listOf("-c:a", "copy")
                    }
                    else -> {
                        args += listOf("-c:v", "copy", "-filter:a", "volume=$mGain")
                        args += audioEncode(containerWebm, "128k")
                    }
                }
            }
        }
        if (!containerWebm) args += listOf("-movflags", "+faststart")
        args += outputPath
        return args
    }

    fun applyChapters(
        videoPath: String,
        metadataPath: String,
        outputPath: String,
        containerWebm: Boolean = false,
    ): List<String> = buildList {
        addAll(
            listOf(
                "-y", "-hide_banner",
                "-i", videoPath,
                "-i", metadataPath,
                "-map_metadata", "1",
                "-map", "0",
                "-c", "copy",
            ),
        )
        if (!containerWebm) addAll(listOf("-movflags", "+faststart"))
        add(outputPath)
    }

    fun copySegment(
        videoPath: String,
        outputPath: String,
        startMs: Long,
        endMs: Long,
    ): List<String> = listOf(
        "-y", "-hide_banner",
        "-ss", seconds(startMs),
        "-to", seconds(endMs),
        "-i", videoPath,
        "-c", "copy",
        "-avoid_negative_ts", "make_zero",
        outputPath,
    )

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

    const val TRACK_TITLE_VOICE = "Voice"
    const val TRACK_TITLE_SYSTEM = "System"

    fun buildVideoFilter(crop: RecordingCrop?, coverTopPx: Int): String? {
        val parts = buildList {
            if (crop != null && crop.isUsable) {
                add("crop=${crop.width}:${crop.height}:${crop.x}:${crop.y}")
            }
            if (coverTopPx > 0) {
                add("drawbox=x=0:y=0:w=iw:h=$coverTopPx:color=black:t=fill")
            }
        }
        return parts.takeIf { it.isNotEmpty() }?.joinToString(",")
    }

    private fun videoEncode(encoder: String, bitrateKbps: Int, frameRate: Int = 30): List<String> = listOf(
        "-c:v", encoder.ifBlank { "libopenh264" },
        "-pix_fmt", "yuv420p",
        "-b:v", "${bitrateKbps.coerceIn(400, 20_000)}k",
        "-r", "${if (frameRate >= 45) 60 else 30}",
    )

    private fun audioEncode(webm: Boolean, bitrate: String): List<String> =
        if (webm) {
            listOf("-c:a", "libopus", "-b:a", bitrate, "-ac", "2", "-ar", "48000")
        } else {
            listOf("-c:a", "aac", "-b:a", bitrate, "-ac", "2", "-ar", "44100")
        }

    private fun seconds(ms: Long): String =
        String.format(Locale.US, "%.3f", ms.coerceAtLeast(0L) / 1000.0)
}
