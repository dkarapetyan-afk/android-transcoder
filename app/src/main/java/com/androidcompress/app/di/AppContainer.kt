package com.androidcompress.app.di

import android.content.Context
import com.androidcompress.app.capture.RecordingStore
import com.androidcompress.app.data.AppDatabase
import com.androidcompress.app.data.EncoderCapabilities
import com.androidcompress.app.data.HistoryJanitor
import com.androidcompress.app.data.JobRepository
import com.androidcompress.app.data.PreferencesRepository
import com.androidcompress.app.data.SettingsJson
import com.androidcompress.app.asr.CaptionPass
import com.androidcompress.app.asr.SherpaWhisperCaptioner
import com.androidcompress.app.asr.WhisperModelStore
import com.androidcompress.app.agent.AutomationCoordinator
import com.androidcompress.app.encode.BatchQueueSettings
import com.androidcompress.app.encode.BatchRecipe
import com.androidcompress.app.encode.EncodeProgressStore
import com.androidcompress.app.encode.FfmpegGateway
import com.androidcompress.app.encode.FfmpegKitGateway
import com.androidcompress.app.encode.JobLogStore
import com.androidcompress.app.encode.Media3Transcoder
import com.androidcompress.app.encode.MediaCodecEncoderCaps
import com.androidcompress.app.media.AppShortcuts
import com.androidcompress.app.media.InputResolver
import com.androidcompress.app.media.JobImporter
import com.androidcompress.app.media.LatestShortcutOpener
import com.androidcompress.app.media.MediaProbe
import com.androidcompress.app.media.MediaStoreExporter
import com.androidcompress.app.media.SourceFileDeleter
import com.androidcompress.app.util.runCatchingLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class AppContainer(context: Context) {
    val appContext = context.applicationContext
    private val db = AppDatabase.create(appContext)

    val jobs = JobRepository(db.jobDao())
    val prefs = PreferencesRepository(appContext)
    val probe = MediaProbe(appContext)
    val inputs = InputResolver(appContext)
    val exporter = MediaStoreExporter(appContext)
    val sourceDeleter = SourceFileDeleter(appContext)
    val ffmpeg: FfmpegGateway = FfmpegKitGateway()
    val media3 = Media3Transcoder(appContext)
    val whisperModels = WhisperModelStore(appContext)
    val captions = CaptionPass(ffmpeg, whisperModels, SherpaWhisperCaptioner(whisperModels))
    val encodeProgress = EncodeProgressStore()
    val jobLogs = JobLogStore(appContext)
    val recording = RecordingStore()
    val history = HistoryJanitor(jobs, jobLogs, inputs)
    val importer = JobImporter(appContext, jobs, prefs, probe, inputs, history)
    val shortcutOpener = LatestShortcutOpener(appContext, jobs, importer)
    val automation = AutomationCoordinator(appContext)
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        appScope.launch { runCatchingLog(TAG, "prune history") { history.prune() } }
        refreshShortcuts()
    }

    fun refreshShortcuts() {
        appScope.launch {
            val latest = runCatchingLog(TAG, "latest shortcut label") { shortcutOpener.latestLabel() }.getOrNull()
            AppShortcuts.publishDynamic(appContext, latest?.first, latest?.second)
        }
    }

    suspend fun applyBatchRecipe(recipe: BatchRecipe, queuedOnly: Boolean): Int {
        val targets = BatchQueueSettings.targets(jobs.listAll(), queuedOnly)
        for (job in targets) {
            val next = BatchQueueSettings.apply(job, recipe)
            if (next.settingsJson != job.settingsJson) jobs.upsert(next)
        }
        return targets.size
    }

    private val capsMutex = Mutex()
    @Volatile private var caps: EncoderCapabilities? = null

    suspend fun encoderCapabilities(): EncoderCapabilities {
        caps?.let { return it }
        return capsMutex.withLock {
            caps?.let { return it }
            val cached = SettingsJson.decodeCaps(prefs.current().encoderCapsJson)
            if (cached != null) {
                val device = runCatchingLog(TAG, "detect device encoders") { MediaCodecEncoderCaps.detect() }
                    .getOrElse { EncoderCapabilities() }
                val merged = cached.copy(
                    hasH264MediaCodec = cached.hasH264MediaCodec || device.hasH264MediaCodec,
                    hasHevcMediaCodec = cached.hasHevcMediaCodec || device.hasHevcMediaCodec,
                    hasVp8MediaCodec = cached.hasVp8MediaCodec || device.hasVp8MediaCodec,
                    hasVp9MediaCodec = cached.hasVp9MediaCodec || device.hasVp9MediaCodec,
                    hasAv1MediaCodec = cached.hasAv1MediaCodec || device.hasAv1MediaCodec,
                )
                caps = merged
                return merged
            }
            val detected = runCatchingLog(TAG, "detect ffmpeg encoders") { ffmpeg.detectEncoders() }
                .getOrElse { EncoderCapabilities() }
            prefs.setEncoderCapsJson(SettingsJson.encodeCaps(detected))
            val device = runCatchingLog(TAG, "detect device encoders") { MediaCodecEncoderCaps.detect() }
                .getOrElse { EncoderCapabilities() }
            val merged = detected.copy(
                hasH264MediaCodec = detected.hasH264MediaCodec || device.hasH264MediaCodec,
                hasHevcMediaCodec = detected.hasHevcMediaCodec || device.hasHevcMediaCodec,
                hasVp8MediaCodec = detected.hasVp8MediaCodec || device.hasVp8MediaCodec,
                hasVp9MediaCodec = detected.hasVp9MediaCodec || device.hasVp9MediaCodec,
                hasAv1MediaCodec = detected.hasAv1MediaCodec || device.hasAv1MediaCodec,
            )
            caps = merged
            merged
        }
    }

    private companion object {
        const val TAG = "AppContainer"
    }
}
