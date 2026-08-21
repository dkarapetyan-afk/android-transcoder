package com.androidcompress.app.encode

import com.androidcompress.app.data.EncodeResult
import com.androidcompress.app.data.EncodeStats
import com.androidcompress.app.data.EncoderCapabilities
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

class FfmpegKitGateway : FfmpegGateway {

    override suspend fun detectEncoders(): EncoderCapabilities {
        val listing = suspendCancellableCoroutine { cont ->
            val session = FFmpegKit.executeAsync("-hide_banner -encoders") { done ->
                if (cont.isActive) cont.resume(done.output.orEmpty())
            }
            cont.invokeOnCancellation { FFmpegKit.cancel(session.sessionId) }
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
                        sizeBytes = stats.size.toLong().coerceAtLeast(0L),
                        speed = stats.speed.toFloat(),
                        videoFrameNumber = stats.videoFrameNumber.coerceAtLeast(0),
                    ),
                )
            },
        )
        return object : EncodeSession {
            override val id: Long = session.sessionId
            override suspend fun await(): EncodeResult = deferred.await()
            override fun cancel() {
                runCatching { FFmpegKit.cancel(session.sessionId) }
                runCatching { FFmpegKit.cancel() }
                Thread({
                    try {
                        Thread.sleep(2_000)
                    } catch (_: InterruptedException) {
                        return@Thread
                    }
                    deferred.complete(
                        EncodeResult(
                            success = false,
                            cancelled = true,
                            outputPath = null,
                            error = null,
                            logs = "FFmpeg cancel requested",
                        ),
                    )
                }, "ffmpeg-cancel").apply {
                    isDaemon = true
                    start()
                }
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
