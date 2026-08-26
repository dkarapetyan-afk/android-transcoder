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

/** Timed line for FFmpeg `drawtext` burn-in when the `subtitles` filter is unavailable. */
data class BurnCaptionCue(
    val startSec: Double,
    val endSec: Double,
    val text: String,
)

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
        grayscale: Boolean = false,
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
        val videoFilter = buildVideoFilter(cropOn, coverPx, grayscale)
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

    fun extractPcmS16le(inputPath: String, outputPath: String): List<String> = listOf(
        "-y", "-hide_banner",
        "-i", inputPath,
        "-vn",
        "-ac", "1",
        "-ar", "16000",
        "-f", "s16le",
        "-acodec", "pcm_s16le",
        outputPath,
    )

    fun applySubtitles(
        videoPath: String,
        srtPath: String,
        outputPath: String,
        containerWebm: Boolean = false,
    ): List<String> = buildList {
        addAll(
            listOf(
                "-y", "-hide_banner",
                "-i", videoPath,
                "-i", srtPath,
                "-map", "0:v?",
                "-map", "0:a?",
                "-map", "1",
                "-c:v", "copy",
                "-c:a", "copy",
                "-c:s", if (containerWebm) "webvtt" else "mov_text",
                "-metadata:s:s:0", "language=eng",
                "-disposition:s:0", "default",
            ),
        )
        if (!containerWebm) addAll(listOf("-movflags", "+faststart"))
        add(outputPath)
    }

    /**
     * Re-encode video so SRT cues are painted on the frames at their timestamps.
     * Audio is stream-copied. Never uses h264/vp8/vp9_mediacodec.
     */
    fun burnCaptions(
        videoPath: String,
        outputPath: String,
        videoFilter: String,
        videoEncoder: String,
        videoBitrateKbps: Int,
        containerWebm: Boolean = false,
    ): List<String> {
        val encoder = videoEncoder.ifBlank { "libopenh264" }
        check(encoder != "h264_mediacodec" && encoder != "vp8_mediacodec" && encoder != "vp9_mediacodec") {
            "$encoder writes scrambled frames"
        }
        val kbps = videoBitrateKbps.coerceIn(400, 20_000)
        return buildList {
            addAll(listOf("-y", "-hide_banner", "-stats_period", "0.25", "-i", videoPath))
            addAll(listOf("-vf", videoFilter))
            addAll(listOf("-map", "0:v:0", "-map", "0:a?"))
            addAll(listOf("-c:v", encoder, "-b:v", "${kbps}k", "-pix_fmt", "yuv420p"))
            addAll(burnEncoderTune(encoder, kbps, containerWebm))
            addAll(listOf("-c:a", "copy"))
            if (!containerWebm) addAll(listOf("-movflags", "+faststart"))
            add(outputPath)
        }
    }

    fun subtitleBurnFilter(srtPath: String, fontsDir: String = ANDROID_FONTS_DIR): String {
        val file = escapeFilterPath(srtPath)
        val fonts = escapeFilterPath(fontsDir)
        val style = "Fontname=Roboto,Fontsize=16,PrimaryColour=&H00FFFFFF," +
            "OutlineColour=&H00000000,BorderStyle=1,Outline=1.6,Shadow=0,Alignment=2,MarginV=28"
        return "subtitles=filename=$file:charenc=UTF-8:fontsdir=$fonts:force_style='$style'"
    }

    fun drawTextBurnFilter(
        cues: List<BurnCaptionCue>,
        fontFile: String,
        maxCues: Int = MAX_DRAWTEXT_CUES,
    ): String? {
        if (cues.isEmpty() || fontFile.isBlank()) return null
        val font = escapeFilterPath(fontFile)
        return cues.take(maxCues).joinToString(",") { cue ->
            val text = escapeDrawText(cue.text)
            val start = String.format(Locale.US, "%.3f", cue.startSec.coerceAtLeast(0.0))
            val end = String.format(Locale.US, "%.3f", cue.endSec.coerceAtLeast(cue.startSec + 0.05))
            "drawtext=fontfile=$font:text='$text':x=(w-text_w)/2:y=h-th-(h*0.06):" +
                "fontsize=h/18:fontcolor=white:borderw=2:bordercolor=black:" +
                "enable='between(t,$start,$end)'"
        }.takeIf { it.isNotBlank() }
    }

    /** Escape a filesystem path for an FFmpeg filter option. Do not wrap in quotes. */
    fun escapeFilterPath(path: String): String = buildString(path.length + 8) {
        for (ch in path) {
            if (ch == '\\' || ch == '\'' || ch == ':' || ch == '[' || ch == ']' ||
                ch == ',' || ch == ';'
            ) {
                append('\\')
            }
            append(ch)
        }
    }

    fun escapeDrawText(text: String): String {
        val folded = text.trim().replace(Regex("[\\r\\n]+"), " ").replace(Regex("\\s+"), " ")
        return buildString(folded.length + 8) {
            for (ch in folded) {
                when (ch) {
                    '\\', '\'', ':', '[', ']', ',', '%', ';' -> append('\\')
                }
                append(ch)
            }
        }
    }

    const val ANDROID_FONTS_DIR = "/system/fonts"
    const val MAX_DRAWTEXT_CUES = 200

    internal fun burnEncoderTune(encoder: String, bitrateKbps: Int, containerWebm: Boolean): List<String> =
        buildList {
            when (encoder) {
                "libvpx", "libvpx-vp9" -> {
                    addAll(listOf("-deadline", "good", "-cpu-used", "5", "-row-mt", "1"))
                    if (encoder == "libvpx") addAll(listOf("-auto-alt-ref", "0"))
                }
                "libaom-av1" -> addAll(
                    listOf("-usage", "realtime", "-cpu-used", "8", "-row-mt", "1", "-tiles", "2x2"),
                )
                "libsvtav1" -> addAll(listOf("-preset", "10"))
                "hevc_mediacodec" -> addAll(listOf("-tag:v", "hvc1"))
                "av1_mediacodec" -> if (!containerWebm) addAll(listOf("-tag:v", "av01"))
                "libopenh264", "mpeg4" -> addAll(
                    listOf("-maxrate", "${bitrateKbps}k", "-bufsize", "${bitrateKbps * 2}k"),
                )
            }
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
    /**
     * Works on YUV imports and RGB stills (combine picture + audio).
     * `hue=s=0` skips planar YUV; `lutyuv` skips RGB JPEGs/PNGs.
     */
    const val GRAYSCALE_FILTER = "format=gray"

    fun buildVideoFilter(crop: RecordingCrop?, coverTopPx: Int, grayscale: Boolean = false): String? {
        val parts = buildList {
            if (crop != null && crop.isUsable) {
                add("crop=${crop.width}:${crop.height}:${crop.x}:${crop.y}")
            }
            if (coverTopPx > 0) {
                add("drawbox=x=0:y=0:w=iw:h=$coverTopPx:color=black:t=fill")
            }
            if (grayscale) add(GRAYSCALE_FILTER)
        }
        return parts.takeIf { it.isNotEmpty() }?.joinToString(",")
    }

    fun hasGrayscaleFilter(vf: String): Boolean {
        if (vf.isBlank()) return false
        return vf.contains(GRAYSCALE_FILTER) ||
            vf.contains("hue=s=0") ||
            vf.split(',').any { it.trim().startsWith("format=gray") }
    }

    /**
     * Make sure grayscale is on the video filter FFmpeg will actually run.
     * Extra `-vf` / command overrides replace an earlier filter graph, so this
     * appends to the last `-vf`/`-filter:v` instead of adding a second flag.
     */
    fun ensureGrayscale(args: List<String>, enabled: Boolean): List<String> {
        if (!enabled || args.isEmpty() || args.contains("-vn")) return args
        val vfFlags = setOf("-vf", "-filter:v")
        val valueIdxs = args.indices.mapNotNull { i ->
            if (args[i] in vfFlags && i + 1 < args.size && !args[i + 1].startsWith("-")) i + 1 else null
        }
        if (valueIdxs.isNotEmpty()) {
            val last = valueIdxs.last()
            if (hasGrayscaleFilter(args[last])) return args
            return args.toMutableList().also { it[last] = "${args[last]},$GRAYSCALE_FILTER" }
        }
        val fcIdx = args.indexOf("-filter_complex")
        if (fcIdx >= 0 && fcIdx + 1 < args.size && !args[fcIdx + 1].startsWith("-")) {
            val fc = args[fcIdx + 1]
            if (hasGrayscaleFilter(fc)) return args
            return args.toMutableList().also { it[fcIdx + 1] = appendGrayscaleToVideoChain(fc) }
        }
        val at = args.lastIndex.coerceAtLeast(0)
        return args.toMutableList().also {
            it.addAll(at, listOf("-vf", GRAYSCALE_FILTER))
        }
    }

    internal fun appendGrayscaleToVideoChain(filterComplex: String): String {
        val match = Regex("\\[0:v]([^;]*?)(\\[v])").find(filterComplex)
        if (match != null) {
            val filters = match.groupValues[1].trim().trim(',')
            val inserted = if (filters.isEmpty()) GRAYSCALE_FILTER else "$filters,$GRAYSCALE_FILTER"
            return filterComplex.replaceRange(match.range, "[0:v]$inserted[v]")
        }
        return filterComplex
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
