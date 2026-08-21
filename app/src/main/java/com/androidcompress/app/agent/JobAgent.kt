package com.androidcompress.app.agent

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.androidcompress.app.container
import com.androidcompress.app.data.AudioOption
import com.androidcompress.app.data.CompressJob
import com.androidcompress.app.data.EncodeEngine
import com.androidcompress.app.data.EncodeSettings
import com.androidcompress.app.data.JobStatus
import com.androidcompress.app.data.JobType
import com.androidcompress.app.data.Preset
import com.androidcompress.app.data.SettingsJson
import com.androidcompress.app.data.audioOutput
import com.androidcompress.app.data.effectiveAudio
import com.androidcompress.app.data.galleryFolder
import com.androidcompress.app.data.outputMime
import com.androidcompress.app.data.usesWebm
import com.androidcompress.app.di.AppContainer
import com.androidcompress.app.encode.BatchRecipe
import com.androidcompress.app.encode.CompressService
import com.androidcompress.app.encode.EncodeQueue
import com.androidcompress.app.encode.FfmpegCommandBuilder
import com.androidcompress.app.encode.FfmpegCommandTemplate
import com.androidcompress.app.encode.Media3EncodePlanner
import com.androidcompress.app.media.DeviceMediaQueries
import com.androidcompress.app.media.DeviceMediaStore
import com.androidcompress.app.media.InputResolver
import com.androidcompress.app.media.MediaLibraryAccess
import kotlinx.coroutines.delay
import java.io.File
import java.util.UUID

