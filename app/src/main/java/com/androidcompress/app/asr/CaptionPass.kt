package com.androidcompress.app.asr

import android.content.Context
import android.media.MediaMetadataRetriever
import com.androidcompress.app.R
import com.androidcompress.app.data.EncodeResult
import com.androidcompress.app.data.EncodeSettings
import com.androidcompress.app.data.EncodeStats
import com.androidcompress.app.data.EncoderCapabilities
import com.androidcompress.app.data.OutputMode
import com.androidcompress.app.data.VideoCodec
import com.androidcompress.app.data.effectiveVideoCodec
import com.androidcompress.app.data.usesWebm
import com.androidcompress.app.data.wantsBurnCaptions
import com.androidcompress.app.data.wantsCaptions
import com.androidcompress.app.encode.BurnCaptionCue
import com.androidcompress.app.encode.FfmpegCommandBuilder
import com.androidcompress.app.encode.FfmpegGateway
import com.androidcompress.app.encode.FfmpegMuxCommands
import com.androidcompress.app.encode.OpusCodecPrivate
import com.androidcompress.app.encode.quoteArgs
import com.androidcompress.app.util.AppLog
import com.androidcompress.app.util.runCatchingLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class CaptionOutcome(
    val media: File,
    val srt: File?,
    val muxed: Boolean,
    val cueCount: Int,
    val log: String,
    val burned: Boolean = false,
)

