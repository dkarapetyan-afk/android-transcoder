package com.androidcompress.app.encode

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import com.androidcompress.app.R
import com.androidcompress.app.container
import com.androidcompress.app.data.EncodeEngine
import com.androidcompress.app.data.EncodeProgress
import com.androidcompress.app.data.EncodeResult
import com.androidcompress.app.data.EncodeSettings
import com.androidcompress.app.data.JobStatus
import com.androidcompress.app.data.SettingsJson
import com.androidcompress.app.data.SourceVideo
import com.androidcompress.app.data.audioOutput
import com.androidcompress.app.data.galleryFolder
import com.androidcompress.app.data.outputExtension
import com.androidcompress.app.data.outputMime
import com.androidcompress.app.data.usesWebm
import com.androidcompress.app.media.CombinePairing
import com.androidcompress.app.media.InputResolver
import com.androidcompress.app.util.Notifications
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class CompressService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var work: Job? = null
    private var session: EncodeSession? = null
    private var wakeLock: PowerManager.WakeLock? = null
    @Volatile private var cancelAll = false
    @Volatile private var currentJobId: String? = null
    @Volatile private var queueTotal = 1
    @Volatile private var queueIndex = 1

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CANCEL -> {
                session?.cancel()
                return START_NOT_STICKY
            }
            ACTION_CANCEL_ALL -> {
                cancelAll = true
                session?.cancel()
                scope.launch { container().jobs.cancelAllQueued() }
                return START_NOT_STICKY
            }
            ACTION_CANCEL_JOB -> {
                val id = intent.getStringExtra(EXTRA_JOB_ID)
                if (id != null && id == currentJobId) {
                    session?.cancel()
                } else if (id != null) {
                    scope.launch { container().jobs.cancelQueued(id) }
                }
                return START_NOT_STICKY
            }
            ACTION_ENQUEUE, ACTION_START -> {
                val jobId = intent.getStringExtra(EXTRA_JOB_ID)
                startAsForeground(jobId ?: currentJobId.orEmpty(), 0)
                if (work?.isActive != true) {
                    cancelAll = false
                    work = scope.launch { drain() }
                }
            }
        }
        return START_NOT_STICKY
    }

    private suspend fun drain() {
        val app = container()
        acquireWakeLock()
        try {
            do {
                while (!cancelAll) {
                    val next = app.jobs.nextQueued() ?: break
                    val active = EncodeQueue.active(app.jobs.listActive())
                    queueTotal = active.size.coerceAtLeast(1)
                    queueIndex = EncodeQueue.position(active, next.id).first.coerceAtLeast(1)
                    runJob(next.id)
                }
            } while (!cancelAll && app.jobs.nextQueued() != null)
        } finally {
            currentJobId = null
            app.encodeProgress.update(null)
            releaseWakeLock()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private suspend fun runJob(jobId: String) {
        val app = container()
        val job = app.jobs.get(jobId)
        if (job == null) return
        val settings = SettingsJson.decode(job.settingsJson)
        val sourceUri = Uri.parse(job.sourceUri)
        val probed = runCatching { app.probe.probe(sourceUri) }.getOrNull()
        val audioProbed = job.audioUri.takeIf { it.isNotBlank() }?.let { uri ->
            runCatching { app.probe.probe(Uri.parse(uri)) }.getOrNull()
        }
        val stillImage = job.stillImage || probed?.stillImage == true
        val source = SourceVideo(
            uri = job.sourceUri,
            displayName = job.displayName,
            width = if (job.width > 0) job.width else probed?.width ?: 0,
            height = if (job.height > 0) job.height else probed?.height ?: 0,
            durationMs = when {
                job.durationMs > 0 -> job.durationMs
                audioProbed != null -> CombinePairing.outputDurationMs(
                    probed?.durationMs ?: 0L,
                    audioProbed.durationMs,
                    stillImage,
                )
                else -> probed?.durationMs ?: 0L
            },
            bytes = job.sourceBytes,
            frameRate = if (stillImage) 30f else probed?.frameRate ?: 30f,
            audioCodec = audioProbed?.audioCodec ?: probed?.audioCodec,
            hasAudio = audioProbed?.hasAudio ?: probed?.hasAudio ?: true,
            hasVideo = probed?.hasVideo ?: (job.width > 0 && job.height > 0) || stillImage,
            stillImage = stillImage,
            audioUri = job.audioUri,
        )
        currentJobId = jobId
        app.jobs.updateStatus(jobId, JobStatus.RUNNING)
        val audioOnly = settings.audioOutput(source.hasVideo)
        val output = app.inputs.encodeOutputFile(jobId, settings.outputExtension())
        output.delete()
        val log = StringBuilder()
        log.appendLine("jobId=$jobId")
        log.appendLine("name=${job.displayName}")
        log.appendLine("engine=${settings.engine}")
        log.appendLine("output=${if (audioOnly) "audio" else "video"}")
        log.appendLine("container=${settings.container}")
        log.appendLine("source=${job.sourceUri}")
        if (job.audioUri.isNotBlank()) {
            log.appendLine("audio=${job.audioUri}")
            log.appendLine("stillImage=$stillImage")
        }
        try {
            if (audioOnly && !source.hasAudio) {
                error(getString(R.string.error_no_audio_extract))
            }
            val estimatedOutput = FfmpegCommandBuilder.estimateOutputBytes(source, settings)
            val needed = InputResolver.bytesNeededForEncode(
                estimatedOutput,
                job.sourceBytes,
                source.durationMs,
            )
            if (!app.inputs.hasSpaceFor(needed)) {
                error(getString(R.string.error_not_enough_storage_compress))
            }
            val result = when (settings.engine) {
                EncodeEngine.MEDIA3 -> runMedia3(jobId, source, settings, sourceUri, output, log)
                EncodeEngine.FFMPEG -> runFfmpeg(jobId, source, settings, sourceUri, output, log)
            }
            when {
                result.cancelled -> {
                    output.delete()
                    log.appendLine("cancelled")
                    app.jobLogs.write(jobId, log.toString())
                    app.jobs.updateStatus(jobId, JobStatus.CANCELLED, finished = true)
                    app.encodeProgress.update(null)
                }
                result.success && output.exists() && output.length() > 0 -> {
                    val published = app.exporter.publish(
                        output,
                        compressedName(job.displayName, settings),
                        settings.outputMime(),
                        settings.galleryFolder(),
                    )
                    val bytes = output.length()
                    output.delete()
                    log.appendLine("published=$published")
                    app.jobLogs.write(jobId, log.toString())
                    app.jobs.updateStatus(
                        id = jobId,
                        status = JobStatus.SUCCEEDED,
                        outputUri = published.toString(),
                        outputBytes = bytes,
                        finished = true,
                    )
                    if (job.deleteSourceAfter) {
                        val removed = app.sourceDeleter.deleteSources(
                            listOf(job.sourceUri, job.audioUri),
                            published.toString(),
                        )
                        app.jobs.markSourceDeleted(jobId, removed.deleted)
                    }
                    app.inputs.deleteImportCopy(jobId)
                    val doneMs = Media3EncodePlanner.outputDurationMs(settings, source)
                    app.encodeProgress.update(EncodeProgress(jobId, 1f, doneMs))
                }
                else -> {
                    output.delete()
                    app.jobLogs.write(jobId, log.toString())
                    app.jobs.updateStatus(
                        jobId,
                        JobStatus.FAILED,
                        error = result.error ?: getString(R.string.error_compression_failed),
                        finished = true,
                    )
                    app.encodeProgress.update(null)
                }
            }
        } catch (t: Throwable) {
            output.delete()
            log.appendLine("exception: ${t.message}")
            log.appendLine(t.stackTraceToString())
            runCatching { app.jobLogs.write(jobId, log.toString()) }
            app.jobs.updateStatus(
                jobId,
                JobStatus.FAILED,
                error = t.message ?: getString(R.string.error_compression_failed),
                finished = true,
            )
            app.encodeProgress.update(null)
        } finally {
            runCatching { if (app.jobLogs.read(jobId) == null) app.jobLogs.write(jobId, log.toString()) }
            runCatching { app.history.prune() }
            currentJobId = null
            app.inputs.deleteImportCopy(jobId)
        }
    }

    private suspend fun runFfmpeg(
        jobId: String,
        source: SourceVideo,
        settings: EncodeSettings,
        sourceUri: Uri,
        output: File,
        log: StringBuilder,
    ): EncodeResult {
        val app = container()
        val ffmpegInput = app.inputs.resolveForFfmpeg(sourceUri, jobId)
        val ffmpegAudio = source.audioUri.takeIf { it.isNotBlank() }?.let { uri ->
            app.inputs.resolveForFfmpeg(Uri.parse(uri), jobId, "audio")
        }
        val caps = app.encoderCapabilities()
        var plan = FfmpegCommandBuilder.build(
            ffmpegInput, output.absolutePath, settings, source, caps, audioInput = ffmpegAudio,
        )
        if (settings.ffmpegCommandOverride.isNotBlank()) {
            val args = FfmpegCommandTemplate.materialize(
                settings.ffmpegCommandOverride,
                ffmpegInput,
                output.absolutePath,
                ffmpegAudio,
            ).getOrThrow()
            log.appendLine("using edited command template")
            return executePlan(jobId, source, settings, plan.copy(args = args), log)
        }
        var result = executePlan(jobId, source, settings, plan, log)
        while (!result.success && !result.cancelled) {
            val next = FfmpegCommandBuilder.fallbackPlan(
                plan, ffmpegInput, output.absolutePath, settings, source, caps, ffmpegAudio,
            ) ?: break
            log.appendLine("retrying with fallback encoder/pix_fmt")
            output.delete()
            plan = next
            result = executePlan(jobId, source, settings, plan, log)
        }
        return result
    }

    private suspend fun runMedia3(
        jobId: String,
        source: SourceVideo,
        settings: EncodeSettings,
        sourceUri: Uri,
        output: File,
        log: StringBuilder,
    ): EncodeResult {
        var spec = Media3EncodePlanner.plan(settings, source)
        var result = executeMedia3(jobId, source, spec, sourceUri, output, log)
        val fallback = if (settings.usesWebm()) {
            Media3EncodePlanner.webmFallback(spec)
        } else {
            Media3EncodePlanner.h264Fallback(spec)
        }
        if (!result.success && !result.cancelled && fallback != null) {
            log.appendLine("retrying Media3 ${fallback.encoderLabel} fallback")
            output.delete()
            spec = fallback
            result = executeMedia3(jobId, source, spec, sourceUri, output, log)
        }
        return result
    }

    private suspend fun executeMedia3(
        jobId: String,
        source: SourceVideo,
        spec: Media3EncodeSpec,
        sourceUri: Uri,
        output: File,
        log: StringBuilder,
    ): EncodeResult {
        log.appendLine("Media3 encoder=${spec.encoderLabel}")
        if (spec.removeVideo) {
            log.appendLine("audio only")
        }
        if (spec.stillImage) {
            log.appendLine("still image durationMs=${spec.imageDurationMs} fps=${spec.originalFps}")
        }
        if (spec.clipActive) {
            log.appendLine("clip start=${spec.clipStartMs}ms end=${spec.clipEndMs ?: "eos"}")
        }
        val clipDurationMs = spec.clipDurationMs(source.durationMs)
        val current = container().media3.encode(
            input = sourceUri,
            outputPath = output.absolutePath,
            spec = spec,
            durationMs = clipDurationMs,
            onStats = { stats ->
                val fraction = if (clipDurationMs > 0) {
                    (stats.timeMs.toFloat() / clipDurationMs).coerceIn(0f, 0.99f)
                } else {
                    0f
                }
                container().encodeProgress.update(EncodeProgress(jobId, fraction, stats.timeMs, spec.encoderLabel))
                startAsForeground(jobId, (fraction * 100).toInt())
            },
        )
        session = current
        val result = current.await()
        appendResult(log, result)
        return result
    }

    private suspend fun executePlan(
        jobId: String,
        source: SourceVideo,
        settings: EncodeSettings,
        plan: EncodePlan,
        log: StringBuilder,
    ): EncodeResult {
        log.appendLine("FFmpeg command: ${quoteArgs(plan.args)}")
        val lastStatsAt = AtomicLong(System.currentTimeMillis())
        val stalled = AtomicBoolean(false)
        val current = container().ffmpeg.encode(
            args = plan.args,
            onLog = { line -> if (line.isNotBlank()) log.appendLine(line) },
            onStats = { stats ->
                lastStatsAt.set(System.currentTimeMillis())
                val durationMs = Media3EncodePlanner.outputDurationMs(settings, source)
                val fraction = if (durationMs > 0) {
                    (stats.timeMs.toFloat() / durationMs).coerceIn(0f, 0.99f)
                } else {
                    0f
                }
                container().encodeProgress.update(EncodeProgress(jobId, fraction, stats.timeMs))
                startAsForeground(jobId, (fraction * 100).toInt())
            },
        )
        session = current
        val watchdog = scope.launch {
            while (isActive) {
                delay(2_000)
                if (System.currentTimeMillis() - lastStatsAt.get() > STALL_MS) {
                    stalled.set(true)
                    current.cancel()
                    break
                }
            }
        }
        val result = try {
            current.await()
        } finally {
            watchdog.cancel()
        }
        if (stalled.get()) {
            log.appendLine("encoder stalled; cancelling so a fallback can run")
        }
        appendResult(log, result)
        return if (stalled.get() && !result.success) {
            result.copy(
                cancelled = false,
                error = result.error ?: getString(R.string.error_compression_failed),
            )
        } else {
            result
        }
    }

    private fun appendResult(log: StringBuilder, result: EncodeResult) {
        log.appendLine("success=${result.success} cancelled=${result.cancelled}")
        result.error?.let { log.appendLine("error=$it") }
        if (result.logs.isNotBlank()) {
            log.appendLine("--- engine log ---")
            log.appendLine(result.logs)
        }
    }

    private fun compressedName(original: String, settings: EncodeSettings): String {
        val audioOnly = settings.output == com.androidcompress.app.data.OutputMode.AUDIO
        val base = original.substringBeforeLast('.').ifBlank { if (audioOnly) "audio" else "video" }
        return "${base}-compressed.${settings.outputExtension()}"
    }

    private fun startAsForeground(jobId: String, percent: Int) {
        val cancel = PendingIntent.getService(
            this,
            2,
            Intent(this, CompressService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = Notifications.encoding(this, jobId, percent, queueIndex, queueTotal, cancel)
        // Do not use ServiceCompat.startForeground here. androidx.core 1.16 masks
        // FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING (0x2000) to 0, and targetSdk 36
        // rejects a type-none FGS (Pixel 10 / Android 16 crash).
        if (Build.VERSION.SDK_INT >= 35) {
            startForeground(
                Notifications.ENCODE_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING,
            )
        } else if (Build.VERSION.SDK_INT >= 29) {
            startForeground(
                Notifications.ENCODE_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            startForeground(Notifications.ENCODE_ID, notification)
        }
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(PowerManager::class.java)
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "reccomp:encode").apply {
            setReferenceCounted(false)
            acquire(6 * 60 * 60 * 1000L)
        }
    }

    private fun releaseWakeLock() {
        runCatching { wakeLock?.release() }
        wakeLock = null
    }

    override fun onDestroy() {
        scope.cancel()
        releaseWakeLock()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.androidcompress.app.ENCODE_START"
        const val ACTION_ENQUEUE = "com.androidcompress.app.ENCODE_ENQUEUE"
        const val ACTION_CANCEL = "com.androidcompress.app.ENCODE_CANCEL"
        const val ACTION_CANCEL_ALL = "com.androidcompress.app.ENCODE_CANCEL_ALL"
        const val ACTION_CANCEL_JOB = "com.androidcompress.app.ENCODE_CANCEL_JOB"
        const val EXTRA_JOB_ID = "jobId"
        private const val STALL_MS = 20_000L

        fun start(context: Context, jobId: String) = enqueue(context, jobId)

        fun enqueue(context: Context, jobId: String) {
            val intent = Intent(context, CompressService::class.java)
                .setAction(ACTION_ENQUEUE)
                .putExtra(EXTRA_JOB_ID, jobId)
            context.startForegroundService(intent)
        }

        fun cancel(context: Context) {
            context.startService(Intent(context, CompressService::class.java).setAction(ACTION_CANCEL))
        }

        fun cancelAll(context: Context) {
            context.startService(Intent(context, CompressService::class.java).setAction(ACTION_CANCEL_ALL))
        }

        fun cancelJob(context: Context, jobId: String) {
            context.startService(
                Intent(context, CompressService::class.java)
                    .setAction(ACTION_CANCEL_JOB)
                    .putExtra(EXTRA_JOB_ID, jobId),
            )
        }
    }
}
