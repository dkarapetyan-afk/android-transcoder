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
import com.androidcompress.app.asr.CaptionProgressNotifier
import com.androidcompress.app.capture.RecordCaptureLog
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
import com.androidcompress.app.data.wantsCaptions
import com.androidcompress.app.media.CombinePairing
import com.androidcompress.app.media.InputResolver
import com.androidcompress.app.util.AppLog
import com.androidcompress.app.util.Notifications
import com.androidcompress.app.util.runCatchingLog
import kotlinx.coroutines.CancellationException
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
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class CompressService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var work: Job? = null
    private var session: EncodeSession? = null
    private var wakeLock: PowerManager.WakeLock? = null
    @Volatile private var cancelAll = false
    @Volatile private var jobCancel = false
    @Volatile private var captionCancel = false
    @Volatile private var currentJobId: String? = null
    @Volatile private var queueTotal = 1
    @Volatile private var queueIndex = 1

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CANCEL -> {
                jobCancel = true
                captionCancel = true
                session?.cancel()
                return START_NOT_STICKY
            }
            ACTION_CANCEL_ALL -> {
                cancelAll = true
                jobCancel = true
                captionCancel = true
                session?.cancel()
                scope.launch { container().jobs.cancelAllQueued() }
                return START_NOT_STICKY
            }
            ACTION_CANCEL_JOB -> {
                val id = intent.getStringExtra(EXTRA_JOB_ID)
                if (id != null && id == currentJobId) {
                    jobCancel = true
                    captionCancel = true
                    session?.cancel()
                } else if (id != null) {
                    scope.launch { container().jobs.cancelQueued(id) }
                }
                return START_NOT_STICKY
            }
            ACTION_CANCEL_CAPTIONS -> {
                captionCancel = true
                return START_NOT_STICKY
            }
            ACTION_ENQUEUE, ACTION_START -> {
                val jobId = intent.getStringExtra(EXTRA_JOB_ID)
                startAsForeground(jobId ?: currentJobId.orEmpty(), 0)
                if (work?.isActive != true) {
                    cancelAll = false
                    jobCancel = false
                    captionCancel = false
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
            Notifications.clearCaptions(this)
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
        val probed = runCatchingLog(TAG, "probe source") { app.probe.probe(sourceUri) }.getOrNull()
        val audioProbed = job.audioUri.takeIf { it.isNotBlank() }?.let { uri ->
            runCatchingLog(TAG, "probe audio") { app.probe.probe(Uri.parse(uri)) }.getOrNull()
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
        if (!cancelAll) {
            jobCancel = false
            captionCancel = false
        }
        app.jobs.updateStatus(jobId, JobStatus.RUNNING)
        val audioOnly = settings.audioOutput(source.hasVideo)
        val output = app.inputs.encodeOutputFile(jobId, settings.outputExtension())
        output.delete()
        val log = StringBuilder()
        RecordCaptureLog.extract(app.jobLogs.read(jobId))?.let {
            log.appendLine(it)
            log.appendLine()
        }
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
                    var publishFile = output
                    if (settings.wantsCaptions()) {
                        captionCancel = captionCancel || cancelAll || jobCancel
                        startAsForeground(jobId, 90)
                        val notice = captionNotifier(jobId)
                        try {
                            val cap = app.captions.apply(
                                media = output,
                                settings = settings,
                                workDir = output.parentFile ?: File(cacheDir, "encode").also { it.mkdirs() },
                                stem = "$jobId-cap",
                                onProgress = { fraction, message ->
                                    val mapped = (0.9f + 0.09f * fraction.coerceIn(0f, 1f)).coerceIn(0f, 0.99f)
                                    app.encodeProgress.update(EncodeProgress(jobId, mapped, 0L, message))
                                    notice.update(fraction, message)
                                },
                                isCancelled = { captionCancel || jobCancel || cancelAll },
                            )
                            publishFile = cap.media
                            log.append(cap.log)
                            if (!cap.log.endsWith("\n")) log.appendLine()
                            cap.srt?.let { srt ->
                                val sidecarName = compressedName(job.displayName, settings)
                                    .substringBeforeLast('.')
                                val sidecar = app.exporter.publishSidecar(
                                    srt,
                                    sidecarName,
                                    settings.galleryFolder(),
                                )
                                log.appendLine("srt=${sidecar ?: "unpublished"}")
                                srt.delete()
                            }
                        } catch (err: CancellationException) {
                            throw err
                        } catch (err: Throwable) {
                            AppLog.e(TAG, "captions", err)
                            if (jobCancel || cancelAll) {
                                output.delete()
                                log.appendLine("captions cancelled")
                                app.jobLogs.write(jobId, log.toString())
                                app.jobs.updateStatus(jobId, JobStatus.CANCELLED, finished = true)
                                app.encodeProgress.update(null)
                                return
                            }
                            log.appendLine(
                                if (captionCancel || err.message == "cancelled") {
                                    "captions cancelled"
                                } else {
                                    "captions failed: ${err.message}"
                                },
                            )
                        } finally {
                            notice.clear()
                        }
                    }
                    val published = app.exporter.publish(
                        publishFile,
                        compressedName(job.displayName, settings),
                        settings.outputMime(),
                        settings.galleryFolder(),
                    )
                    val bytes = publishFile.length()
                    if (publishFile != output) output.delete()
                    publishFile.delete()
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
            AppLog.e(TAG, "encode job", t)
            output.delete()
            log.appendLine("exception: ${t.message}")
            log.appendLine(t.stackTraceToString())
            runCatchingLog(TAG, "write job log") { app.jobLogs.write(jobId, log.toString()) }
            app.jobs.updateStatus(
                jobId,
                JobStatus.FAILED,
                error = t.message ?: getString(R.string.error_compression_failed),
                finished = true,
            )
            app.encodeProgress.update(null)
        } finally {
            runCatchingLog(TAG, "write job log") {
                if (app.jobLogs.read(jobId) == null) app.jobLogs.write(jobId, log.toString())
            }
            runCatchingLog(TAG, "prune history") { app.history.prune() }
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
        var ffmpegInput = app.inputs.resolveForFfmpeg(sourceUri, jobId)
        var ffmpegAudio = source.audioUri.takeIf { it.isNotBlank() }?.let { uri ->
            app.inputs.resolveForFfmpeg(Uri.parse(uri), jobId, "audio")
        }
        val caps = app.encoderCapabilities()
        val passLog = app.inputs.passLogPrefix(jobId)
        fun planOf(
            encodeSettings: EncodeSettings,
            encoderOverride: String? = null,
            pixFmtOverride: String? = null,
        ) = FfmpegCommandBuilder.build(
            ffmpegInput, output.absolutePath, encodeSettings, source, caps,
            pixFmtOverride = pixFmtOverride,
            encoderOverride = encoderOverride,
            audioInput = ffmpegAudio,
            passLogPrefix = passLog,
        )
        suspend fun refreshInputs() {
            val nextInput = app.inputs.refreshFfmpegInput(sourceUri, jobId, ffmpegInput)
            if (nextInput != ffmpegInput) {
                log.appendLine("reopened input for FFmpeg")
                ffmpegInput = nextInput
            }
            val audioUri = source.audioUri.takeIf { it.isNotBlank() } ?: return
            val currentAudio = ffmpegAudio ?: return
            val nextAudio = app.inputs.refreshFfmpegInput(Uri.parse(audioUri), jobId, currentAudio, "audio")
            if (nextAudio != currentAudio) ffmpegAudio = nextAudio
        }
        var plan = planOf(settings)
        if (plan.firstPassArgs != null) {
            var copied = false
            if (InputResolver.isSafParameter(ffmpegInput)) {
                ffmpegInput = app.inputs.copyToCache(sourceUri, jobId).absolutePath
                copied = true
            }
            val audioUri = source.audioUri.takeIf { it.isNotBlank() }
            if (audioUri != null && ffmpegAudio != null && InputResolver.isSafParameter(ffmpegAudio)) {
                ffmpegAudio = app.inputs.copyToCache(Uri.parse(audioUri), jobId, "audio").absolutePath
                copied = true
            }
            if (copied) {
                log.appendLine("copied input; 2-pass cannot reuse a SAF pipe")
                plan = planOf(settings)
            }
        }
        try {
            if (settings.ffmpegCommandOverride.isNotBlank()) {
                val args = FfmpegMuxCommands.ensureGrayscale(
                    FfmpegCommandTemplate.materialize(
                        settings.ffmpegCommandOverride,
                        ffmpegInput,
                        output.absolutePath,
                        ffmpegAudio,
                    ).getOrThrow(),
                    settings.grayscale && !settings.audioOutput(source.hasVideo),
                )
                log.appendLine("using edited command template")
                return executePlan(jobId, source, settings, plan.copy(args = args, firstPassArgs = null), log)
            }
            var encodeSettings = settings
            var result = executePlan(jobId, source, encodeSettings, plan, log)
            if (!result.success && !result.cancelled && plan.firstPassArgs != null) {
                log.appendLine("2-pass failed; retrying one pass")
                output.delete()
                app.inputs.deletePassLogs(jobId)
                encodeSettings = settings.copy(twoPass = false)
                refreshInputs()
                plan = planOf(encodeSettings, plan.videoEncoder, plan.pixFmt)
                result = executePlan(jobId, source, encodeSettings, plan, log)
            }
            while (!result.success && !result.cancelled) {
                refreshInputs()
                val next = FfmpegCommandBuilder.fallbackPlan(
                    plan, ffmpegInput, output.absolutePath, encodeSettings, source, caps, ffmpegAudio, passLog,
                ) ?: break
                log.appendLine("retrying with fallback encoder/pix_fmt")
                output.delete()
                app.inputs.deletePassLogs(jobId)
                plan = next
                result = executePlan(jobId, source, encodeSettings, plan, log)
            }
            return result
        } finally {
            app.inputs.deletePassLogs(jobId)
        }
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
                startAsForeground(jobId, (fraction * 100).toInt(), spec.encoderLabel)
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
        val clip = Media3EncodePlanner.clipWindow(settings, source.durationMs)
        if (clip.active) {
            log.appendLine("clip start=${clip.startMs}ms end=${clip.endMs ?: "eos"}")
        }
        val pass1 = plan.firstPassArgs
        if (pass1.isNullOrEmpty()) {
            log.appendLine("FFmpeg command: ${quoteArgs(plan.args)}")
            return runFfmpegArgs(jobId, source, settings, plan.args, log, 0f, 0.99f, null)
        }
        log.appendLine("FFmpeg 2-pass encode")
        log.appendLine("pass 1: ${quoteArgs(pass1)}")
        val first = runFfmpegArgs(
            jobId, source, settings, pass1, log, 0f, 0.48f, getString(R.string.compress_pass_1),
        )
        if (!first.success || first.cancelled) return first
        if (FfmpegSessionLogs.encodedNoMedia(first.logs)) {
            log.appendLine("pass 1 encoded no frames")
            return first.copy(
                success = false,
                error = first.error ?: getString(R.string.error_two_pass_empty),
            )
        }
        log.appendLine("pass 2: ${quoteArgs(plan.args)}")
        return runFfmpegArgs(
            jobId, source, settings, plan.args, log, 0.48f, 0.99f, getString(R.string.compress_pass_2),
        )
    }

    private suspend fun runFfmpegArgs(
        jobId: String,
        source: SourceVideo,
        settings: EncodeSettings,
        args: List<String>,
        log: StringBuilder,
        progressStart: Float,
        progressEnd: Float,
        message: String?,
    ): EncodeResult {
        val lastStatsAt = AtomicLong(System.currentTimeMillis())
        val lastTimeMs = AtomicLong(0L)
        val lastFrame = AtomicInteger(0)
        val stalled = AtomicBoolean(false)
        val hasMediaTime = AtomicBoolean(false)
        val durationMs = Media3EncodePlanner.outputDurationMs(settings, source)
        val fps = FfmpegCommandBuilder.outputFrameRate(source, settings)
        val startedAt = System.currentTimeMillis()
        val stallPrefs = container().prefs.current()
        val stallMs = if (message != null) {
            EncodeStallTimeout.toMs(stallPrefs.twoPassStallTimeoutSec)
        } else {
            EncodeStallTimeout.toMs(stallPrefs.stallTimeoutSec)
        }
        val progressFile = container().inputs.ffmpegProgressFile(jobId)
        progressFile.delete()
        fun publish(timeMs: Long, force: Boolean = false) {
            lastStatsAt.set(System.currentTimeMillis())
            val previous = lastTimeMs.get()
            if (!force && timeMs <= previous) return
            if (timeMs > previous) lastTimeMs.set(timeMs)
            val span = (progressEnd - progressStart).coerceAtLeast(0f)
            val local = if (durationMs > 0) {
                (timeMs.toFloat() / durationMs).coerceIn(0f, 1f)
            } else {
                0f
            }
            val fraction = (progressStart + local * span).coerceIn(0f, 0.99f)
            container().encodeProgress.update(EncodeProgress(jobId, fraction, timeMs, message))
            startAsForeground(jobId, (fraction * 100).toInt(), message, twoPass = message != null)
        }
        fun note(statsTimeMs: Long, frames: Int, logTimeMs: Long) {
            if (frames > lastFrame.get()) lastFrame.set(frames)
            val timeMs = FfmpegEncodeProgress.timeMs(
                statsTimeMs = statsTimeMs,
                videoFrameNumber = lastFrame.get(),
                fps = fps,
                logTimeMs = logTimeMs,
                logFrame = lastFrame.get(),
            )
            if (timeMs > 0L) hasMediaTime.set(true)
            publish(timeMs)
        }
        publish(0L, force = true)
        val current = container().ffmpeg.encode(
            args = FfmpegEncodeProgress.withProgressArg(args, progressFile.absolutePath),
            onLog = { line ->
                if (line.isNotBlank()) log.appendLine(line)
                val snap = FfmpegEncodeProgress.parseLine(line) ?: return@encode
                note(0L, snap.frame ?: 0, snap.timeMs ?: 0L)
            },
            onStats = { stats ->
                note(stats.timeMs, stats.videoFrameNumber, 0L)
            },
        )
        session = current
        val watchdog = scope.launch {
            while (isActive) {
                delay(250)
                val now = System.currentTimeMillis()
                val snap = FfmpegEncodeProgress.parseDump(FfmpegEncodeProgress.readDump(progressFile))
                if (snap != null) {
                    lastStatsAt.set(now)
                    val frames = snap.frame ?: 0
                    val logTime = snap.timeMs ?: 0L
                    val computed = FfmpegEncodeProgress.timeMs(0L, frames, fps, logTime, frames)
                    if (computed > 0L) {
                        hasMediaTime.set(true)
                        note(0L, frames, logTime)
                    } else if (snap.continuing && !hasMediaTime.get()) {
                        publish(FfmpegEncodeProgress.wallClockTimeMs(startedAt, durationMs, now))
                    }
                }
                if (now - lastStatsAt.get() > stallMs) {
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
            progressFile.delete()
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

    private fun startAsForeground(
        jobId: String,
        percent: Int,
        statusMessage: String? = null,
        twoPass: Boolean = false,
    ) {
        val cancel = PendingIntent.getService(
            this,
            2,
            Intent(this, CompressService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = Notifications.encoding(
            this, jobId, percent, queueIndex, queueTotal, cancel, statusMessage, twoPass,
        )
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
        runCatchingLog(TAG, "release wake lock") { wakeLock?.release() }
        wakeLock = null
    }

    private fun captionNotifier(jobId: String): CaptionProgressNotifier {
        val cancel = PendingIntent.getService(
            this,
            4,
            Intent(this, CompressService::class.java).setAction(ACTION_CANCEL_CAPTIONS),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return CaptionProgressNotifier(this, cancel, "jobId" to jobId)
    }

    override fun onDestroy() {
        jobCancel = true
        captionCancel = true
        session?.cancel()
        Notifications.clearCaptions(this)
        scope.cancel()
        releaseWakeLock()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "CompressService"
        const val ACTION_START = "com.androidcompress.app.ENCODE_START"
        const val ACTION_ENQUEUE = "com.androidcompress.app.ENCODE_ENQUEUE"
        const val ACTION_CANCEL = "com.androidcompress.app.ENCODE_CANCEL"
        const val ACTION_CANCEL_ALL = "com.androidcompress.app.ENCODE_CANCEL_ALL"
        const val ACTION_CANCEL_JOB = "com.androidcompress.app.ENCODE_CANCEL_JOB"
        const val ACTION_CANCEL_CAPTIONS = "com.androidcompress.app.ENCODE_CANCEL_CAPTIONS"
        const val EXTRA_JOB_ID = "jobId"
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

        fun cancelCaptions(context: Context) {
            context.startService(Intent(context, CompressService::class.java).setAction(ACTION_CANCEL_CAPTIONS))
        }
    }
}
