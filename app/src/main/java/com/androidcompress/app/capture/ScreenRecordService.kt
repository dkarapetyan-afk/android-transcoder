package com.androidcompress.app.capture

import android.app.Activity
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
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
import androidx.window.layout.WindowMetricsCalculator
import com.androidcompress.app.R
import com.androidcompress.app.container
import com.androidcompress.app.data.JobStatus
import com.androidcompress.app.data.RecordAudioMode
import com.androidcompress.app.data.RecordResolution
import com.androidcompress.app.util.Notifications
import com.androidcompress.app.util.even
import com.androidcompress.app.util.formatDuration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

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
    private var stopping = false
    private var paused = false
    private var usesMicrophone = false

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            scope.launch { finalizeRecording(userStopped = true) }
        }

        override fun onCapturedContentResize(width: Int, height: Int) {
            // VirtualDisplay is recreated only on a new session; ignore mid-session resize.
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                scope.launch { finalizeRecording(userStopped = true) }
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
            ACTION_START -> startRecording(intent)
        }
        return START_NOT_STICKY
    }

    private fun startRecording(intent: Intent) {
        if (mediaProjection != null) return
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
        val audioMode = (
            intent.getStringExtra(EXTRA_AUDIO_MODE)
                ?.let { runCatching { RecordAudioMode.valueOf(it) }.getOrNull() }
                ?: RecordAudioMode.NONE
            ).resolvedForSdk(Build.VERSION.SDK_INT)
        val resolution = intent.getStringExtra(EXTRA_RESOLUTION)
            ?.let { runCatching { RecordResolution.valueOf(it) }.getOrNull() }
            ?: RecordResolution.P1080
        usesMicrophone = audioMode.usesMicrophone

        startAsForeground(0)
        val manager = getSystemService(MediaProjectionManager::class.java)
        val projection = manager.getMediaProjection(resultCode, data)
        if (projection == null) {
            container().recording.fail(getString(R.string.error_start_capture))
            stopSelf()
            return
        }
        projection.registerCallback(projectionCallback, handler)
        mediaProjection = projection

        val size = captureSize(resolution)
        val file = container().inputs.recordOutputFile(id)
        outputFile = file
        try {
            val rec = createRecorder(file, size.first, size.second, audioMode)
            recorder = rec
            rec.prepare()
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
            if (audioMode.usesInternalAudio && Build.VERSION.SDK_INT >= 29) {
                val wav = container().inputs.recordAudioFile(id)
                audioFile = wav
                internalAudio = PcmWavCapture.internal(projection, wav).also { it.start() }
            }
            if (audioMode == RecordAudioMode.BOTH) {
                val micWav = container().inputs.recordMicAudioFile(id)
                micAudioFile = micWav
                micAudio = PcmWavCapture.microphone(micWav).also { it.start() }
            }
            rec.start()
            container().recording.start(id)
            scope.launch {
                container().jobs.updateStatus(id, JobStatus.RECORDING)
            }
            ticker = scope.launch {
                while (isActive) {
                    if (!stopping) {
                        startAsForeground(container().recording.state.value.elapsedMs())
                    }
                    delay(1_000)
                }
            }
        } catch (t: Throwable) {
            container().recording.fail(t.message ?: getString(R.string.error_start_recorder))
            teardown()
            stopSelf()
        }
    }

    private fun pauseRecording() {
        if (stopping || paused || recorder == null) return
        try {
            recorder?.pause()
            internalAudio?.pause()
            micAudio?.pause()
            paused = true
            container().recording.setPaused(true)
            startAsForeground(container().recording.state.value.elapsedMs(), paused = true)
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
            startAsForeground(container().recording.state.value.elapsedMs(), paused = false)
        } catch (_: Throwable) {
            // Leave the session paused if the encoder cannot resume.
        }
    }

    private suspend fun finalizeRecording(@Suppress("UNUSED_PARAMETER") userStopped: Boolean) {
        if (stopping) return
        stopping = true
        ticker?.cancel()
        val id = jobId
        val video = outputFile
        val wav = audioFile
        val micWav = micAudioFile
        container().recording.markSaving()
        startAsForeground(container().recording.state.value.elapsedMs(), saving = true)
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
            val muxed = File(video.parentFile, "${video.nameWithoutExtension}-muxed.mp4")
            val usableInternal = wav.takeIf { it != null && it.exists() && it.length() > 44 }
            val usableMic = micWav.takeIf { it != null && it.exists() && it.length() > 44 }
            val muxSession = when {
                usableInternal != null && usableMic != null -> {
                    container().ffmpeg.muxMixMicAndInternalAac(
                        video.absolutePath,
                        usableInternal.absolutePath,
                        usableMic.absolutePath,
                        muxed.absolutePath,
                    )
                }
                usableInternal != null -> {
                    container().ffmpeg.muxCopyVideoAac(
                        video.absolutePath,
                        usableInternal.absolutePath,
                        muxed.absolutePath,
                    )
                }
                usableMic != null -> {
                    container().ffmpeg.muxCopyVideoAac(
                        video.absolutePath,
                        usableMic.absolutePath,
                        muxed.absolutePath,
                    )
                }
                else -> null
            }
            if (muxSession != null) {
                val muxResult = muxSession.await()
                if (muxResult.success && muxed.exists() && muxed.length() > 1024) {
                    video.delete()
                    finalFile = if (muxed.renameTo(video)) video else muxed
                }
            }
            wav?.delete()
            micWav?.delete()
            if (finalFile != muxed) muxed.delete()

            val uri = Uri.fromFile(finalFile)
            val probed = runCatching { container().probe.probe(uri) }.getOrNull()
            val existing = container().jobs.get(id)
            if (existing != null) {
                container().jobs.upsert(
                    existing.copy(
                        status = JobStatus.READY,
                        sourceUri = uri.toString(),
                        sourceBytes = probed?.bytes ?: finalFile.length(),
                        durationMs = probed?.durationMs ?: existing.durationMs,
                        width = probed?.width ?: existing.width,
                        height = probed?.height ?: existing.height,
                        displayName = probed?.displayName ?: existing.displayName,
                    ),
                )
            }
            container().recording.finish(id)
        } catch (t: Throwable) {
            container().recording.fail(t.message ?: getString(R.string.error_save_recording))
            if (id != null) {
                container().jobs.updateStatus(id, JobStatus.FAILED, error = t.message, finished = true)
            }
        } finally {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun createRecorder(file: File, width: Int, height: Int, audioMode: RecordAudioMode): MediaRecorder {
        val rec = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(this) else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }
        if (audioMode == RecordAudioMode.MICROPHONE) {
            rec.setAudioSource(MediaRecorder.AudioSource.MIC)
        }
        rec.setVideoSource(MediaRecorder.VideoSource.SURFACE)
        rec.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        rec.setOutputFile(file.absolutePath)
        rec.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
        if (audioMode == RecordAudioMode.MICROPHONE) {
            rec.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            rec.setAudioEncodingBitRate(128_000)
            rec.setAudioSamplingRate(44_100)
        }
        rec.setVideoSize(width, height)
        rec.setVideoFrameRate(30)
        rec.setVideoEncodingBitRate((width * height * 4).coerceIn(2_000_000, 16_000_000))
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
        ServiceCompat.startForeground(
            this,
            Notifications.RECORD_ID,
            Notifications.recording(
                context = this,
                elapsed = formatDuration(elapsedMs),
                stopIntent = stop,
                pauseResumeIntent = pauseResume,
                paused = paused,
                saving = saving,
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
        return types
    }

    private fun teardown() {
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
        const val EXTRA_AUDIO_MODE = "audioMode"
        const val EXTRA_RESOLUTION = "resolution"

        fun start(
            context: Context,
            jobId: String,
            resultCode: Int,
            data: Intent,
            audioMode: RecordAudioMode,
            resolution: RecordResolution,
        ) {
            val intent = Intent(context, ScreenRecordService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_JOB_ID, jobId)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_RESULT_DATA, data)
                .putExtra(EXTRA_AUDIO_MODE, audioMode.resolvedForSdk(Build.VERSION.SDK_INT).name)
                .putExtra(EXTRA_RESOLUTION, resolution.name)
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
