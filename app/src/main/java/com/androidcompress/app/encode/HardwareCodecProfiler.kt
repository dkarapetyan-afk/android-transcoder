package com.androidcompress.app.encode

import android.content.Context
import android.net.Uri
import com.androidcompress.app.R
import com.androidcompress.app.data.EncodeResult
import com.androidcompress.app.data.EncoderCapabilities
import com.androidcompress.app.data.H264Profile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

class HardwareCodecProfiler(
    context: Context,
    private val ffmpeg: FfmpegGateway,
    private val media3: Media3Transcoder,
    private val advertised: (String) -> HardwareAdvertisedCaps? = { HardwareCodecProbe.advertised(it) },
) {
    private val appContext = context.applicationContext

    suspend fun run(
        caps: EncoderCapabilities,
        onProgress: suspend (HardwareProgress) -> Unit,
        cancelled: AtomicBoolean,
    ): HardwareProfileReport = withContext(Dispatchers.IO) {
        val started = System.currentTimeMillis()
        val targets = HardwareProfilePlan.targets(caps)
        val dir = workDir()
        dir.deleteRecursively()
        dir.mkdirs()
        val results = ArrayList<HardwareEncoderResult>(targets.size)
        try {
            for ((index, target) in targets.withIndex()) {
                if (cancelled.get()) break
                results += profileTarget(target, dir, index, targets.size, onProgress, cancelled)
            }
        } finally {
            dir.deleteRecursively()
        }
        HardwareProfileReport(
            startedAt = started,
            finishedAt = System.currentTimeMillis(),
            cancelled = cancelled.get(),
            results = results,
        )
    }

    private suspend fun profileTarget(
        target: HardwareTarget,
        dir: File,
        index: Int,
        total: Int,
        onProgress: suspend (HardwareProgress) -> Unit,
        cancelled: AtomicBoolean,
    ): HardwareEncoderResult {
        val claimed = advertised(target.mime)
        val maxW = claimed?.maxWidth?.takeIf { it > 0 } ?: HardwareProfilePlan.DEFAULT_MAX_WIDTH
        val maxH = claimed?.maxHeight?.takeIf { it > 0 } ?: HardwareProfilePlan.DEFAULT_MAX_HEIGHT
        val sizes = HardwareProfilePlan.sizesFor(maxW, maxH)
        var verified: HardwareSize? = null
        var encodeMs: Long? = null
        var lastError: String? = null

        for (size in sizes) {
            if (cancelled.get()) break
            val source = ensureSource(dir, size, index, total, target, onProgress, cancelled)
            if (source == null) {
                lastError = lastError ?: appContext.getString(R.string.hw_error_source)
                continue
            }
            onProgress(
                HardwareProgress(
                    targetId = target.id,
                    displayName = target.displayName,
                    step = HardwareStep.ENCODE,
                    width = size.width,
                    height = size.height,
                    index = index,
                    total = total,
                ),
            )
            val trial = encodeOnce(
                target,
                source,
                File(dir, "${target.id}_${size.width}x${size.height}.${target.extension}"),
                size,
                tenBit = false,
                cancelled = cancelled,
            )
            if (trial.success) {
                verified = size
                encodeMs = trial.encodeMs
                lastError = null
                break
            }
            lastError = trial.error
        }

        var tenBitVerified: Boolean? = null
        if (!cancelled.get() && claimed?.tenBit == true && verified != null) {
            val tenBitSize = sizes.firstOrNull { it.height <= 1080 } ?: verified
            val source = ensureSource(dir, tenBitSize, index, total, target, onProgress, cancelled)
            if (source != null) {
                onProgress(
                    HardwareProgress(
                        targetId = target.id,
                        displayName = target.displayName,
                        step = HardwareStep.TEN_BIT,
                        width = tenBitSize.width,
                        height = tenBitSize.height,
                        index = index,
                        total = total,
                    ),
                )
                if (target.kind == HardwareTargetKind.FFMPEG) {
                    val trial = encodeOnce(
                        target,
                        source,
                        File(dir, "${target.id}_10bit.${target.extension}"),
                        tenBitSize,
                        tenBit = true,
                        cancelled = cancelled,
                    )
                    tenBitVerified = trial.success
                    if (!trial.success) lastError = lastError ?: trial.error
                }
            }
        }

        val available = verified != null
        return HardwareEncoderResult(
            targetId = target.id,
            displayName = target.displayName,
            available = available,
            codecName = claimed?.encoderName.orEmpty(),
            advertisedMaxWidth = claimed?.maxWidth ?: 0,
            advertisedMaxHeight = claimed?.maxHeight ?: 0,
            verifiedMaxWidth = verified?.width ?: 0,
            verifiedMaxHeight = verified?.height ?: 0,
            advertisedTenBit = claimed?.tenBit == true,
            advertisedHdr = claimed?.hdr == true,
            tenBitVerified = tenBitVerified,
            speedX = encodeMs?.let { HardwareProfilePlan.speedX(HardwareProfilePlan.CLIP_MS, it) },
            encodeMs = encodeMs,
            error = if (available) null else lastError,
        )
    }

    private suspend fun ensureSource(
        dir: File,
        size: HardwareSize,
        index: Int,
        total: Int,
        target: HardwareTarget,
        onProgress: suspend (HardwareProgress) -> Unit,
        cancelled: AtomicBoolean,
    ): File? {
        val file = File(dir, "src_${size.width}x${size.height}.mp4")
        if (file.exists() && file.length() > 1_024) return file
        if (cancelled.get()) return null
        onProgress(
            HardwareProgress(
                targetId = target.id,
                displayName = target.displayName,
                step = HardwareStep.SOURCE,
                width = size.width,
                height = size.height,
                index = index,
                total = total,
            ),
        )
        val args = listOf(
            "-y", "-hide_banner",
            "-f", "lavfi",
            "-i", "testsrc=size=${size.width}x${size.height}:rate=${HardwareProfilePlan.FPS}:duration=1",
            "-pix_fmt", "yuv420p",
            "-c:v", "mpeg4",
            "-q:v", "6",
            "-an",
            file.absolutePath,
        )
        val result = runFfmpeg(args, cancelled)
        return file.takeIf { result.success && it.exists() && it.length() > 1_024 }
    }

    private suspend fun encodeOnce(
        target: HardwareTarget,
        source: File,
        output: File,
        size: HardwareSize,
        tenBit: Boolean,
        cancelled: AtomicBoolean,
    ): Trial {
        output.delete()
        val started = System.nanoTime()
        val result = when (target.kind) {
            HardwareTargetKind.FFMPEG -> {
                val encoder = target.ffmpegName
                    ?: return Trial(false, 0, appContext.getString(R.string.hw_error_missing_encoder))
                runFfmpeg(ffmpegArgs(encoder, source, output, size, tenBit, target.extension), cancelled)
            }
            HardwareTargetKind.MEDIA3 -> runMedia3(target, source, output, size, cancelled)
        }
        val elapsed = ((System.nanoTime() - started) / 1_000_000L).coerceAtLeast(1L)
        val wrote = output.exists() && output.length() > 512
        val success = result.success && wrote && !result.cancelled
        return Trial(
            success = success,
            encodeMs = elapsed,
            error = when {
                success -> null
                result.cancelled -> appContext.getString(R.string.hw_cancel)
                !result.error.isNullOrBlank() -> result.error
                !wrote -> appContext.getString(R.string.hw_error_empty)
                else -> appContext.getString(R.string.hw_error_failed)
            },
        )
    }

    private fun ffmpegArgs(
        encoder: String,
        source: File,
        output: File,
        size: HardwareSize,
        tenBit: Boolean,
        extension: String,
    ): List<String> {
        val kbps = HardwareProfilePlan.bitrateKbps(size.width, size.height)
        val pix = if (tenBit) "p010le" else "nv12"
        val args = mutableListOf(
            "-y", "-hide_banner",
            "-i", source.absolutePath,
            "-an",
            "-pix_fmt", pix,
            "-c:v", encoder,
            "-b:v", "${kbps}k",
            "-g", HardwareProfilePlan.FPS.toString(),
        )
        if (tenBit && encoder == "hevc_mediacodec") {
            args += listOf("-profile:v", "main10")
        }
        if (tenBit && encoder == "h264_mediacodec") {
            args += listOf("-profile:v", "high10")
        }
        if (tenBit && encoder == "av1_mediacodec") {
            args += listOf("-profile:v", "main10")
        }
        if (extension == "webm") {
            args += listOf("-f", "webm")
        }
        args += output.absolutePath
        return args
    }

    private suspend fun runFfmpeg(args: List<String>, cancelled: AtomicBoolean): EncodeResult {
        val session = ffmpeg.encode(args, onLog = {}, onStats = {})
        return awaitSession(session, cancelled)
    }

    private suspend fun runMedia3(
        target: HardwareTarget,
        source: File,
        output: File,
        size: HardwareSize,
        cancelled: AtomicBoolean,
    ): EncodeResult {
        val width = size.width
        val height = size.height
        val spec = Media3EncodeSpec(
            videoMimeType = target.mime,
            outputHeight = height,
            outputWidth = width,
            outputFps = HardwareProfilePlan.FPS,
            originalFps = HardwareProfilePlan.FPS.toFloat(),
            videoBitrateBps = HardwareProfilePlan.bitrateKbps(width, height) * 1_000,
            audioBitrateBps = 0,
            removeAudio = true,
            remuxAudio = false,
            encoderLabel = "Media3",
            preferCbr = true,
            iFrameIntervalSeconds = 1f,
            h264Profile = H264Profile.AUTO,
            toneMapHdr = false,
            audioVolume = 1f,
            maxBFrames = null,
            removeVideo = false,
            webm = target.extension == "webm",
        )
        val session = media3.encode(
            input = Uri.fromFile(source),
            outputPath = output.absolutePath,
            spec = spec,
            durationMs = HardwareProfilePlan.CLIP_MS,
            onStats = {},
        )
        return awaitSession(session, cancelled)
    }

    private suspend fun awaitSession(session: EncodeSession, cancelled: AtomicBoolean): EncodeResult {
        val result = withTimeoutOrNull(ENCODE_TIMEOUT_MS) {
            coroutineScope {
                val waiter = async { session.await() }
                while (!waiter.isCompleted) {
                    if (cancelled.get()) {
                        session.cancel()
                        break
                    }
                    delay(100)
                }
                waiter.await()
            }
        }
        if (result == null) {
            session.cancel()
            return EncodeResult(
                success = false,
                cancelled = cancelled.get(),
                outputPath = null,
                error = appContext.getString(R.string.hw_error_timeout),
                logs = "",
            )
        }
        return result
    }

    private fun workDir(): File = File(appContext.cacheDir, "hwtest")

    private data class Trial(
        val success: Boolean,
        val encodeMs: Long,
        val error: String?,
    )

    companion object {
        const val ENCODE_TIMEOUT_MS = 25_000L
    }
}
