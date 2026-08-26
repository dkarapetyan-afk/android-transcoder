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
import com.androidcompress.app.asr.CaptionProgressNotifier
import com.androidcompress.app.container
import com.androidcompress.app.data.AudioOption
import com.androidcompress.app.data.ContainerFormat
import com.androidcompress.app.data.EncodeSettings
import com.androidcompress.app.data.JobStatus
import com.androidcompress.app.data.OutputMode
import com.androidcompress.app.data.RecordAudioMode
import com.androidcompress.app.data.RecordResolution
import com.androidcompress.app.encode.FfmpegMuxCommands
import com.androidcompress.app.encode.RecordingCrop
import com.androidcompress.app.encode.quoteArgs
import com.androidcompress.app.util.AppLog
import com.androidcompress.app.util.Notifications
import com.androidcompress.app.util.runCatchingLog
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
    private var mixer: LiveAudioMixer? = null
    private var cropPipe: CropDisplayPipe? = null
    private var outputFile: File? = null
    private var mixedAudioFile: File? = null
    private var micAudioFile: File? = null
    private var liveCropped = false
    private var liveCovered = false
    private var liveGray = false
    private var jobId: String? = null
    private var ticker: Job? = null
    private var sessionJob: Job? = null
    private var stopping = false
    @Volatile private var captionSkip = false
    private var paused = false
    private var encoderStarted = false
    private var usesMicrophone = false
    private var usesCamera = false
    private var options = RecordOptions()
    private var encodeSize: Pair<Int, Int> = 1280 to 720
    private var overlays: RecordOverlayHost? = null
    private var stopNotice: String? = null
    private var regionOverlayWidth = 0
    private var regionOverlayHeight = 0
    private var plannedLiveCrop: RecordingCrop? = null
    private var liveCropError: String? = null
    private var pipeCoverDestPx = 0

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
            ACTION_BOOKMARK -> {
                addBookmark()
                return START_NOT_STICKY
            }
            ACTION_CANCEL_CAPTIONS -> {
                captionSkip = true
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
        regionOverlayWidth = 0
        regionOverlayHeight = 0
        plannedLiveCrop = null
        liveCropError = null
        pipeCoverDestPx = 0

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
        store.setPipEnabled(options.pipControls)
        if (options.captureRegion) {
            store.prepare(id, RecordPhase.REGION, pipEnabled = options.pipControls)
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
            regionOverlayWidth = 0
            regionOverlayHeight = 0
            cont.resume(RecordRegion.FULL)
            return@suspendCancellableCoroutine
        }
        host.showRegion(
            onConfirm = { region, overlayWidth, overlayHeight ->
                regionOverlayWidth = overlayWidth
                regionOverlayHeight = overlayHeight
                if (cont.isActive) cont.resume(region)
            },
            onCancel = { if (cont.isActive) cont.resume(null) },
        )
        cont.invokeOnCancellation { host.hideRegion() }
    }

    private fun beginEncoder(id: String, projection: MediaProjection) {
        val full = captureSize(options.resolution)
        val liveCrop = options.region?.liveEncoderCrop(full.first, full.second)
        plannedLiveCrop = liveCrop
        liveCropError = null
        val sourceCoverPx = if (options.coverStatusBar) {
            StatusBarCover.sourcePixels(this, full.second)
        } else {
            0
        }
        val pipeCrop = when {
            liveCrop != null -> liveCrop
            sourceCoverPx > 0 || options.grayscale -> RecordingCrop(0, 0, full.first, full.second)
            else -> null
        }
        val coverDestPx = StatusBarCover.destPixels(sourceCoverPx, pipeCrop)
        pipeCoverDestPx = coverDestPx
        val encW = liveCrop?.width ?: full.first
        val encH = liveCrop?.height ?: full.second
        encodeSize = full
        liveCropped = false
        liveCovered = false
        liveGray = false
        val file = container().inputs.recordOutputFile(id, options.outputExtension)
        outputFile = file
        try {
            val rec = prepareRecorder(file, encW, encH, options)
            recorder = rec
            var displaySurface = rec.surface
            if (pipeCrop != null) {
                val pipeResult = runCatchingLog(TAG, "crop display pipe") {
                    CropDisplayPipe.start(
                        rec.surface,
                        full.first,
                        full.second,
                        pipeCrop,
                        coverDestPx,
                        grayscale = options.grayscale,
                    )
                }
                val pipe = pipeResult.getOrNull()
                liveCropError = pipeResult.exceptionOrNull()?.message
                if (pipe != null) {
                    cropPipe = pipe
                    displaySurface = pipe.inputSurface
                    liveCropped = liveCrop != null
                    liveCovered = coverDestPx > 0
                    liveGray = options.grayscale
                    if (liveCrop != null) encodeSize = encW to encH
                } else {
                    runCatchingLog(TAG, "reset recorder") { rec.reset() }
                    runCatchingLog(TAG, "release recorder") { rec.release() }
                    val fullRec = prepareRecorder(file, full.first, full.second, options)
                    recorder = fullRec
                    displaySurface = fullRec.surface
                    liveCropped = false
                    liveCovered = false
                    liveGray = false
                    encodeSize = full
                }
            }
            val activeRec = recorder ?: error(getString(R.string.error_start_recorder))
            virtualDisplay = projection.createVirtualDisplay(
                "RecordingCompressor",
                full.first,
                full.second,
                densityDpi(),
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                displaySurface,
                null,
                handler,
            )
            if (options.audioMode != RecordAudioMode.NONE) {
                val wav = container().inputs.recordAudioFile(id)
                mixedAudioFile = wav
                val isolate = options.isolateAudioTracks && options.audioMode == RecordAudioMode.BOTH
                val micWav = if (isolate) container().inputs.recordMicAudioFile(id) else null
                micAudioFile = micWav
                val uid = CaptureApps.uid(packageManager, options.internalAudioPackage)
                mixer = LiveAudioMixer.start(
                    context = this,
                    projection = projection,
                    output = wav,
                    options = options,
                    appUid = uid,
                    micOutput = micWav,
                ).also { it.start() }
            }
            activeRec.start()
            encoderStarted = true
            container().recording.setPipEnabled(options.pipControls)
            container().recording.startCapturing()
            if (container().recording.state.value.jobId == null) {
                container().recording.start(id, pipEnabled = options.pipControls)
            }
            scope.launch { container().jobs.updateStatus(id, JobStatus.RECORDING) }
            if (options.showBubble) {
                overlays?.showBubble(
                    paused = false,
                    onPauseResume = {
                        if (paused) resumeRecording() else pauseRecording()
                    },
                    onStop = { requestStop() },
                    onBookmark = if (options.bookmarkMode != BookmarkMode.OFF) {
                        { addBookmark() }
                    } else {
                        null
                    },
                )
            }
            if (options.facecam && hasCameraPermission()) {
                usesCamera = true
                startAsForeground(0)
                overlays?.showFacecam(options)
            }
            TapHighlightService.setRecording(
                active = true,
                showTaps = options.showTaps,
                showLaser = options.showLaser,
                showAnnotation = options.showAnnotation,
            )
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
            AppLog.e(TAG, "start capture", t)
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
            mixer?.pause()
            cropPipe?.setPaused(true)
            paused = true
            container().recording.setPaused(true)
            overlays?.updateBubble(true)
            if (options.facecamHideOnPause) overlays?.setFacecamVisible(false)
            startAsForeground(container().recording.state.value.elapsedMs(), paused = true)
            RecordTileService.requestListening(this)
        } catch (t: Throwable) {
            AppLog.e(TAG, "pause recording", t)
            runCatchingLog(TAG, "resume after pause fail") { recorder?.resume() }
            mixer?.resume()
            cropPipe?.setPaused(false)
            paused = false
        }
    }

    private fun resumeRecording() {
        if (stopping || !paused || recorder == null) return
        try {
            recorder?.resume()
            mixer?.resume()
            cropPipe?.setPaused(false)
            paused = false
            container().recording.setPaused(false)
            overlays?.updateBubble(false)
            if (options.facecam) overlays?.setFacecamVisible(true)
            startAsForeground(container().recording.state.value.elapsedMs(), paused = false)
            RecordTileService.requestListening(this)
        } catch (t: Throwable) {
            AppLog.e(TAG, "resume recording", t)
        }
    }

    private fun addBookmark() {
        if (stopping || !encoderStarted) return
        container().recording.addBookmark()
        startAsForeground(container().recording.state.value.elapsedMs())
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
        TapHighlightService.setRecording(false, false, false, false)
        val id = jobId
        val video = outputFile
        val wav = mixedAudioFile
        val micWav = micAudioFile
        val bookmarks = container().recording.state.value.bookmarks
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
        var didLiveCrop = liveCropped
        var didLiveCover = liveCovered
        var didLiveGray = liveGray
        try {
            didLiveCrop = liveCropped
            didLiveCover = liveCovered
            didLiveGray = liveGray
            stopEncoderPipeline()
            mediaProjection?.unregisterCallback(projectionCallback)
            mediaProjection?.stop()
            mediaProjection = null

            if (id == null || video == null || !video.exists() || video.length() < 1024) {
                val noVideo = getString(R.string.error_recording_no_video)
                if (id != null) {
                    writeRecordLog(
                        id = id,
                        liveApplied = didLiveCrop,
                        softwareCrop = null,
                        coverDestPx = 0,
                        extra = "error=$noVideo",
                    )
                    container().jobs.updateStatus(id, JobStatus.FAILED, error = noVideo, finished = true)
                }
                container().recording.fail(noVideo)
                return
            }

            var finalFile: File = video
            val processed = File(
                video.parentFile,
                "${video.nameWithoutExtension}-muxed.${options.outputExtension}",
            )
            val usableMix = wav.takeIf { it != null && it.exists() && it.length() > 44 }
            val usableMic = micWav.takeIf { it != null && it.exists() && it.length() > 44 }
            val isolate = options.isolateAudioTracks && usableMix != null && usableMic != null
            val crop = if (didLiveCrop) {
                null
            } else {
                options.region?.encoderCrop(encodeSize.first, encodeSize.second)
            }
            val coverTopPx = if (didLiveCover || !options.coverStatusBar) {
                0
            } else {
                StatusBarCover.destPixels(
                    StatusBarCover.sourcePixels(this, encodeSize.second),
                    crop,
                )
            }
            val softwareGray = options.grayscale && !didLiveGray
            val caps = runCatchingLog(TAG, "encoder caps") { container().encoderCapabilities() }.getOrNull()
            val cropEncoder = when {
                options.usesWebm && caps?.hasLibvpxVp9 == true -> "libvpx-vp9"
                options.usesWebm -> "libvpx"
                caps?.hasOpenH264 == true -> "libopenh264"
                else -> "mpeg4"
            }
            val cropBitrate = ((crop?.width ?: encodeSize.first) * (crop?.height ?: encodeSize.second) * 4 / 1000)
                .coerceIn(800, 12_000)
            val post = FfmpegMuxCommands.recordingPostProcess(
                videoPath = video.absolutePath,
                outputPath = processed.absolutePath,
                internalWav = usableMix?.absolutePath,
                micWav = if (isolate) usableMic.absolutePath else null,
                crop = crop,
                coverTopPx = coverTopPx,
                videoHasAudio = false,
                internalGainPercent = 100,
                micGainPercent = 100,
                duckAppAudio = false,
                videoEncoder = cropEncoder,
                videoBitrateKbps = cropBitrate,
                frameRate = options.frameRate,
                containerWebm = options.usesWebm,
                applyGain = false,
                isolateTracks = isolate,
                grayscale = softwareGray,
            )
            var muxSuccess: Boolean? = null
            if (post != null) {
                val muxResult = container().ffmpeg.encode(post, onLog = {}, onStats = {}).await()
                muxSuccess = muxResult.success
                if (muxResult.success && processed.exists() && processed.length() > 1024) {
                    video.delete()
                    finalFile = if (processed.renameTo(video)) video else processed
                }
            }
            wav?.delete()
            micWav?.delete()
            if (finalFile != processed) processed.delete()
            finalFile = applyChaptersIfNeeded(finalFile, bookmarks)
            val captioned = applyCaptionsIfNeeded(finalFile)
            if (captioned != null) finalFile = captioned.first
            val captionNote = captioned?.second
            val splitFiles = splitIfNeeded(finalFile, bookmarks)

            val uri = Uri.fromFile(finalFile)
            val probed = runCatchingLog(TAG, "probe recording") { container().probe.probe(uri) }.getOrNull()
            writeRecordLog(
                id = id,
                liveApplied = didLiveCrop,
                softwareCrop = crop,
                coverDestPx = if (didLiveCover) pipeCoverDestPx else coverTopPx,
                outputWidth = probed?.width,
                outputHeight = probed?.height,
                ffmpegCommand = post?.let(::quoteArgs),
                muxSuccess = muxSuccess,
                liveGray = didLiveGray,
                softwareGray = softwareGray && post != null,
                extra = captionNote,
            )
            val existing = container().jobs.get(id)
            val direct = options.directEncode
            var outputUri: String? = null
            var outputBytes: Long? = null
            var status = JobStatus.READY
            if (direct) {
                val published = runCatchingLog(TAG, "publish recording") {
                    container().exporter.publish(
                        finalFile,
                        directDisplayName(),
                        options.outputMime,
                        "Movies/RecordingCompressor",
                    )
                }.getOrNull()
                if (published != null) {
                    outputUri = published.toString()
                    outputBytes = probed?.bytes ?: finalFile.length()
                    status = JobStatus.SUCCEEDED
                    runCatchingLog(TAG, "delete cache file") { finalFile.delete() }
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
                publishSplitJobs(existing, splitFiles, status == JobStatus.SUCCEEDED)
            }
            container().recording.finish(
                jobId = id,
                openResult = status == JobStatus.SUCCEEDED,
                notice = stopNotice,
            )
        } catch (t: CancellationException) {
            throw t
        } catch (t: Throwable) {
            AppLog.e(TAG, "finish recording", t)
            if (id != null) {
                writeRecordLog(
                    id = id,
                    liveApplied = didLiveCrop,
                    softwareCrop = null,
                    coverDestPx = if (didLiveCover) pipeCoverDestPx else 0,
                    extra = "exception=${t.message}",
                )
                container().jobs.updateStatus(id, JobStatus.FAILED, error = t.message, finished = true)
            }
            container().recording.fail(t.message ?: getString(R.string.error_save_recording))
        } finally {
            Notifications.clearCaptions(this)
            RecordTileService.requestListening(this)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private suspend fun applyChaptersIfNeeded(file: File, bookmarks: List<Long>): File {
        if (options.bookmarkMode != BookmarkMode.CHAPTERS) return file
        val duration = runCatchingLog(TAG, "probe duration") {
            container().probe.probe(Uri.fromFile(file)).durationMs
        }.getOrNull()
            ?: container().recording.state.value.elapsedMs()
        val meta = RecordBookmarks.ffmetadata(bookmarks, duration) ?: return file
        val metaFile = File(file.parentFile, "${file.nameWithoutExtension}.ffmeta")
        val out = File(file.parentFile, "${file.nameWithoutExtension}-chapters.${options.outputExtension}")
        var resultFile = file
        try {
            metaFile.writeText(meta)
            val args = FfmpegMuxCommands.applyChapters(
                videoPath = file.absolutePath,
                metadataPath = metaFile.absolutePath,
                outputPath = out.absolutePath,
                containerWebm = options.usesWebm,
            )
            val result = container().ffmpeg.encode(args, onLog = {}, onStats = {}).await()
            if (result.success && out.exists() && out.length() > 1024) {
                file.delete()
                resultFile = if (out.renameTo(file)) file else out
            }
        } catch (t: Throwable) {
            AppLog.e(TAG, "apply chapters", t)
            resultFile = file
        } finally {
            metaFile.delete()
            if (out.exists() && out != resultFile) out.delete()
        }
        return resultFile
    }

    private suspend fun applyCaptionsIfNeeded(file: File): Pair<File, String>? {
        if (!options.directEncode || !options.captions || options.audioMode == RecordAudioMode.NONE) {
            return null
        }
        val settings = EncodeSettings(
            captions = true,
            audio = AudioOption.AAC_128,
            output = OutputMode.VIDEO,
            container = if (options.usesWebm) ContainerFormat.WEBM else ContainerFormat.MP4,
        )
        val workDir = file.parentFile ?: return null
        captionSkip = false
        val notice = captionNotifier()
        return try {
            val cap = container().captions.apply(
                media = file,
                settings = settings,
                workDir = workDir,
                stem = "${file.nameWithoutExtension}-cap",
                onProgress = { fraction, message -> notice.update(fraction, message) },
                isCancelled = { captionSkip },
            )
            cap.srt?.let { srt ->
                container().exporter.publishSidecar(
                    srt,
                    directDisplayName().substringBeforeLast('.'),
                    "Movies/RecordingCompressor",
                )
                srt.delete()
            }
            cap.media to "captions cues=${cap.cueCount} muxed=${cap.muxed}"
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            AppLog.e(TAG, "captions", t)
            if (captionSkip || t.message == "cancelled") {
                file to "captions cancelled"
            } else {
                file to "captions failed: ${t.message}"
            }
        } finally {
            notice.clear()
        }
    }

    private fun captionNotifier(): CaptionProgressNotifier {
        val cancel = PendingIntent.getService(
            this,
            4,
            Intent(this, ScreenRecordService::class.java).setAction(ACTION_CANCEL_CAPTIONS),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return CaptionProgressNotifier(this, cancel)
    }

    private suspend fun splitIfNeeded(
        file: File,
        bookmarks: List<Long>,
    ): List<Pair<RecordSegment, File>> {
        if (options.bookmarkMode != BookmarkMode.SPLIT) return emptyList()
        val duration = runCatchingLog(TAG, "probe duration") {
            container().probe.probe(Uri.fromFile(file)).durationMs
        }.getOrNull()
            ?: container().recording.state.value.elapsedMs()
        val segments = RecordBookmarks.segments(bookmarks, duration)
        if (segments.isEmpty()) return emptyList()
        val out = mutableListOf<Pair<RecordSegment, File>>()
        for (seg in segments) {
            val dest = File(
                file.parentFile,
                "${file.nameWithoutExtension}-p${seg.index}.${options.outputExtension}",
            )
            val args = FfmpegMuxCommands.copySegment(
                videoPath = file.absolutePath,
                outputPath = dest.absolutePath,
                startMs = seg.startMs,
                endMs = seg.endMs,
            )
            val result = runCatchingLog(TAG, "split segment") {
                container().ffmpeg.encode(args, onLog = {}, onStats = {}).await()
            }.getOrNull()
            if (result?.success == true && dest.exists() && dest.length() > 512) {
                out += seg to dest
            } else {
                dest.delete()
            }
        }
        return out
    }

    private suspend fun publishSplitJobs(
        original: com.androidcompress.app.data.CompressJob,
        parts: List<Pair<RecordSegment, File>>,
        directSuccess: Boolean,
    ) {
        if (parts.isEmpty()) return
        for ((seg, file) in parts) {
            val partId = java.util.UUID.randomUUID().toString()
            val probed = runCatchingLog(TAG, "probe split") { container().probe.probe(Uri.fromFile(file)) }.getOrNull()
            var outputUri: String? = null
            var outputBytes: Long? = null
            var status = JobStatus.READY
            var sourceUri = Uri.fromFile(file).toString()
            if (directSuccess && options.directEncode) {
                val published = runCatchingLog(TAG, "publish split") {
                    container().exporter.publish(
                        file,
                        "${directDisplayName().substringBeforeLast('.') } ${seg.index}-${seg.total}.${options.outputExtension}",
                        options.outputMime,
                        "Movies/RecordingCompressor",
                    )
                }.getOrNull()
                if (published != null) {
                    outputUri = published.toString()
                    outputBytes = probed?.bytes ?: file.length()
                    status = JobStatus.SUCCEEDED
                    sourceUri = outputUri
                    runCatchingLog(TAG, "delete split cache") { file.delete() }
                }
            }
            container().jobs.upsert(
                original.copy(
                    id = partId,
                    status = status,
                    sourceUri = sourceUri,
                    outputUri = outputUri,
                    outputBytes = outputBytes,
                    sourceBytes = probed?.bytes ?: file.length(),
                    durationMs = probed?.durationMs ?: seg.durationMs,
                    width = probed?.width ?: original.width,
                    height = probed?.height ?: original.height,
                    displayName = getString(
                        R.string.record_split_name,
                        seg.index,
                        seg.total,
                    ),
                    createdAt = original.createdAt + seg.index,
                    finishedAt = if (status == JobStatus.SUCCEEDED) System.currentTimeMillis() else null,
                    queuedAt = null,
                    error = null,
                ),
            )
        }
    }

    private fun prepareRecorder(
        file: File,
        width: Int,
        height: Int,
        options: RecordOptions,
    ): MediaRecorder {
        val codecs = if (options.usesWebm) {
            listOf(null)
        } else if (options.directEncode) {
            listOf(options.videoCodec, RecordVideoCodec.HEVC, RecordVideoCodec.H264).distinct()
        } else {
            listOf(RecordVideoCodec.H264)
        }
        val rates = listOf(options.frameRate, 30).distinct()
        var last: Throwable? = null
        for (rate in rates) {
            for (codec in codecs) {
                val rec = createRecorder(
                    file,
                    width,
                    height,
                    options.copy(frameRate = rate, videoCodec = codec ?: options.videoCodec),
                    webm = options.usesWebm,
                )
                try {
                    rec.prepare()
                    return rec
                } catch (t: Throwable) {
                    last = t
                    runCatchingLog(TAG, "reset recorder") { rec.reset() }
                    runCatchingLog(TAG, "release recorder") { rec.release() }
                }
            }
        }
        throw last ?: IllegalStateException(getString(R.string.error_start_recorder))
    }

    private fun createRecorder(
        file: File,
        width: Int,
        height: Int,
        options: RecordOptions,
        webm: Boolean,
    ): MediaRecorder {
        val rec = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(this) else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }
        rec.setVideoSource(MediaRecorder.VideoSource.SURFACE)
        rec.setOutputFormat(
            if (webm) MediaRecorder.OutputFormat.WEBM else MediaRecorder.OutputFormat.MPEG_4,
        )
        rec.setOutputFile(file.absolutePath)
        if (webm) {
            rec.setVideoEncoder(MediaRecorder.VideoEncoder.VP8)
        } else {
            val codec = if (options.directEncode) options.videoCodec else RecordVideoCodec.H264
            rec.setVideoEncoder(codec.mediaRecorderValue())
        }
        rec.setVideoSize(width, height)
        rec.setVideoFrameRate(options.frameRate.coerceIn(24, 60))
        val codecForBitrate = if (webm) RecordVideoCodec.H264 else options.videoCodec
        rec.setVideoEncodingBitRate(
            codecForBitrate.videoBitrate(
                width,
                height,
                options.directEncode || webm,
                options.videoBitrateKbps,
                options.frameRate,
            ),
        )
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
        val bookmark = if (options.bookmarkMode != BookmarkMode.OFF) {
            PendingIntent.getService(
                this,
                3,
                Intent(this, ScreenRecordService::class.java).setAction(ACTION_BOOKMARK),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        } else {
            null
        }
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
                bookmarkIntent = bookmark,
                paused = paused,
                saving = saving,
                preparing = store.phase == RecordPhase.REGION || store.phase == RecordPhase.COUNTDOWN,
                quiet = options.quietNotification,
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

    private fun writeRecordLog(
        id: String,
        liveApplied: Boolean,
        softwareCrop: RecordingCrop?,
        coverDestPx: Int,
        outputWidth: Int? = null,
        outputHeight: Int? = null,
        ffmpegCommand: String? = null,
        muxSuccess: Boolean? = null,
        extra: String? = null,
        liveGray: Boolean = false,
        softwareGray: Boolean = false,
    ) {
        val capture = captureSize(options.resolution)
        val text = buildString {
            appendLine("jobId=$id")
            appendLine(
                RecordCaptureLog.build(
                    captureWidth = capture.first,
                    captureHeight = capture.second,
                    encodeWidth = encodeSize.first,
                    encodeHeight = encodeSize.second,
                    overlayWidth = regionOverlayWidth,
                    overlayHeight = regionOverlayHeight,
                    region = options.region,
                    liveCrop = plannedLiveCrop,
                    softwareCrop = softwareCrop,
                    liveApplied = liveApplied,
                    liveError = liveCropError,
                    coverDestPx = coverDestPx,
                    grayscale = options.grayscale,
                    liveGray = liveGray,
                    softwareGray = softwareGray,
                    outputWidth = outputWidth,
                    outputHeight = outputHeight,
                    ffmpegCommand = ffmpegCommand,
                    muxSuccess = muxSuccess,
                ),
            )
            extra?.takeIf { it.isNotBlank() }?.let { appendLine(it) }
        }
        runCatchingLog(TAG, "write record log") { container().jobLogs.write(id, text) }
    }

    /**
     * VirtualDisplay off → destroy the GL window on the encoder surface (EOS)
     * → then MediaRecorder.stop(). Stopping the recorder while EGL still owns
     * the surface blocks for about 10s and pads the file with a frozen frame.
     */
    private fun stopEncoderPipeline() {
        virtualDisplay?.release()
        virtualDisplay = null
        mixer?.stop()
        mixer = null
        cropPipe?.release()
        cropPipe = null
        liveCropped = false
        liveCovered = false
        liveGray = false
        runCatchingLog(TAG, "stop recorder") { recorder?.stop() }
        runCatchingLog(TAG, "reset recorder") { recorder?.reset() }
        runCatchingLog(TAG, "release recorder") { recorder?.release() }
        recorder = null
    }

    private fun teardown() {
        overlays?.dismissAll()
        TapHighlightService.setRecording(false, false, false, false)
        virtualDisplay?.release()
        virtualDisplay = null
        mixer?.stop()
        mixer = null
        cropPipe?.release()
        cropPipe = null
        liveCropped = false
        liveCovered = false
        liveGray = false
        runCatchingLog(TAG, "reset recorder") { recorder?.reset() }
        runCatchingLog(TAG, "release recorder") { recorder?.release() }
        recorder = null
        mediaProjection?.unregisterCallback(projectionCallback)
        mediaProjection?.stop()
        mediaProjection = null
    }

    private fun abortDraft(id: String) {
        scope.launch { runCatchingLog(TAG, "delete draft job") { container().jobs.delete(id) } }
    }

    private fun hasCameraPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED

    private fun availableBytes(): Long {
        val dir = outputFile?.parentFile ?: cacheDir
        return runCatchingLog(TAG, "usable space") { dir.usableSpace }.getOrDefault(Long.MAX_VALUE)
    }

    private fun directDisplayName(): String {
        val stamp = SimpleDateFormat("yyyy-MM-dd HHmmss", Locale.US).format(Date())
        return "Screen recording $stamp.${options.outputExtension}"
    }

    override fun onDestroy() {
        captionSkip = true
        Notifications.clearCaptions(this)
        teardown()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "ScreenRecord"
        const val ACTION_START = "com.androidcompress.app.RECORD_START"
        const val ACTION_STOP = "com.androidcompress.app.RECORD_STOP"
        const val ACTION_PAUSE = "com.androidcompress.app.RECORD_PAUSE"
        const val ACTION_RESUME = "com.androidcompress.app.RECORD_RESUME"
        const val ACTION_BOOKMARK = "com.androidcompress.app.RECORD_BOOKMARK"
        const val ACTION_CANCEL_CAPTIONS = "com.androidcompress.app.RECORD_CANCEL_CAPTIONS"
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

        fun bookmark(context: Context) {
            context.startService(Intent(context, ScreenRecordService::class.java).setAction(ACTION_BOOKMARK))
        }

        fun cancelCaptions(context: Context) {
            context.startService(Intent(context, ScreenRecordService::class.java).setAction(ACTION_CANCEL_CAPTIONS))
        }
    }
}
