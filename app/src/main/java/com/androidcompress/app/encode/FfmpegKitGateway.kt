package com.androidcompress.app.encode

import com.androidcompress.app.data.EncodeResult
import com.androidcompress.app.data.EncodeStats
import com.androidcompress.app.data.EncoderCapabilities
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resume

class FfmpegKitGateway : FfmpegGateway {

    override suspend fun detectEncoders(): EncoderCapabilities {
        val listing: String = try {
            withTimeout(DETECT_TIMEOUT_MS) {
                suspendCancellableCoroutine { cont ->
                    val session = FFmpegKit.executeAsync("-hide_banner -encoders") { done ->
                        if (cont.isActive) cont.resume(done.output.orEmpty())
                    }
                    cont.invokeOnCancellation { runCatching { FFmpegKit.cancel(session.sessionId) } }
                }
            }
        } catch (e: TimeoutCancellationException) {
            throw IllegalStateException("FFmpeg encoder listing timed out", e)
        }
        return EncoderListing.parse(listing)
    }

    override fun encode(
        args: List<String>,
        onLog: (String) -> Unit,
        onStats: (EncodeStats) -> Unit,
    ): EncodeSession {
        val deferred = CompletableDeferred<EncodeResult>()
        val session = FFmpegKit.executeWithArgumentsAsync(
            args.toTypedArray(),
            { done -> deferred.complete(done.toResult()) },
            { log -> onLog(log.message.orEmpty()) },
            { stats ->
                onStats(
                    EncodeStats(
                        timeMs = stats.time.toLong().coerceAtLeast(0L),
                        sizeBytes = stats.size.coerceAtLeast(0L),
                        speed = stats.speed.toFloat(),
                        videoFrameNumber = stats.videoFrameNumber.coerceAtLeast(0),
                    ),
                )
            },
        )
        return object : EncodeSession {
            override val id: Long = session.sessionId

            override suspend fun await(): EncodeResult =
                try {
                    deferred.await()
                } catch (e: CancellationException) {
                    runCatching { FFmpegKit.cancel(session.sessionId) }
                    throw e
                }

            override fun cancel() {
                runCatching { FFmpegKit.cancel(session.sessionId) }
            }
        }
    }

    override fun muxCopyVideoAac(videoPath: String, audioPath: String, outputPath: String): EncodeSession {
        return encode(FfmpegMuxCommands.copyVideoAac(videoPath, audioPath, outputPath), onLog = {}, onStats = {})
    }

    override fun muxMixMicAndInternalAac(
        videoPath: String,
        internalWav: String,
        micWav: String,
        outputPath: String,
    ): EncodeSession {
        return encode(
            FfmpegMuxCommands.mixMicAndInternalAac(videoPath, internalWav, micWav, outputPath),
            onLog = {},
            onStats = {},
        )
    }

}

private fun com.arthenica.ffmpegkit.Session.toResult(): EncodeResult {
    val code = returnCode
    val cancelled = code != null && ReturnCode.isCancel(code)
    val success = code != null && ReturnCode.isSuccess(code)
    return EncodeResult(
        success = success,
        cancelled = cancelled,
        outputPath = null,
        error = if (!success && !cancelled) {
            failStackTrace ?: "FFmpeg exited with ${code?.value ?: "unknown"}"
        } else {
            null
        },
        logs = buildString {
            if (!success && !cancelled) {
                appendLine("FFmpeg exited with ${code?.value ?: "unknown"}")
                failStackTrace?.takeIf { it.isNotBlank() }?.let {
                    appendLine("failStackTrace:")
                    appendLine(it)
                }
            }
            val sessionOut = output.orEmpty()
            if (sessionOut.isNotBlank()) {
                appendLine("session output:")
                append(sessionOut)
            }
        },
    )
}

private const val DETECT_TIMEOUT_MS = 30_000L
