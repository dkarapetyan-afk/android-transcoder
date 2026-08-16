package com.androidcompress.app.encode

import android.content.Context
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.ColorInfo
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.common.util.Clock
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.FrameDropEffect
import androidx.media3.effect.Presentation
import androidx.media3.transformer.AudioEncoderSettings
import androidx.media3.transformer.Codec
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultAssetLoaderFactory
import androidx.media3.transformer.DefaultDecoderFactory
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import com.androidcompress.app.data.EncodeResult
import com.androidcompress.app.data.EncodeStats
import com.androidcompress.app.data.H264Profile
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Device transcode path: Media3 Transformer + MediaCodec.
 * No FFmpeg. CBR by default, VBR first on MediaTek, Pixel 10 HDR tone-map.
 */
@OptIn(UnstableApi::class)
class Media3Transcoder(context: Context) {

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    fun encode(
        input: Uri,
        outputPath: String,
        spec: Media3EncodeSpec,
        durationMs: Long,
        onStats: (EncodeStats) -> Unit,
    ): EncodeSession {
        val cancelled = AtomicBoolean(false)
        val deferred = CompletableDeferred<EncodeResult>()
        val transformerRef = AtomicReference<Transformer?>(null)
        val progressJob = AtomicReference<Job?>(null)

        val start = Runnable {
            if (cancelled.get()) {
                deferred.complete(cancelledResult())
                return@Runnable
            }
            try {
                val transformer = buildTransformer(
                    spec = spec,
                    onCompleted = {
                        if (deferred.isCompleted) return@buildTransformer
                        val size = File(outputPath).length()
                        deferred.complete(
                            EncodeResult(
                                success = size > 0,
                                cancelled = false,
                                outputPath = outputPath,
                                error = if (size > 0) null else "Device encoder wrote an empty file",
                                logs = spec.encoderLabel,
                            ),
                        )
                    },
                    onError = { error, cause ->
                        if (deferred.isCompleted) return@buildTransformer
                        if (cancelled.get()) {
                            deferred.complete(cancelledResult())
                        } else {
                            deferred.complete(
                                EncodeResult(
                                    success = false,
                                    cancelled = false,
                                    outputPath = null,
                                    error = error,
                                    logs = media3Log(spec.encoderLabel, error, cause),
                                ),
                            )
                        }
                    },
                )
                transformerRef.set(transformer)
                transformer.start(composition(input, spec), outputPath)
                progressJob.set(
                    scope.launch {
                        while (isActive && !deferred.isCompleted) {
                            val holder = ProgressHolder()
                            val state = transformer.getProgress(holder)
                            if (state != Transformer.PROGRESS_STATE_NOT_STARTED) {
                                val fraction = (holder.progress / 100f).coerceIn(0f, 0.99f)
                                val timeMs = if (durationMs > 0) (fraction * durationMs).toLong() else 0L
                                val size = if (File(outputPath).exists()) File(outputPath).length() else 0L
                                onStats(EncodeStats(timeMs = timeMs, sizeBytes = size, speed = 0f))
                            }
                            delay(200)
                        }
                    },
                )
            } catch (t: Throwable) {
                if (!deferred.isCompleted) {
                    deferred.complete(
                        EncodeResult(
                            success = false,
                            cancelled = cancelled.get(),
                            outputPath = null,
                            error = t.message ?: "Device encoder failed to start",
                            logs = media3Log(spec.encoderLabel, t.message, t),
                        ),
                    )
                }
            }
        }

        if (Looper.myLooper() == Looper.getMainLooper()) {
            start.run()
        } else {
            mainHandler.post(start)
        }

        deferred.invokeOnCompletion {
            progressJob.get()?.cancel()
        }

        return object : EncodeSession {
            override val id: Long = 0L
            override suspend fun await(): EncodeResult = deferred.await()
            override fun cancel() {
                cancelled.set(true)
                mainHandler.post {
                    runCatching { transformerRef.get()?.cancel() }
                    if (!deferred.isCompleted) deferred.complete(cancelledResult())
                }
            }
        }
    }

