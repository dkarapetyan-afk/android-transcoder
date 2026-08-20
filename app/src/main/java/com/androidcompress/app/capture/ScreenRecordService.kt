package com.androidcompress.app.capture

import android.app.Activity
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.view.WindowManager
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.window.layout.WindowMetricsCalculator
import com.androidcompress.app.R
import com.androidcompress.app.container
import com.androidcompress.app.data.JobStatus
import com.androidcompress.app.data.RecordAudioMode
import com.androidcompress.app.data.RecordResolution
import com.androidcompress.app.encode.FfmpegMuxCommands
import com.androidcompress.app.util.Notifications
import com.androidcompress.app.util.even
import com.androidcompress.app.util.formatDuration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.resume

class ScreenRecordService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val handler = Handler(Looper.getMainLooper())
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var recorder: MediaRecorder? = null
    private var internalAudio: PcmWavCapture? = null
    private var micAudio: PcmWavCapture? = null
    private var outputFile: File? = null
    private var audioFile: File? = null
    private var micAudioFile: File? = null
    private var jobId: String? = null
    private var ticker: Job? = null
    private var sessionJob: Job? = null
    private var stopping = false
    private var paused = false
    private var encoderStarted = false
    private var usesMicrophone = false
    private var usesCamera = false
    private var options = RecordOptions()
    private var encodeSize: Pair<Int, Int> = 1280 to 720
    private var overlays: RecordOverlayHost? = null
    private var stopNotice: String? = null

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            scope.launch { finalizeRecording() }
        }

        override fun onCapturedContentResize(width: Int, height: Int) {
            // VirtualDisplay is recreated only on a new session; ignore mid-session resize.
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                scope.launch { finalizeRecording() }
                return START_NOT_STICKY
            }
            ACTION_PAUSE -> {
                pauseRecording()
                return START_NOT_STICKY
            }
            ACTION_RESUME -> {
                resumeRecording()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                if (mediaProjection != null || sessionJob != null) return START_NOT_STICKY
                sessionJob = scope.launch { startRecording(intent) }
            }
        }
        return START_NOT_STICKY
    }

    private suspend fun startRecording(intent: Intent) {
        val id = intent.getStringExtra(EXTRA_JOB_ID) ?: return stopSelf()
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
        val data = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_RESULT_DATA)
        }
        if (resultCode != Activity.RESULT_OK || data == null) {
            container().recording.fail(getString(R.string.error_capture_denied))
            stopSelf()
            return
        }
        jobId = id
        options = RecordOptions.fromJson(intent.getStringExtra(EXTRA_OPTIONS)).resolvedForSdk(Build.VERSION.SDK_INT)
        usesMicrophone = options.audioMode.usesMicrophone
        overlays = RecordOverlayHost(this)

        startAsForeground(0)
        val manager = getSystemService(MediaProjectionManager::class.java)
        val projection = manager.getMediaProjection(resultCode, data)
        if (projection == null) {
            container().recording.fail(getString(R.string.error_start_capture))
            abortDraft(id)
            stopSelf()
            return
        }
        projection.registerCallback(projectionCallback, handler)
        mediaProjection = projection

        val store = container().recording
        if (options.captureRegion) {
            store.prepare(id, RecordPhase.REGION)
            startAsForeground(0)
            RecordTileService.requestListening(this)
            val confirmed = awaitRegion()
            if (confirmed == null || stopping) {
                if (!stopping) finalizeRecording(cancelledBeforeStart = true)
                return
            }
            options = options.copy(region = confirmed)
        }

        if (options.countdownSeconds > 0 && !stopping) {
            store.prepare(id, RecordPhase.COUNTDOWN)
            for (n in options.countdownSeconds downTo 1) {
                if (stopping) return
                store.setPhase(RecordPhase.COUNTDOWN, n)
                overlays?.showCountdown(n)
                startAsForeground(0)
                RecordTileService.requestListening(this)
                delay(1_000)
            }
            overlays?.hideCountdown()
        }
        if (stopping) return
        beginEncoder(id, projection)
    }

    private suspend fun awaitRegion(): RecordRegion? = suspendCancellableCoroutine { cont ->
        val host = overlays
        if (host == null || !canDrawOverlays(this)) {
            cont.resume(RecordRegion.FULL)
            return@suspendCancellableCoroutine
        }
        host.showRegion(
            onConfirm = { region -> if (cont.isActive) cont.resume(region) },
            onCancel = { if (cont.isActive) cont.resume(null) },
        )
        cont.invokeOnCancellation { host.hideRegion() }
    }

    private fun beginEncoder(id: String, projection: MediaProjection) {
        val size = captureSize(options.resolution)
        encodeSize = size
        val file = container().inputs.recordOutputFile(id)
        outputFile = file
        try {
            val rec = prepareRecorder(file, size.first, size.second, options)
            recorder = rec
            virtualDisplay = projection.createVirtualDisplay(
                "RecordingCompressor",
                size.first,
                size.second,
                densityDpi(),
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                rec.surface,
                null,
                handler,
            )
            val audioMode = options.audioMode
            if (audioMode.usesInternalAudio && Build.VERSION.SDK_INT >= 29) {
                val wav = container().inputs.recordAudioFile(id)
                audioFile = wav
                val uid = CaptureApps.uid(packageManager, options.internalAudioPackage)
                internalAudio = PcmWavCapture.internal(projection, wav, uid).also { it.start() }
            }
            if (audioMode == RecordAudioMode.BOTH) {
                val micWav = container().inputs.recordMicAudioFile(id)
                micAudioFile = micWav
                micAudio = PcmWavCapture.microphone(micWav).also { it.start() }
            }
            rec.start()
            encoderStarted = true
            container().recording.startCapturing()
            if (container().recording.state.value.jobId == null) {
                container().recording.start(id)
            }
            scope.launch { container().jobs.updateStatus(id, JobStatus.RECORDING) }
            if (options.showBubble) {
                overlays?.showBubble(
                    paused = false,
                    onPauseResume = {
                        if (paused) resumeRecording() else pauseRecording()
                    },
                    onStop = { requestStop() },
                )
            }
            if (options.facecam && hasCameraPermission()) {
                usesCamera = true
                startAsForeground(0)
                overlays?.showFacecam()
            }
            TapHighlightService.setRecording(true, options.showTaps)
            RecordTileService.requestListening(this)
            ticker = scope.launch {
                while (isActive && !stopping) {
                    delay(1_000)
                    if (stopping || paused || !encoderStarted) continue
                    val elapsed = container().recording.state.value.elapsedMs()
                    startAsForeground(elapsed)
                    when (
                        StorageGuard.reason(
                            elapsedMs = elapsed,
                            maxDurationMs = options.maxDurationMs,
                            lowStorageEnabled = options.autoStopLowStorage,
                            availableBytes = availableBytes(),
                            recordedBytes = outputFile?.length() ?: 0L,
                        )
                    ) {
                        RecordAutoStop.DURATION -> {
                            requestStop(getString(R.string.record_stopped_duration))
                            return@launch
                        }
                        RecordAutoStop.STORAGE -> {
                            requestStop(getString(R.string.record_stopped_storage))
                            return@launch
                        }
                        null -> Unit
                    }
                }
            }
        } catch (t: Throwable) {
            container().recording.fail(t.message ?: getString(R.string.error_start_recorder))
            abortDraft(id)
            teardown()
            stopSelf()
        }
    }

    private fun pauseRecording() {
        if (stopping || paused || !encoderStarted || recorder == null) return
        try {
            recorder?.pause()
            internalAudio?.pause()
            micAudio?.pause()
            paused = true
            container().recording.setPaused(true)
            overlays?.updateBubble(true)
            startAsForeground(container().recording.state.value.elapsedMs(), paused = true)
            RecordTileService.requestListening(this)
        } catch (_: Throwable) {
            runCatching { recorder?.resume() }
            internalAudio?.resume()
            micAudio?.resume()
            paused = false
        }
    }

    private fun resumeRecording() {
        if (stopping || !paused || recorder == null) return
        try {
            recorder?.resume()
            internalAudio?.resume()
            micAudio?.resume()
            paused = false
            container().recording.setPaused(false)
            overlays?.updateBubble(false)
            startAsForeground(container().recording.state.value.elapsedMs(), paused = false)
            RecordTileService.requestListening(this)
        } catch (_: Throwable) {
            // Leave the session paused if the encoder cannot resume.
        }
    }

    private fun requestStop(notice: String? = null) {
        if (stopping) return
        if (notice != null) stopNotice = notice
        scope.launch { finalizeRecording() }
    }

    private suspend fun finalizeRecording(cancelledBeforeStart: Boolean = false) {
        if (stopping) return
        stopping = true
        val tick = ticker
        ticker = null
        tick?.cancel()
        withContext(NonCancellable) {
            saveRecording(cancelledBeforeStart)
        }
    }

    private suspend fun saveRecording(cancelledBeforeStart: Boolean) {
        overlays?.dismissAll()
        TapHighlightService.setRecording(false, false)
        val id = jobId
        val video = outputFile
        val wav = audioFile
        val micWav = micAudioFile
        val started = encoderStarted
        if (!started || cancelledBeforeStart) {
            teardown()
            if (id != null) abortDraft(id)
            container().recording.fail(
                if (cancelledBeforeStart) getString(R.string.record_cancelled)
                else getString(R.string.error_recording_no_video),
            )
            RecordTileService.requestListening(this)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        container().recording.markSaving()
        startAsForeground(container().recording.state.value.elapsedMs(), saving = true)
        RecordTileService.requestListening(this)
        try {
            runCatching { recorder?.stop() }
            runCatching { recorder?.reset() }
            runCatching { recorder?.release() }
            recorder = null
            internalAudio?.stop()
            internalAudio = null
            micAudio?.stop()
            micAudio = null
            virtualDisplay?.release()
            virtualDisplay = null
            mediaProjection?.unregisterCallback(projectionCallback)
            mediaProjection?.stop()
            mediaProjection = null

            if (id == null || video == null || !video.exists() || video.length() < 1024) {
                val noVideo = getString(R.string.error_recording_no_video)
                container().recording.fail(noVideo)
                if (id != null) container().jobs.updateStatus(id, JobStatus.FAILED, error = noVideo, finished = true)
                return
            }

            var finalFile = video
            val processed = File(video.parentFile, "${video.nameWithoutExtension}-muxed.mp4")
            val usableInternal = wav.takeIf { it != null && it.exists() && it.length() > 44 }
            val usableMic = micWav.takeIf { it != null && it.exists() && it.length() > 44 }
            val crop = options.region?.encoderCrop(encodeSize.first, encodeSize.second)
            val caps = runCatching { container().encoderCapabilities() }.getOrNull()
            val cropEncoder = when {
                caps?.hasOpenH264 == true -> "libopenh264"
                else -> "mpeg4"
            }
            val cropBitrate = ((crop?.width ?: encodeSize.first) * (crop?.height ?: encodeSize.second) * 4 / 1000)
                .coerceIn(800, 12_000)
            val post = FfmpegMuxCommands.recordingPostProcess(
                videoPath = video.absolutePath,
                outputPath = processed.absolutePath,
                internalWav = usableInternal?.absolutePath,
                micWav = usableMic?.absolutePath,
                crop = crop,
                videoHasAudio = options.audioMode == RecordAudioMode.MICROPHONE,
                internalGainPercent = options.internalGainPercent,
                micGainPercent = options.micGainPercent,
                duckAppAudio = options.duckAppAudio && usableInternal != null && usableMic != null,
                videoEncoder = cropEncoder,
                videoBitrateKbps = cropBitrate,
            )
            if (post != null) {
                val muxResult = container().ffmpeg.encode(post, onLog = {}, onStats = {}).await()
                if (muxResult.success && processed.exists() && processed.length() > 1024) {
                    video.delete()
                    finalFile = if (processed.renameTo(video)) video else processed
                }
            }
            wav?.delete()
            micWav?.delete()
            if (finalFile != processed) processed.delete()

            val uri = Uri.fromFile(finalFile)
            val probed = runCatching { container().probe.probe(uri) }.getOrNull()
            val existing = container().jobs.get(id)
            val direct = options.directEncode
            var outputUri: String? = null
            var outputBytes: Long? = null
            var status = JobStatus.READY
            if (direct) {
                val published = runCatching {
                    container().exporter.publish(
                        finalFile,
                        directDisplayName(),
                        "video/mp4",
                        "Movies/RecordingCompressor",
                    )
                }.getOrNull()
                if (published != null) {
                    outputUri = published.toString()
                    outputBytes = probed?.bytes ?: finalFile.length()
                    status = JobStatus.SUCCEEDED
                    runCatching { finalFile.delete() }
                }
            }
            if (existing != null) {
                container().jobs.upsert(
                    existing.copy(
                        status = status,
                        sourceUri = outputUri ?: uri.toString(),
                        outputUri = outputUri,
                        outputBytes = outputBytes,
                        sourceBytes = probed?.bytes ?: finalFile.length(),
                        durationMs = probed?.durationMs ?: existing.durationMs,
                        width = probed?.width ?: existing.width,
                        height = probed?.height ?: existing.height,
                        displayName = probed?.displayName ?: existing.displayName,
                        finishedAt = if (status == JobStatus.SUCCEEDED) System.currentTimeMillis() else null,
                    ),
                )
            }
            container().recording.finish(
                jobId = id,
                openResult = status == JobStatus.SUCCEEDED,
                notice = stopNotice,
            )
        } catch (t: CancellationException) {
            throw t
        } catch (t: Throwable) {
            container().recording.fail(t.message ?: getString(R.string.error_save_recording))
            if (id != null) {
                container().jobs.updateStatus(id, JobStatus.FAILED, error = t.message, finished = true)
            }
        } finally {
            RecordTileService.requestListening(this)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun prepareRecorder(
        file: File,
        width: Int,
        height: Int,
        options: RecordOptions,
    ): MediaRecorder {
        val codecs = if (options.directEncode) {
            listOf(options.videoCodec, RecordVideoCodec.HEVC, RecordVideoCodec.H264).distinct()
        } else {
            listOf(RecordVideoCodec.H264)
        }
        var last: Throwable? = null
        for (codec in codecs) {
            val rec = createRecorder(file, width, height, options.copy(videoCodec = codec))
            try {
                rec.prepare()
                return rec
            } catch (t: Throwable) {
                last = t
                runCatching { rec.reset() }
                runCatching { rec.release() }
            }
        }
        throw last ?: IllegalStateException(getString(R.string.error_start_recorder))
    }

    private fun createRecorder(
        file: File,
        width: Int,
        height: Int,
        options: RecordOptions,
    ): MediaRecorder {
        val rec = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(this) else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }
        val audioMode = options.audioMode
        if (audioMode == RecordAudioMode.MICROPHONE) {
            rec.setAudioSource(MediaRecorder.AudioSource.MIC)
        }
        rec.setVideoSource(MediaRecorder.VideoSource.SURFACE)
        rec.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        rec.setOutputFile(file.absolutePath)
        val codec = if (options.directEncode) options.videoCodec else RecordVideoCodec.H264
        rec.setVideoEncoder(codec.mediaRecorderValue())
        if (audioMode == RecordAudioMode.MICROPHONE) {
            rec.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            rec.setAudioEncodingBitRate(128_000)
            rec.setAudioSamplingRate(44_100)
        }
        rec.setVideoSize(width, height)
        rec.setVideoFrameRate(30)
        rec.setVideoEncodingBitRate(codec.videoBitrate(width, height, options.directEncode))
        return rec
    }

    private fun captureSize(resolution: RecordResolution): Pair<Int, Int> {
        val metrics = WindowMetricsCalculator.getOrCreate().computeMaximumWindowMetrics(this)
        var w = even(metrics.bounds.width().coerceAtLeast(2))
        var h = even(metrics.bounds.height().coerceAtLeast(2))
        val cap = when (resolution) {
            RecordResolution.P720 -> 720
            RecordResolution.P1080 -> 1080
            RecordResolution.DISPLAY -> maxOf(w, h)
        }
        val longSide = maxOf(w, h)
        if (longSide > cap) {
            val scale = cap.toDouble() / longSide
            w = even((w * scale).toInt().coerceAtLeast(2))
            h = even((h * scale).toInt().coerceAtLeast(2))
        }
        return w to h
    }

    private fun densityDpi(): Int {
        val wm = getSystemService(WindowManager::class.java)
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getMetrics(metrics)
        return metrics.densityDpi
    }

    private fun startAsForeground(elapsedMs: Long, paused: Boolean = this.paused, saving: Boolean = false) {
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, ScreenRecordService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val pauseResume = PendingIntent.getService(
            this,
            2,
            Intent(this, ScreenRecordService::class.java)
                .setAction(if (paused) ACTION_RESUME else ACTION_PAUSE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val store = container().recording.state.value
        val elapsedLabel = when (store.phase) {
            RecordPhase.REGION -> getString(R.string.record_phase_region)
            RecordPhase.COUNTDOWN -> getString(R.string.record_countdown_n, store.countdownRemaining)
            else -> formatDuration(elapsedMs)
        }
        ServiceCompat.startForeground(
            this,
            Notifications.RECORD_ID,
            Notifications.recording(
                context = this,
                elapsed = elapsedLabel,
                stopIntent = stop,
                pauseResumeIntent = pauseResume,
                paused = paused,
                saving = saving,
                preparing = store.phase == RecordPhase.REGION || store.phase == RecordPhase.COUNTDOWN,
            ),
            foregroundTypes(),
        )
    }

    private fun foregroundTypes(): Int {
        var types = 0
        if (Build.VERSION.SDK_INT >= 29) {
            types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        }
        if (usesMicrophone && Build.VERSION.SDK_INT >= 30) {
            types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        }
        if (usesCamera && Build.VERSION.SDK_INT >= 30) {
            types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
        }
        return types
    }

    private fun teardown() {
        overlays?.dismissAll()
        TapHighlightService.setRecording(false, false)
        runCatching { recorder?.reset() }
        runCatching { recorder?.release() }
        recorder = null
        internalAudio?.stop()
        internalAudio = null
        micAudio?.stop()
        micAudio = null
        virtualDisplay?.release()
        virtualDisplay = null
        mediaProjection?.unregisterCallback(projectionCallback)
        mediaProjection?.stop()
        mediaProjection = null
    }

    private fun abortDraft(id: String) {
        scope.launch { runCatching { container().jobs.delete(id) } }
    }

    private fun hasCameraPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED

    private fun availableBytes(): Long {
        val dir = outputFile?.parentFile ?: cacheDir
        return runCatching { dir.usableSpace }.getOrDefault(Long.MAX_VALUE)
    }

    private fun directDisplayName(): String {
        val stamp = SimpleDateFormat("yyyy-MM-dd HHmmss", Locale.US).format(Date())
        return "Screen recording $stamp.mp4"
    }

    override fun onDestroy() {
        teardown()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.androidcompress.app.RECORD_START"
        const val ACTION_STOP = "com.androidcompress.app.RECORD_STOP"
        const val ACTION_PAUSE = "com.androidcompress.app.RECORD_PAUSE"
        const val ACTION_RESUME = "com.androidcompress.app.RECORD_RESUME"
        const val EXTRA_JOB_ID = "jobId"
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_RESULT_DATA = "resultData"
        const val EXTRA_OPTIONS = "options"

        fun start(
            context: Context,
            jobId: String,
            resultCode: Int,
            data: Intent,
            options: RecordOptions,
        ) {
            val intent = Intent(context, ScreenRecordService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_JOB_ID, jobId)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_RESULT_DATA, data)
                .putExtra(EXTRA_OPTIONS, options.resolvedForSdk(Build.VERSION.SDK_INT).toJson())
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.startService(Intent(context, ScreenRecordService::class.java).setAction(ACTION_STOP))
        }

        fun pause(context: Context) {
            context.startService(Intent(context, ScreenRecordService::class.java).setAction(ACTION_PAUSE))
        }

        fun resume(context: Context) {
            context.startService(Intent(context, ScreenRecordService::class.java).setAction(ACTION_RESUME))
        }
    }
}