class CaptionPass(
    private val context: Context,
    private val ffmpeg: FfmpegGateway,
    private val models: WhisperModelStore,
    private val captioner: WhisperCaptioner,
) {
    private fun phase(id: Int): String = context.getString(id)
    suspend fun apply(
        media: File,
        settings: EncodeSettings,
        workDir: File,
        stem: String,
        onProgress: (Float, String) -> Unit,
        isCancelled: () -> Boolean,
        capabilities: EncoderCapabilities? = null,
    ): CaptionOutcome = withContext(Dispatchers.IO) {
        val log = StringBuilder()
        if (!settings.wantsCaptions()) {
            return@withContext CaptionOutcome(media, null, false, 0, "captions skipped")
        }
        if (!media.isFile || media.length() < 1024) {
            return@withContext CaptionOutcome(media, null, false, 0, "captions skipped; media missing")
        }
        fun checkCancel() {
            if (isCancelled()) error("cancelled")
        }
        checkCancel()
        onProgress(0.02f, phase(R.string.captions_phase_download))
        models.ensureReady(
            onProgress = { onProgress(0.02f + 0.18f * it.coerceIn(0f, 1f), phase(R.string.captions_phase_download)) },
            isCancelled = isCancelled,
        )
        log.appendLine("whisper tiny model ready")
        checkCancel()
        if (!workDir.exists()) workDir.mkdirs()
        val pcm = File(workDir, "$stem.pcm")
        val srt = File(workDir, "$stem.srt")
        val muxed = File(workDir, "$stem-captions.${media.extension.ifBlank { "mp4" }}")
        pcm.delete()
        srt.delete()
        muxed.delete()
        try {
            onProgress(0.22f, phase(R.string.captions_phase_extract))
            if (!extractPcm(media, pcm, log, onProgress, isCancelled)) {
                return@withContext CaptionOutcome(media, null, false, 0, log.toString())
            }
            onProgress(0.28f, phase(R.string.captions_phase_transcribe))
            val transcribeEnd = if (settings.wantsBurnCaptions()) 0.58f else 0.90f
            val cues = captioner.transcribe(
                pcm = pcm,
                onProgress = {
                    onProgress(
                        0.28f + (transcribeEnd - 0.28f) * it.coerceIn(0f, 1f),
                        phase(R.string.captions_phase_transcribe),
                    )
                },
                isCancelled = isCancelled,
            )
            log.appendLine("cues=${cues.size}")
            if (cues.isEmpty()) {
                log.appendLine("no speech")
                return@withContext CaptionOutcome(media, null, false, 0, log.toString())
            }
            srt.writeText(SrtWriter.render(cues), Charsets.UTF_8)
            val audioOnly = settings.output == OutputMode.AUDIO
            if (audioOnly) {
                onProgress(1f, phase(R.string.captions_phase_transcribe))
                return@withContext CaptionOutcome(media, srt, false, cues.size, log.toString())
            }
            if (settings.usesWebm()) {
                val repaired = runCatchingLog(TAG, "repair opus header") {
                    OpusCodecPrivate.repairWebmFile(media)
                }.getOrDefault(false)
                if (repaired) log.appendLine("opus CodecPrivate rewritten for FFmpeg")
            }
            if (settings.wantsBurnCaptions()) {
                val burned = burnIn(
                    media = media,
                    srt = srt,
                    cues = cues,
                    settings = settings,
                    workDir = workDir,
                    stem = stem,
                    log = log,
                    onProgress = onProgress,
                    isCancelled = isCancelled,
                    capabilities = capabilities,
                )
                if (burned != null) {
                    onProgress(1f, phase(R.string.captions_phase_burn))
                    return@withContext CaptionOutcome(
                        media = burned,
                        srt = srt,
                        muxed = false,
                        cueCount = cues.size,
                        log = log.toString(),
                        burned = true,
                    )
                }
                log.appendLine("burn failed; muxing subtitles")
            }
            onProgress(0.92f, phase(R.string.captions_phase_mux))
            val muxArgs = FfmpegMuxCommands.applySubtitles(
                videoPath = media.absolutePath,
                srtPath = srt.absolutePath,
                outputPath = muxed.absolutePath,
                containerWebm = settings.usesWebm(),
            )
            log.appendLine("subs: ${quoteArgs(muxArgs)}")
            val mux = runFfmpeg(muxArgs, log, isCancelled, maxLogLines = 80)
            if (mux.cancelled || isCancelled()) error("cancelled")
            if (mux.success && muxed.isFile && muxed.length() > 1024) {
                media.delete()
                val finalFile = if (muxed.renameTo(media)) media else muxed
                onProgress(1f, phase(R.string.captions_phase_mux))
                log.appendLine("muxed subtitles into ${finalFile.name}")
                return@withContext CaptionOutcome(finalFile, srt, true, cues.size, log.toString())
            }
            log.appendLine("subtitle mux failed: ${mux.error ?: "unknown"}")
            onProgress(1f, phase(R.string.captions_phase_transcribe))
            CaptionOutcome(media, srt, false, cues.size, log.toString())
        } finally {
            pcm.delete()
            if (muxed.exists() && muxed != media) muxed.delete()
        }
    }

    private suspend fun burnIn(
        media: File,
        srt: File,
        cues: List<CaptionCue>,
        settings: EncodeSettings,
        workDir: File,
        stem: String,
        log: StringBuilder,
        onProgress: (Float, String) -> Unit,
        isCancelled: () -> Boolean,
        capabilities: EncoderCapabilities?,
    ): File? {
        val burned = File(workDir, "$stem-burn.${media.extension.ifBlank { "mp4" }}")
        burned.delete()
        val durationMs = mediaDurationMs(media).coerceAtLeast(
            ((cues.maxOfOrNull { it.endSec } ?: 1.0) * 1000.0).toLong().coerceAtLeast(1_000L),
        )
        val kbps = settings.videoBitrateKbps.coerceIn(400, 20_000)
        val encoders = burnEncoderCandidates(settings, capabilities ?: EncoderCapabilities())
        val filters = buildList {
            add(FfmpegMuxCommands.subtitleBurnFilter(srt.absolutePath))
            val font = systemFontFile()
            if (font != null) {
                val draw = FfmpegMuxCommands.drawTextBurnFilter(
                    cues.map { BurnCaptionCue(it.startSec, it.endSec, it.text) },
                    font,
                )
                if (draw != null) add(draw)
            }
        }
        var keep: File? = null
        try {
            filters.forEachIndexed { filterIndex, filter ->
                val label = if (filterIndex == 0) "subtitles" else "drawtext"
                val tryEncoders = if (filterIndex == 0) encoders else encoders.take(1)
                for (encoder in tryEncoders) {
                    checkCancel(isCancelled)
                    burned.delete()
                    val args = FfmpegMuxCommands.burnCaptions(
                        videoPath = media.absolutePath,
                        outputPath = burned.absolutePath,
                        videoFilter = filter,
                        videoEncoder = encoder,
                        videoBitrateKbps = kbps,
                        containerWebm = settings.usesWebm(),
                    )
                    log.appendLine("burn $label $encoder: ${quoteArgs(args)}")
                    onProgress(0.58f, phase(R.string.captions_phase_burn))
                    val result = runFfmpeg(
                        args,
                        log,
                        isCancelled,
                        maxLogLines = 48,
                        onStats = { stats ->
                            val frac = (stats.timeMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
                            onProgress(0.58f + 0.40f * frac, phase(R.string.captions_phase_burn))
                        },
                    )
                    if (result.cancelled || isCancelled()) error("cancelled")
                    if (result.success && burned.isFile && burned.length() > 1024) {
                        media.delete()
                        val finalFile = if (burned.renameTo(media)) media else burned
                        keep = finalFile
                        log.appendLine("burned captions into ${finalFile.name} encoder=$encoder via=$label")
                        return finalFile
                    }
                    log.appendLine("burn $label $encoder failed: ${result.error ?: "unknown"}")
                    burned.delete()
                }
            }
            return null
        } finally {
            if (burned.exists() && burned != media && burned != keep) burned.delete()
        }
    }

    private fun checkCancel(isCancelled: () -> Boolean) {
        if (isCancelled()) error("cancelled")
    }

    private fun burnEncoderCandidates(
        settings: EncodeSettings,
        caps: EncoderCapabilities,
    ): List<String> {
        val primary = FfmpegCommandBuilder.selectVideoEncoder(settings, caps)
        val extras = when (settings.effectiveVideoCodec()) {
            VideoCodec.H264 -> listOf("libopenh264", "mpeg4")
            VideoCodec.HEVC -> listOf("hevc_mediacodec", "libopenh264", "mpeg4")
            VideoCodec.VP8 -> listOf("libvpx")
            VideoCodec.VP9 -> listOf("libvpx-vp9", "libvpx")
            VideoCodec.AV1 -> if (settings.usesWebm()) {
                listOf("av1_mediacodec", "libaom-av1", "libvpx-vp9")
            } else {
                listOf("av1_mediacodec", "libaom-av1", "libopenh264")
            }
        }
        return (listOf(primary) + extras)
            .filter { encoder ->
                encoder != "h264_mediacodec" &&
                    encoder != "vp8_mediacodec" &&
                    encoder != "vp9_mediacodec"
            }
            .distinct()
    }

    private fun mediaDurationMs(file: File): Long {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } catch (t: Throwable) {
            AppLog.e(TAG, "media duration", t)
            0L
        } finally {
            runCatchingLog(TAG, "release retriever") { retriever.release() }
        }
    }

    private fun systemFontFile(): String? {
        val dir = File(FfmpegMuxCommands.ANDROID_FONTS_DIR)
        if (!dir.isDirectory) return null
        val names = listOf(
            "Roboto-Regular.ttf",
            "Roboto.ttf",
            "NotoSans-Regular.ttf",
            "NotoSansCJK-Regular.ttc",
            "DroidSans.ttf",
            "NotoNaskhArabic-Regular.ttf",
            "NotoSerif-Regular.ttf",
        )
        names.forEach { name ->
            val file = File(dir, name)
            if (file.isFile) return file.absolutePath
        }
        return dir.listFiles()?.firstOrNull { file ->
            val ext = file.extension.lowercase()
            file.isFile && (ext == "ttf" || ext == "otf" || ext == "ttc")
        }?.absolutePath
    }

    private fun pcmReady(pcm: File): Boolean =
        pcm.isFile && pcm.length() >= WhisperModels.SAMPLE_RATE

    private suspend fun extractPcm(
        media: File,
        pcm: File,
        log: StringBuilder,
        onProgress: (Float, String) -> Unit,
        isCancelled: () -> Boolean,
    ): Boolean {
        pcm.delete()
        val decoded = runCatchingLog(TAG, "mediacodec pcm") {
            MediaCodecPcmExtractor.extractS16leMono16k(
                input = media,
                output = pcm,
                isCancelled = isCancelled,
                onProgress = { onProgress(0.22f + 0.06f * it.coerceIn(0f, 1f), phase(R.string.captions_phase_extract)) },
            )
        }
        if (decoded.isSuccess && pcmReady(pcm)) {
            log.appendLine("pcm=mediacodec bytes=${pcm.length()}")
            return true
        }
        val decodeErr = decoded.exceptionOrNull()
        if (decodeErr is CancellationException) throw decodeErr
        if (isCancelled() || decodeErr?.message == "cancelled") error("cancelled")
        log.appendLine("mediacodec pcm: ${decoded.exceptionOrNull()?.message ?: "empty"}")
        pcm.delete()
        val extract = FfmpegMuxCommands.extractPcmS16le(media.absolutePath, pcm.absolutePath)
        log.appendLine("pcm: ${quoteArgs(extract)}")
        val extracted = runFfmpeg(extract, log, isCancelled, maxLogLines = 24)
        if (extracted.cancelled || isCancelled()) error("cancelled")
        if (extracted.success && pcmReady(pcm)) {
            log.appendLine("pcm=ffmpeg bytes=${pcm.length()}")
            return true
        }
        log.appendLine("pcm extract failed: ${extracted.error ?: "empty"}")
        return false
    }

    private suspend fun runFfmpeg(
        args: List<String>,
        log: StringBuilder,
        isCancelled: () -> Boolean,
        maxLogLines: Int = Int.MAX_VALUE,
        onStats: (EncodeStats) -> Unit = {},
    ): EncodeResult {
        if (isCancelled()) error("cancelled")
        var kept = 0
        var skipped = 0
        val session = ffmpeg.encode(
            args,
            onLog = { line ->
                if (keepFfmpegLine(line)) {
                    if (kept < maxLogLines) {
                        log.appendLine(line)
                        kept++
                    } else {
                        skipped++
                    }
                }
            },
            onStats = onStats,
        )
        return try {
            coroutineScope {
                val watch = launch {
                    while (isActive) {
                        if (isCancelled()) {
                            session.cancel()
                            return@launch
                        }
                        delay(150)
                    }
                }
                try {
                    session.await()
                } finally {
                    watch.cancel()
                }
            }
        } catch (e: CancellationException) {
            session.cancel()
            throw e
        }.also {
            if (skipped > 0) log.appendLine("... $skipped more ffmpeg lines")
        }
    }

    private fun keepFfmpegLine(line: String): Boolean {
        if (line.isBlank()) return false
        val text = line.trim()
        return when {
            text.startsWith("Extradata version") -> false
            text.contains("is not implemented. Update your FFmpeg") -> false
            text.contains("streams.videolan.org") -> false
            text.contains("ffmpeg-devel") -> false
            text.contains("Error parsing Ogg extradata") -> false
            text.contains("Failed to open codec in avformat_find_stream_info") -> false
            text.startsWith("If the problem still occurs") -> false
            text.startsWith("If you want to help, upload a sample") -> false
            else -> true
        }
    }

    private companion object {
        const val TAG = "CaptionPass"
    }
}