    private fun buildTransformer(
        spec: Media3EncodeSpec,
        onCompleted: () -> Unit,
        onError: (String, Throwable?) -> Unit,
    ): Transformer {
        val decoderFactory = DefaultDecoderFactory.Builder(appContext)
            .setEnableDecoderFallback(true)
            .build()

        val cbrFactory = encoderFactory(spec, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
        val vbrFactory = encoderFactory(spec, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
        val mediaTek = isMediaTekDeviceOrEncoder(spec.videoMimeType)
        val primary = when {
            mediaTek -> vbrFactory
            spec.preferCbr -> cbrFactory
            else -> vbrFactory
        }
        val fallback = if (primary === cbrFactory) vbrFactory else cbrFactory

        val encoderFactory = object : Codec.EncoderFactory {
            override fun createForAudioEncoding(
                format: androidx.media3.common.Format,
                logSessionId: android.media.metrics.LogSessionId?,
            ): Codec {
                return primary.createForAudioEncoding(format, logSessionId)
            }

            override fun createForVideoEncoding(
                format: androidx.media3.common.Format,
                logSessionId: android.media.metrics.LogSessionId?,
            ): Codec {
                val targetFps = if (spec.outputFps > 0) spec.outputFps.toFloat() else spec.originalFps
                var builder = format.buildUpon()
                if (targetFps > 0f) builder = builder.setFrameRate(targetFps)
                if (format.colorInfo == null || !ColorInfo.isTransferHdr(format.colorInfo)) {
                    builder = builder.setColorInfo(null)
                }
                val modified = builder.build()
                return try {
                    primary.createForVideoEncoding(modified, logSessionId)
                } catch (_: Exception) {
                    fallback.createForVideoEncoding(modified, logSessionId)
                }
            }

            override fun audioNeedsEncoding(): Boolean = primary.audioNeedsEncoding()
            override fun videoNeedsEncoding(): Boolean =
                !spec.removeVideo && primary.videoNeedsEncoding()
        }

        val builder = Transformer.Builder(appContext)
            .setAssetLoaderFactory(DefaultAssetLoaderFactory(appContext, decoderFactory, Clock.DEFAULT, null))
            .setEncoderFactory(encoderFactory)
            .addListener(
                object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                        onCompleted()
                    }

                    override fun onError(
                        composition: Composition,
                        exportResult: ExportResult,
                        exportException: ExportException,
                    ) {
                        onError(humanError(exportException, spec), exportException)
                    }
                },
            )
        if (!spec.removeVideo) {
            builder.setVideoMimeType(spec.videoMimeType)
        }
        if (!spec.removeAudio && !spec.remuxAudio) {
            builder.setAudioMimeType(spec.audioMimeType)
        }
        if (spec.webm) {
            builder.setMuxerFactory(AndroidWebmMuxerFactory())
        }
        return builder.build()
    }

    private fun composition(input: Uri, spec: Media3EncodeSpec): Composition {
        val effects = mutableListOf<Effect>()
        if (!spec.removeVideo) {
            if (spec.outputHeight > 0) {
                val width = spec.outputWidth
                val height = spec.outputHeight
                if (width > 0 && height > 0) {
                    effects.add(Presentation.createForWidthAndHeight(width, height, Presentation.LAYOUT_SCALE_TO_FIT))
                }
            }
            if (spec.outputFps > 0 && spec.outputFps.toFloat() < spec.originalFps) {
                effects.add(FrameDropEffect.createSimpleFrameDropEffect(spec.originalFps, spec.outputFps.toFloat()))
            }
        }

        val audioProcessors: List<AudioProcessor> = if (!spec.removeAudio && !spec.remuxAudio) {
            val processors = mutableListOf<AudioProcessor>()
            if (spec.audioVolume != 1f) {
                processors += VolumeAudioProcessor().apply { setVolume(spec.audioVolume) }
            }
            processors += SonicAudioProcessor()
            processors
        } else {
            emptyList()
        }

        val editedItem = EditedMediaItem.Builder(mediaItem(input, spec))
            .setEffects(Effects(audioProcessors, effects))
            .setRemoveAudio(spec.removeAudio)
        if (spec.removeVideo) {
            editedItem.setRemoveVideo(true)
        }
        val edited = editedItem.build()

        var hdrMode = Composition.HDR_MODE_KEEP_HDR
        val forcePixel10 = !spec.removeVideo &&
            isPixel10() &&
            (spec.videoMimeType == MimeTypes.VIDEO_H265 || spec.videoMimeType == MimeTypes.VIDEO_H264) &&
            isHdr(input)
        if (!spec.removeVideo && (spec.toneMapHdr || forcePixel10)) {
            hdrMode = Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL
        }

        val sequence = when {
            spec.removeVideo -> EditedMediaItemSequence.withAudioFrom(listOf(edited))
            spec.removeAudio -> EditedMediaItemSequence.withVideoFrom(listOf(edited))
            else -> EditedMediaItemSequence.withAudioAndVideoFrom(listOf(edited))
        }
        val composition = Composition.Builder(sequence)
            .setHdrMode(hdrMode)
        if (spec.remuxAudio) {
            composition.setTransmuxAudio(true)
        }
        return composition.build()
    }

    private fun mediaItem(input: Uri, spec: Media3EncodeSpec): MediaItem {
        if (!spec.clipActive) return MediaItem.fromUri(input)
        val clipping = MediaItem.ClippingConfiguration.Builder()
            .setStartPositionMs(spec.clipStartMs)
        spec.clipEndMs?.let { clipping.setEndPositionMs(it) }
        return MediaItem.Builder()
            .setUri(input)
            .setClippingConfiguration(clipping.build())
            .build()
    }

