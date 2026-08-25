package com.androidcompress.app.asr

import com.androidcompress.app.data.EncodeSettings
import com.androidcompress.app.data.OutputMode
import com.androidcompress.app.data.usesWebm
import com.androidcompress.app.data.wantsCaptions
import com.androidcompress.app.data.EncodeResult
import com.androidcompress.app.encode.FfmpegGateway
import com.androidcompress.app.encode.FfmpegMuxCommands
import com.androidcompress.app.encode.OpusCodecPrivate
import com.androidcompress.app.encode.quoteArgs
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
)

class CaptionPass(
    private val ffmpeg: FfmpegGateway,
    private val models: WhisperModelStore,
    private val captioner: WhisperCaptioner,
) {
    suspend fun apply(
        media: File,
        settings: EncodeSettings,
        workDir: File,
        stem: String,
        onProgress: (Float, String) -> Unit,
        isCancelled: () -> Boolean,
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
        onProgress(0.02f, "Downloading speech model")
        models.ensureReady(
            onProgress = { onProgress(0.02f + 0.18f * it.coerceIn(0f, 1f), "Downloading speech model") },
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
            onProgress(0.22f, "Extracting audio")
            if (!extractPcm(media, pcm, log, onProgress, isCancelled)) {
                return@withContext CaptionOutcome(media, null, false, 0, log.toString())
            }
            onProgress(0.28f, "Transcribing")
            val cues = captioner.transcribe(
                pcm = pcm,
                onProgress = { onProgress(0.28f + 0.62f * it.coerceIn(0f, 1f), "Transcribing") },
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
                onProgress(1f, "Transcribing")
                return@withContext CaptionOutcome(media, srt, false, cues.size, log.toString())
            }
            onProgress(0.92f, "Muxing captions")
            if (settings.usesWebm()) {
                val repaired = runCatching { OpusCodecPrivate.repairWebmFile(media) }.getOrDefault(false)
                if (repaired) log.appendLine("opus CodecPrivate rewritten for FFmpeg")
            }
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
                onProgress(1f, "Muxing captions")
                log.appendLine("muxed subtitles into ${finalFile.name}")
                return@withContext CaptionOutcome(finalFile, srt, true, cues.size, log.toString())
            }
            log.appendLine("subtitle mux failed: ${mux.error ?: "unknown"}")
            onProgress(1f, "Transcribing")
            CaptionOutcome(media, srt, false, cues.size, log.toString())
        } finally {
            pcm.delete()
            if (muxed.exists() && muxed != media) muxed.delete()
        }
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
        val decoded = runCatching {
            MediaCodecPcmExtractor.extractS16leMono16k(
                input = media,
                output = pcm,
                isCancelled = isCancelled,
                onProgress = { onProgress(0.22f + 0.06f * it.coerceIn(0f, 1f), "Extracting audio") },
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
            onStats = {},
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
}