class JobAgent(
    private val context: Context,
    private val container: AppContainer = context.container(),
) {
    fun describeCapabilities(): AppCapabilities = AppCapabilities(
        summary = "Recording Compressor encodes videos and audio on this device with FFmpeg or Media3. " +
            "It can also combine a picture or video with a separate soundtrack. " +
            "Fit-to-size (targetSizeBytes) derives video bitrate from duration so the file stays under " +
            "Discord 10 MB, WhatsApp 16/64 MB, or Gmail 25 MB.",
        workflow = "1) If libraryAccessGranted is false, call requestLibraryAccess and ask the user to Allow all. " +
            "2) For one file, call compressNow(uriOrPath, settings, wait=true). " +
            "3) For several files, listDeviceMedia with relativePath or date filters, then importDeviceMediaBatch. " +
            "applyToQueue(preset, container) sets the same encode options on every waiting job (e.g. SMALLER + WEBM). " +
            "4) Use cloneJob for a second encode of the same source, or retryJob after a failure. " +
            "5) waitForJob or waitForQueue (max 180s; call again if timedOut). " +
            "6) shareOutput or openOutput when done. discardJob removes history only.",
        presets = JobSettingsCodec.presets,
        targetSizePresets = JobSettingsCodec.targetSizePresets,
        engines = JobSettingsCodec.engines,
        outputs = JobSettingsCodec.outputs,
        containers = JobSettingsCodec.containers,
        codecs = JobSettingsCodec.codecs,
        audioOptions = JobSettingsCodec.audioOptions,
        bitrateModes = JobSettingsCodec.bitrateModes,
        keyframeIntervals = JobSettingsCodec.keyframeIntervals,
        h264Profiles = JobSettingsCodec.h264Profiles,
        hdrModes = JobSettingsCodec.hdrModes,
        bFrameSettings = JobSettingsCodec.bFrameSettings,
        jobStatuses = JobSettingsCodec.jobStatuses,
        libraryAccessGranted = MediaLibraryAccess.granted(context),
        libraryAccessNote = "Grant videos, audio, and photos in Settings and choose Allow all. " +
            "requestLibraryAccess opens that screen. Then listDeviceMedia, importDeviceMedia, " +
            "importDeviceMediaBatch, and compressNow can open files already on the device. " +
            "Those tools accept a MediaStore content URI, an absolute path, or an exact display name.",
        recommendedTools = listOf(
            "describeCapabilities",
            "requestLibraryAccess",
            "compressNow",
            "waitForJob",
            "listDeviceMedia",
            "importDeviceMediaBatch",
            "applyToQueue",
            "cloneJob",
            "retryJob",
            "getProgress",
            "getEncodeLog",
            "getEncoderCapabilities",
            "getSourceInfo",
            "shareOutput",
            "discardJob",
        ),
        restrictions = "This API does not record the screen, delete gallery files, clear all history, or send media off the device. " +
            "discardJob removes a history row and cache only. shareOutput and openOutput use the system sheet or viewer. " +
            "Job records omit source and output URIs. Extra FFmpeg args cannot add inputs or paths. " +
            "waitForJob blocks at most 180 seconds.",
    )

    fun listPresets(): List<PresetInfo> = Preset.entries.map { preset ->
        val settings = EncodeSettings.forPreset(preset)
        PresetInfo(
            name = preset.name,
            description = when (preset) {
                Preset.SMALLER -> "720p, 1500 kbps, smaller file."
                Preset.BALANCED -> "1080p, 2500 kbps, default."
                Preset.HIGHER -> "1440p, 6000 kbps, higher quality."
            },
            settings = JobSettingsCodec.snapshot(settings),
        )
    }

    suspend fun listJobs(status: String?, limit: Int): JobListResult {
        val cap = limit.coerceIn(1, 40)
        val filter = status?.takeIf { it.isNotBlank() && !it.equals("ALL", ignoreCase = true) }
            ?.let { JobSettingsCodec.requireStatus(it) }
        val all = container.jobs.listAll()
        val matched = if (filter == null) all else all.filter { it.status == filter }
        return JobListResult(
            jobs = matched.take(cap).map { toSummary(it, all) },
            totalMatched = matched.size,
            statusFilter = filter?.name ?: "ALL",
        )
    }

    suspend fun getJob(jobId: String): JobDetail = toDetail(requireJob(jobId), container.jobs.listAll())

    suspend fun getQueue(): QueueSnapshot {
        val all = container.jobs.listAll()
        return QueueSnapshot(
            active = EncodeQueue.active(all).map { toSummary(it, all) },
            readyCount = all.count { it.status == JobStatus.READY },
            runningCount = all.count { it.status == JobStatus.RUNNING },
            queuedCount = all.count { it.status == JobStatus.QUEUED },
        )
    }

    suspend fun getProgress(jobId: String?): ProgressSnapshot {
        val all = container.jobs.listAll()
        val live = container.encodeProgress.progress.value
        val job = when {
            !jobId.isNullOrBlank() -> requireJob(jobId)
            live != null -> all.firstOrNull { it.id == live.jobId } ?: EncodeQueue.active(all).firstOrNull()
            else -> EncodeQueue.active(all).firstOrNull()
        }
        if (job == null) {
            return ProgressSnapshot(
                jobId = "",
                status = "NONE",
                displayName = "",
                fraction = 0f,
                percent = 0,
                timeMs = 0L,
                message = "No encode is running.",
                queuePosition = 0,
                queueSize = EncodeQueue.active(all).size,
                error = "",
            )
        }
        val (position, size) = EncodeQueue.position(all, job.id)
        val matchesLive = live?.jobId == job.id
        val fraction = if (matchesLive) live.fraction.coerceIn(0f, 1f) else 0f
        return ProgressSnapshot(
            jobId = job.id,
            status = job.status.name,
            displayName = job.displayName,
            fraction = fraction,
            percent = (fraction * 100f).toInt().coerceIn(0, 100),
            timeMs = if (matchesLive) live.timeMs else 0L,
            message = if (matchesLive) live.message.orEmpty() else job.status.name,
            queuePosition = position,
            queueSize = size,
            error = job.error.orEmpty(),
        )
    }

    suspend fun getEncodeLog(jobId: String?, maxChars: Int): EncodeLogSnapshot {
        val id = jobId?.takeIf { it.isNotBlank() } ?: container.jobLogs.lastJobId().orEmpty()
        if (id.isBlank()) {
            return EncodeLogSnapshot(jobId = "", found = false, text = "", returnedChars = 0)
        }
        val raw = container.jobLogs.read(id).orEmpty()
        val cap = maxChars.coerceIn(256, 16_000)
        val text = if (raw.length > cap) raw.takeLast(cap) else raw
        return EncodeLogSnapshot(
            jobId = id,
            found = raw.isNotBlank(),
            text = text,
            returnedChars = text.length,
        )
    }

    suspend fun applyPreset(jobId: String, presetName: String, engineName: String?): JobDetail {
        val job = requireJob(jobId)
        JobSettingsCodec.requireEditable(job)
        val preset = JobSettingsCodec.requirePreset(presetName)
        val engine = engineName?.let { JobSettingsCodec.requireEngine(it) }
            ?: SettingsJson.decode(job.settingsJson).engine
        return saveSettings(job, EncodeSettings.forPreset(preset, engine))
    }

    suspend fun updateJobSettings(jobId: String, update: JobSettingsUpdate): JobDetail {
        val job = requireJob(jobId)
        JobSettingsCodec.requireEditable(job)
        val current = SettingsJson.decode(job.settingsJson)
        val next = JobSettingsCodec.apply(current, JobSettingsCodec.patchFromUpdate(update))
        return saveSettings(job, next)
    }

    suspend fun previewEncode(jobId: String): EncodePreview {
        val job = requireJob(jobId)
        val settings = SettingsJson.decode(job.settingsJson)
        val source = JobSettingsCodec.toSource(job)
        val caps = container.encoderCapabilities()
        val ffmpegPlan = if (settings.engine == EncodeEngine.FFMPEG) {
            FfmpegCommandBuilder.build(
                FfmpegCommandTemplate.INPUT,
                FfmpegCommandTemplate.OUTPUT,
                settings,
                source,
                caps,
                audioInput = if (source.isCombine) FfmpegCommandTemplate.AUDIO else null,
            )
        } else {
            null
        }
        val generated = ffmpegPlan?.let { FfmpegCommandTemplate.fromArgs(it.args) }.orEmpty()
        val override = settings.ffmpegCommandOverride
        val customized = override.isNotBlank() && override.trim() != generated.trim()
        val command = if (customized) override else generated
        val encoderLabel = when {
            settings.engine == EncodeEngine.MEDIA3 -> Media3EncodePlanner.plan(settings, source).encoderLabel
            settings.audioOutput(source.hasVideo) -> when {
                settings.effectiveAudio(source.hasVideo) == AudioOption.COPY -> "FFmpeg · audio copy"
                settings.usesWebm() -> "FFmpeg · Opus"
                else -> "FFmpeg · AAC"
            }
            else -> ffmpegPlan?.videoEncoder.orEmpty()
        }
        val estimateSource = if (
            settings.engine == EncodeEngine.MEDIA3 ||
            settings.audioOutput(source.hasVideo) ||
            source.isCombine
        ) {
            source.copy(durationMs = Media3EncodePlanner.outputDurationMs(settings, source))
        } else {
            source
        }
        val notes = buildString {
            if (settings.engine == EncodeEngine.MEDIA3 && settings.ffmpegExtraArgs.isNotBlank()) {
                append("Extra FFmpeg args are ignored on the Media3 engine. ")
            }
            if (settings.engine == EncodeEngine.MEDIA3 && settings.ffmpegCommandOverride.isNotBlank()) {
                append("The FFmpeg command override is ignored on the Media3 engine. ")
            }
            if (ffmpegPlan?.firstPassArgs != null) {
                append("FFmpeg will run a 2-pass VBR encode.")
            } else if (settings.twoPass && settings.engine == EncodeEngine.FFMPEG) {
                append("Two-pass is on, but this encoder stays one pass (libopenh264 and hardware encoders).")
            }
        }.trim()
        return EncodePreview(
            jobId = job.id,
            encoderLabel = encoderLabel,
            command = command,
            commandCustomized = customized,
            estimateBytes = FfmpegCommandBuilder.estimateOutputBytes(estimateSource, settings),
            settings = JobSettingsCodec.snapshot(settings),
            notes = notes,
        )
    }

    suspend fun startJob(
        jobId: String,
        update: JobSettingsUpdate?,
        deleteSourceAfter: Boolean?,
    ): JobActionResult {
        var job = requireJob(jobId)
        if (update != null) {
            JobSettingsCodec.requireEditable(job)
            val next = JobSettingsCodec.apply(
                SettingsJson.decode(job.settingsJson),
                JobSettingsCodec.patchFromUpdate(update),
            )
            job = persistSettings(job, next)
        }
        JobSettingsCodec.requireStartable(job)
        val settings = SettingsJson.decode(job.settingsJson)
        val sanitized = settings.copy(
            ffmpegExtraArgs = JobSettingsCodec.sanitizeExtraArgs(settings.ffmpegExtraArgs),
            ffmpegCommandOverride = JobSettingsCodec.sanitizeCommandOverride(settings.ffmpegCommandOverride),
        )
        val delete = deleteSourceAfter ?: job.deleteSourceAfter
        val json = SettingsJson.encode(sanitized)
        container.jobs.upsert(job.copy(settingsJson = json, deleteSourceAfter = delete))
        container.jobs.enqueue(job.id, json)
        val serviceNote = startEncodeService(job.id)
        val started = requireJob(job.id)
        return JobActionResult(
            message = "Job ${started.displayName} is ${started.status.name.lowercase()}. $serviceNote".trim(),
            jobs = listOf(toDetail(started, container.jobs.listAll())),
        )
    }

    suspend fun startReadyJobs(limit: Int, update: JobSettingsUpdate?): JobActionResult {
        val cap = limit.coerceIn(1, 40)
        val ready = container.jobs.listAll()
            .filter { it.status == JobStatus.READY && it.sourceUri.isNotBlank() }
            .take(cap)
        if (ready.isEmpty()) {
            return JobActionResult(message = "No READY jobs to start.", jobs = emptyList())
        }
        val started = ArrayList<JobDetail>(ready.size)
        for (job in ready) {
            val result = startJob(job.id, update, deleteSourceAfter = null)
            started.addAll(result.jobs)
        }
        return JobActionResult(
            message = "Started ${started.size} job(s).",
            jobs = started,
        )
    }

    suspend fun cancelJob(jobId: String): JobActionResult {
        val job = requireJob(jobId)
        when (job.status) {
            JobStatus.QUEUED -> container.jobs.cancelQueued(job.id)
            JobStatus.RUNNING -> CompressService.cancelJob(context, job.id)
            else -> error("Job ${job.id} is ${job.status.name} and is not running or queued.")
        }
        val updated = container.jobs.get(job.id) ?: job
        return JobActionResult(
            message = "Cancel requested for ${updated.displayName}.",
            jobs = listOf(toDetail(updated, container.jobs.listAll())),
        )
    }

    suspend fun applyToQueue(preset: String, containerName: String?, queuedOnly: Boolean): JobActionResult {
        val recipe = BatchRecipe(
            preset = JobSettingsCodec.requirePreset(preset),
            container = containerName?.takeIf { it.isNotBlank() }?.let { raw ->
                JobSettingsCodec.parseContainer(raw)
                    ?: error("Unknown container \"$raw\". Use one of: ${JobSettingsCodec.containers.joinToString()}")
            },
        )
        val count = container.applyBatchRecipe(recipe, queuedOnly)
        val all = container.jobs.listAll()
        val jobs = EncodeQueue.active(all)
            .ifEmpty { all.filter { it.status == JobStatus.READY } }
            .take(40)
            .map { toDetail(it, all) }
        return JobActionResult(
            message = if (count == 0) {
                "No waiting jobs to update."
            } else {
                "Applied ${recipe.preset.name}" +
                    (recipe.container?.let { " ${it.name}" } ?: "") +
                    " to $count waiting job(s). The running encode is unchanged."
            },
            jobs = jobs,
        )
    }

    suspend fun cancelQueue(): JobActionResult {
        val before = EncodeQueue.active(container.jobs.listAll())
        if (before.isEmpty()) {
            return JobActionResult(message = "The queue is already empty.", jobs = emptyList())
        }
        CompressService.cancelAll(context)
        val after = before.map { container.jobs.get(it.id) ?: it }
        return JobActionResult(
            message = "Cancel-all requested for ${before.size} job(s).",
            jobs = after.map { toDetail(it, container.jobs.listAll()) },
        )
    }

    suspend fun listDeviceMedia(
        kind: String?,
        query: String?,
        limit: Int,
        relativePath: String?,
        addedAfterEpochMs: Long,
        minDurationMs: Long,
        maxDurationMs: Long,
    ): DeviceMediaList {
        val items = DeviceMediaStore.list(
            context = context,
            kind = kind,
            query = query,
            limit = limit,
            relativePath = relativePath,
            addedAfterEpochMs = addedAfterEpochMs,
            minDurationMs = minDurationMs,
            maxDurationMs = maxDurationMs,
        ).map { row ->
            DeviceMediaItem(
                contentUri = row.contentUri.toString(),
                displayName = row.displayName,
                kind = row.kind,
                mimeType = row.mimeType,
                bytes = row.bytes,
                durationMs = row.durationMs,
                relativePath = row.relativePath,
                dateAddedEpochMs = row.dateAddedEpochMs,
                dateModifiedEpochMs = row.dateModifiedEpochMs,
            )
        }
        return DeviceMediaList(
            items = items,
            kind = kind?.trim()?.uppercase()?.ifBlank { "ANY" } ?: "ANY",
            relativePath = DeviceMediaQueries.normalizeRelativePath(relativePath),
            addedAfterEpochMs = addedAfterEpochMs.coerceAtLeast(0L),
            minDurationMs = minDurationMs.coerceAtLeast(0L),
            maxDurationMs = maxDurationMs.coerceAtLeast(0L),
            libraryAccessGranted = MediaLibraryAccess.granted(context),
        )
    }

    suspend fun importDeviceMedia(uriOrPath: String): JobActionResult {
        val uri = DeviceMediaStore.resolve(context, uriOrPath)
        return importResolved(uri)
    }

    suspend fun importFile(contentUri: Uri): JobActionResult =
        importResolved(DeviceMediaStore.resolve(context, contentUri.toString()))

    suspend fun importCombine(visualUri: Uri, audioUri: Uri): JobActionResult {
        val visual = DeviceMediaStore.resolve(context, visualUri.toString())
        val audio = DeviceMediaStore.resolve(context, audioUri.toString())
        val id = container.importer.importCombine(visual, audio)
        return JobActionResult(
            message = "Created combine job $id.",
            jobs = listOf(getJob(id)),
        )
    }

    private suspend fun importResolved(uri: Uri): JobActionResult {
        val id = container.importer.import(uri)
        return JobActionResult(
            message = "Imported job $id.",
            jobs = listOf(getJob(id)),
        )
    }

    suspend fun getAppDefaults(): AppDefaults {
        val prefs = container.prefs.current()
        return AppDefaults(
            defaultPreset = prefs.defaultPreset.name,
            defaultEngine = prefs.defaultEngine.name,
            autoCompressAfterRecord = prefs.autoCompressAfterRecord,
            rememberAdvanced = prefs.rememberAdvanced,
            deleteOriginalAfterEncode = prefs.deleteOriginalAfterEncode,
            libraryAccessGranted = MediaLibraryAccess.granted(context),
        )
    }

    suspend fun setAppDefaults(
        presetName: String?,
        engineName: String?,
        autoCompressAfterRecord: Boolean?,
        rememberAdvanced: Boolean?,
        deleteOriginalAfterEncode: Boolean?,
    ): AppDefaults {
        if (presetName != null) container.prefs.setDefaultPreset(JobSettingsCodec.requirePreset(presetName))
        if (engineName != null) container.prefs.setDefaultEngine(JobSettingsCodec.requireEngine(engineName))
        if (autoCompressAfterRecord != null) {
            container.prefs.setAutoCompressAfterRecord(autoCompressAfterRecord)
        }
        if (rememberAdvanced != null) container.prefs.setRememberAdvanced(rememberAdvanced)
        if (deleteOriginalAfterEncode != null) {
            container.prefs.setDeleteOriginalAfterEncode(deleteOriginalAfterEncode)
        }
        return getAppDefaults()
    }

    suspend fun compressNow(
        uriOrPath: String,
        update: JobSettingsUpdate?,
        wait: Boolean,
        timeoutSec: Int,
        deleteSourceAfter: Boolean,
    ): WaitResult {
        val imported = importDeviceMedia(uriOrPath)
        val job = imported.jobs.firstOrNull() ?: error("Import did not create a job.")
        val started = startJob(job.summary.jobId, update, deleteSourceAfter)
        val startedJob = started.jobs.first()
        if (!wait) {
            return toWaitResult(started.jobs, timedOut = false, started.message)
        }
        return waitForJob(startedJob.summary.jobId, timeoutSec)
    }

    suspend fun waitForJob(jobId: String, timeoutSec: Int): WaitResult {
        val job = requireJob(jobId)
        if (AgentWait.isTerminal(job.status)) {
            return toWaitResult(listOf(getJob(job.id)), timedOut = false, terminalMessage(job))
        }
        if (!AgentWait.canWait(job.status)) {
            error("Job ${job.id} is ${job.status.name} and is not encoding. Call startJob or compressNow first.")
        }
        return awaitUntil(timeoutSec, idleMessage = "Job ${job.displayName} finished.") { all ->
            val current = all.firstOrNull { it.id == job.id } ?: throw NoSuchElementException("No job with id ${job.id}.")
            when {
                AgentWait.isTerminal(current.status) -> listOf(current) to true
                AgentWait.canWait(current.status) -> listOf(current) to false
                else -> error("Job ${current.id} is ${current.status.name} and is not encoding.")
            }
        }
    }

    suspend fun waitForQueue(timeoutSec: Int): WaitResult {
        val initial = EncodeQueue.active(container.jobs.listAll())
        if (initial.isEmpty()) {
            return toWaitResult(emptyList(), timedOut = false, "The queue is idle.")
        }
        val watchedIds = initial.map { it.id }.toSet()
        return awaitUntil(timeoutSec, idleMessage = "Queue finished.") { all ->
            val active = EncodeQueue.active(all)
            if (active.isEmpty()) {
                all.filter { it.id in watchedIds } to true
            } else {
                active to false
            }
        }
    }

    suspend fun importDeviceMediaBatch(uriOrPaths: List<String>): BatchImportResult {
        val paths = uriOrPaths.map { it.trim() }.filter { it.isNotBlank() }.distinct().take(40)
        if (paths.isEmpty()) error("Pass at least one content URI, file path, or display name.")
        val jobs = ArrayList<JobDetail>()
        val errors = ArrayList<String>()
        for (raw in paths) {
            runCatching { importDeviceMedia(raw) }
                .onSuccess { jobs.addAll(it.jobs) }
                .onFailure { errors.add("${raw.take(80)}: ${it.message ?: "Unable to import"}") }
        }
        return BatchImportResult(
            message = "Imported ${jobs.size} file(s). ${errors.size} failed.",
            jobs = jobs,
            errors = errors,
            importedCount = jobs.size,
            failedCount = errors.size,
        )
    }

    suspend fun importCombineDeviceMedia(visualUriOrPath: String, audioUriOrPath: String): JobActionResult {
        val visual = DeviceMediaStore.resolve(context, visualUriOrPath)
        val audio = DeviceMediaStore.resolve(context, audioUriOrPath)
        return importCombine(visual, audio)
    }

    suspend fun retryJob(jobId: String, update: JobSettingsUpdate?): JobActionResult {
        val job = requireJob(jobId)
        JobSettingsCodec.requireRetryable(job)
        return startJob(job.id, update, deleteSourceAfter = false)
    }

    suspend fun cloneJob(jobId: String, update: JobSettingsUpdate?): JobDetail {
        val job = requireJob(jobId)
        JobSettingsCodec.requireCloneable(job)
        val newId = UUID.randomUUID().toString()
        runCatching { container.inputs.copyJobCache(job.id, newId) }
            .getOrElse { error("Could not copy the source for a second job.") }
        val sourceUri = InputResolver.remapCachedUri(job.sourceUri, job.id, newId)
        val audioUri = if (job.audioUri.isBlank()) {
            ""
        } else {
            InputResolver.remapCachedUri(job.audioUri, job.id, newId)
        }
        if (sourceUri != job.sourceUri) {
            val path = Uri.parse(sourceUri).path
            if (path == null || !File(path).isFile) error("Could not copy the source for a second job.")
        }
        var settings = SettingsJson.decode(job.settingsJson)
        if (update != null) {
            settings = JobSettingsCodec.apply(settings, JobSettingsCodec.patchFromUpdate(update))
        }
        val clone = job.copy(
            id = newId,
            type = if (job.type == JobType.RECORD) JobType.IMPORT else job.type,
            status = JobStatus.READY,
            sourceUri = sourceUri,
            audioUri = audioUri,
            outputUri = null,
            outputBytes = null,
            error = null,
            createdAt = System.currentTimeMillis(),
            finishedAt = null,
            queuedAt = null,
            deleteSourceAfter = false,
            sourceDeleted = false,
            settingsJson = SettingsJson.encode(settings),
        )
        container.jobs.upsert(clone)
        runCatching { container.history.prune() }
        return getJob(newId)
    }

    suspend fun discardJob(jobId: String): JobActionResult {
        val job = requireJob(jobId)
        JobSettingsCodec.requireDiscardable(job)
        val detail = toDetail(job, container.jobs.listAll())
        container.history.deleteJob(job.id)
        return JobActionResult(
            message = "Removed ${job.displayName} from history. Gallery files were left alone.",
            jobs = listOf(detail),
        )
    }

    suspend fun shareOutput(jobId: String): JobActionResult {
        val job = requireOutputJob(jobId)
        val mime = SettingsJson.decode(job.settingsJson).outputMime()
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, Uri.parse(job.outputUri))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(send, if (mime.startsWith("audio")) "Share audio" else "Share video")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startUserActivity(chooser, "Could not open the share sheet. Open the result screen in the app.")
        return JobActionResult(
            message = "Opened the share sheet for ${job.displayName}.",
            jobs = listOf(toDetail(job, container.jobs.listAll())),
        )
    }

    suspend fun openOutput(jobId: String): JobActionResult {
        val job = requireOutputJob(jobId)
        val mime = SettingsJson.decode(job.settingsJson).outputMime()
        val view = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(Uri.parse(job.outputUri), mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startUserActivity(view, "Could not open the file. Open the result screen in the app.")
        return JobActionResult(
            message = "Opened ${job.displayName}.",
            jobs = listOf(toDetail(job, container.jobs.listAll())),
        )
    }

    fun requestLibraryAccess(): LibraryAccessResult {
        if (MediaLibraryAccess.granted(context)) {
            return LibraryAccessResult(granted = true, message = "Library access is already granted.")
        }
        AgentLaunch.openSettings(context, requestLibrary = true)
        return LibraryAccessResult(
            granted = false,
            message = "Opened Settings. Ask the user to allow videos, audio, and photos and choose Allow all.",
        )
    }

    suspend fun getEncoderCapabilities(): DeviceEncodeCaps {
        val caps = container.encoderCapabilities()
        return DeviceEncodeCaps(
            hardwareH264 = caps.hasH264MediaCodec,
            hardwareHevc = caps.hasHevcMediaCodec,
            hardwareVp8 = caps.hasVp8MediaCodec,
            hardwareVp9 = caps.hasVp9MediaCodec,
            hardwareAv1 = caps.hasAv1MediaCodec,
            softwareVp8 = caps.hasLibvpx,
            softwareVp9 = caps.hasLibvpxVp9,
            softwareAv1 = caps.softwareAv1,
            opus = caps.hasLibOpus,
            openH264 = caps.hasOpenH264,
            note = "Prefer hardware H.264 when hardwareH264 is true. Use HEVC only when hardwareHevc is true. " +
                "Use AV1 when hardwareAv1 is true, or softwareAv1 on FFmpeg. " +
                "WebM should use software VP9 (libvpx) when softwareVp9 is true. AV1 works in MP4 and WebM.",
        )
    }

    suspend fun getSourceInfo(jobId: String): SourceInfo {
        val job = requireJob(jobId)
        val probed = runCatching { container.probe.probe(Uri.parse(job.sourceUri)) }.getOrNull()
        val audioProbed = if (job.audioUri.isBlank()) {
            null
        } else {
            runCatching { container.probe.probe(Uri.parse(job.audioUri)) }.getOrNull()
        }
        return SourceInfo(
            jobId = job.id,
            displayName = job.displayName,
            durationMs = probed?.durationMs ?: job.durationMs,
            width = probed?.width ?: job.width,
            height = probed?.height ?: job.height,
            sourceBytes = probed?.bytes ?: job.sourceBytes,
            frameRate = probed?.frameRate ?: 0f,
            hasAudio = probed?.hasAudio == true || audioProbed?.hasAudio == true || job.audioUri.isNotBlank(),
            hasVideo = probed?.hasVideo ?: (job.width > 0 && job.height > 0 || job.stillImage),
            stillImage = probed?.stillImage ?: job.stillImage,
            combine = job.isCombine,
            audioCodec = (audioProbed?.audioCodec ?: probed?.audioCodec).orEmpty(),
        )
    }

    private suspend fun awaitUntil(
        timeoutSec: Int,
        idleMessage: String,
        snapshot: (List<CompressJob>) -> Pair<List<CompressJob>, Boolean>,
    ): WaitResult {
        val timeout = AgentWait.clampTimeout(timeoutSec)
        val deadline = System.currentTimeMillis() + timeout * 1000L
        while (true) {
            val all = container.jobs.listAll()
            val (jobs, done) = snapshot(all)
            if (done) {
                val details = jobs.map { toDetail(it, all) }
                val message = jobs.firstOrNull()?.let { terminalMessage(it) } ?: idleMessage
                return toWaitResult(details, timedOut = false, message)
            }
            val remaining = deadline - System.currentTimeMillis()
            if (remaining <= 0L) {
                val details = jobs.map { toDetail(it, all) }
                return toWaitResult(
                    details,
                    timedOut = true,
                    "Still encoding after ${timeout}s. Call waitForJob or waitForQueue again.",
                )
            }
            delay(remaining.coerceAtMost(500L))
        }
    }

    private suspend fun toWaitResult(
        jobs: List<JobDetail>,
        timedOut: Boolean,
        message: String,
    ): WaitResult {
        val primaryId = jobs.firstOrNull()?.summary?.jobId
        val log = if (primaryId != null && (timedOut || jobs.any { it.summary.status == "FAILED" })) {
            getEncodeLog(primaryId, 4_000)
        } else {
            EncodeLogSnapshot(jobId = primaryId.orEmpty(), found = false, text = "", returnedChars = 0)
        }
        return WaitResult(
            message = message,
            timedOut = timedOut,
            jobs = jobs,
            progress = getProgress(primaryId),
            log = log,
        )
    }

    private fun terminalMessage(job: CompressJob): String = when (job.status) {
        JobStatus.SUCCEEDED -> "Job ${job.displayName} succeeded."
        JobStatus.FAILED -> "Job ${job.displayName} failed. ${job.error.orEmpty()}".trim()
        JobStatus.CANCELLED -> "Job ${job.displayName} was cancelled."
        else -> "Job ${job.displayName} is ${job.status.name}."
    }

    private suspend fun requireOutputJob(jobId: String): CompressJob {
        val job = requireJob(jobId)
        if (job.status != JobStatus.SUCCEEDED || job.outputUri.isNullOrBlank()) {
            error("Job ${job.id} has no compressed file to share yet.")
        }
        return job
    }

    private fun startUserActivity(intent: Intent, fallback: String) {
        try {
            context.startActivity(intent)
        } catch (_: Exception) {
            error(fallback)
        }
    }

    private suspend fun saveSettings(job: CompressJob, settings: EncodeSettings): JobDetail =
        toDetail(persistSettings(job, settings), container.jobs.listAll())

    private suspend fun persistSettings(job: CompressJob, settings: EncodeSettings): CompressJob {
        val json = SettingsJson.encode(settings)
        val updated = job.copy(settingsJson = json)
        container.jobs.upsert(updated)
        return updated
    }

    private suspend fun requireJob(jobId: String): CompressJob {
        val id = jobId.trim()
        if (id.isBlank()) error("jobId is required.")
        return container.jobs.get(id) ?: throw NoSuchElementException("No job with id $id.")
    }

    private fun startEncodeService(jobId: String): String = try {
        CompressService.enqueue(context, jobId)
        "The encode service was started."
    } catch (_: IllegalStateException) {
        "The job is queued. Open the app if encoding does not begin."
    } catch (_: Exception) {
        "The job is queued. Open the app if encoding does not begin."
    }

    private fun toDetail(job: CompressJob, all: List<CompressJob>): JobDetail {
        val settings = SettingsJson.decode(job.settingsJson)
        return JobDetail(
            summary = toSummary(job, all),
            settings = JobSettingsCodec.snapshot(settings),
            hasOutput = !job.outputUri.isNullOrBlank() && job.status == JobStatus.SUCCEEDED,
            outputFolder = settings.galleryFolder(),
            deleteSourceAfter = job.deleteSourceAfter,
            sourceDeleted = job.sourceDeleted,
        )
    }

    private fun toSummary(job: CompressJob, all: List<CompressJob>): JobSummary {
        val settings = SettingsJson.decode(job.settingsJson)
        val (position, size) = EncodeQueue.position(all, job.id)
        return JobSummary(
            jobId = job.id,
            displayName = job.displayName,
            type = job.type.name,
            status = job.status.name,
            durationMs = job.durationMs,
            width = job.width,
            height = job.height,
            sourceBytes = job.sourceBytes,
            outputBytes = job.outputBytes,
            combine = job.isCombine,
            engine = settings.engine.name,
            container = settings.container.name,
            output = settings.output.name,
            error = job.error,
            createdAt = job.createdAt,
            finishedAt = job.finishedAt,
            queuePosition = position,
            queueSize = size,
        )
    }
}