    private fun encoderFactory(spec: Media3EncodeSpec, bitrateMode: Int): DefaultEncoderFactory {
        val videoBuilder = VideoEncoderSettings.Builder()
            .setBitrate(spec.videoBitrateBps)
            .setBitrateMode(bitrateMode)
        spec.iFrameIntervalSeconds?.let { videoBuilder.setiFrameIntervalSeconds(it) }
        if (!spec.webm) {
            spec.maxBFrames?.let { videoBuilder.setMaxBFrames(it) }
            codecProfile(spec)?.let { profile ->
                videoBuilder.setEncodingProfileLevel(profile, VideoEncoderSettings.NO_VALUE)
            }
        }
        val video = videoBuilder.build()
        val audioBuilder = AudioEncoderSettings.Builder()
        if (spec.audioBitrateBps > 0) {
            audioBuilder.setBitrate(spec.audioBitrateBps)
        }
        return DefaultEncoderFactory.Builder(appContext)
            .setEnableFallback(true)
            .setRequestedVideoEncoderSettings(video)
            .setRequestedAudioEncoderSettings(audioBuilder.build())
            .build()
    }

    private fun codecProfile(spec: Media3EncodeSpec): Int? {
        val hevc = spec.videoMimeType == MimeTypes.VIDEO_H265 || spec.videoMimeType == Media3EncodePlanner.MIME_HEVC
        return if (hevc) {
            when (spec.h264Profile) {
                H264Profile.MAIN, H264Profile.HIGH -> MediaCodecInfo.CodecProfileLevel.HEVCProfileMain
                else -> null
            }
        } else {
            when (spec.h264Profile) {
                H264Profile.BASELINE -> MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline
                H264Profile.MAIN -> MediaCodecInfo.CodecProfileLevel.AVCProfileMain
                H264Profile.HIGH -> MediaCodecInfo.CodecProfileLevel.AVCProfileHigh
                H264Profile.AUTO -> null
            }
        }
    }

    private fun humanError(exception: ExportException, spec: Media3EncodeSpec): String {
        val muxer = exception.errorCode == ExportException.ERROR_CODE_MUXING_FAILED
        val decoder = exception.errorCode == ExportException.ERROR_CODE_DECODER_INIT_FAILED
        val encoder = exception.errorCode == ExportException.ERROR_CODE_ENCODER_INIT_FAILED
        return when {
            muxer && spec.webm ->
                "This device could not write a WebM file with Media3. Try FFmpeg."
            muxer && Build.MANUFACTURER.equals("HUAWEI", ignoreCase = true) ->
                "This Huawei device rejected the MP4 muxer. Try FFmpeg instead."
            decoder -> "This device cannot decode the source video with Media3."
            encoder -> "This device cannot encode that codec or resolution with Media3."
            else -> exception.localizedMessage ?: "Device encoder failed"
        }
    }

    private fun isMediaTekDeviceOrEncoder(mimeType: String): Boolean {
        return try {
            val hardware = Build.HARDWARE.lowercase()
            val board = Build.BOARD.lowercase()
            val manufacturer = Build.MANUFACTURER.lowercase()
            val soc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Build.SOC_MODEL.lowercase() else ""
            if (hardware.contains("mediatek") || board.contains("mediatek") || manufacturer.contains("mediatek") ||
                soc.contains("mediatek") || soc.contains("dimensity") ||
                hardware.matches(Regex(""".*mt\d{4}.*""")) || board.matches(Regex(""".*mt\d{4}.*"""))
            ) {
                return true
            }
            val list = MediaCodecList(MediaCodecList.ALL_CODECS)
            for (info in list.codecInfos) {
                if (!info.isEncoder) continue
                if (info.supportedTypes.any { it.equals(mimeType, ignoreCase = true) }) {
                    val name = info.name.lowercase()
                    if (name.contains("mtk") || name.contains("mediatek")) return true
                }
            }
            false
        } catch (_: Exception) {
            false
        }
    }

    private fun isPixel10(): Boolean =
        Build.MANUFACTURER.equals("Google", ignoreCase = true) && Build.MODEL.contains("Pixel 10")

    private fun isHdr(uri: Uri): Boolean {
        if (Build.VERSION.SDK_INT < 30) return false
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(appContext, uri)
            val transfer = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_COLOR_TRANSFER)
            transfer == "6" || transfer == "7"
        } catch (_: Exception) {
            false
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun media3Log(encoderLabel: String, error: String?, cause: Throwable?): String = buildString {
        appendLine("encoder: $encoderLabel")
        error?.takeIf { it.isNotBlank() }?.let { appendLine("error: $it") }
        cause?.let {
            appendLine("stackTrace:")
            append(it.stackTraceToString())
        }
    }

    private fun cancelledResult() = EncodeResult(
        success = false,
        cancelled = true,
        outputPath = null,
        error = null,
        logs = "",
    )
}
