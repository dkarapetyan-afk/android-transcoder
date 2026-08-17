package com.androidcompress.app.agent

import android.content.Context
import android.net.Uri
import com.androidcompress.app.data.AudioOption
import com.androidcompress.app.data.CompressJob
import com.androidcompress.app.data.EncodeEngine
import com.androidcompress.app.data.EncodeSettings
import com.androidcompress.app.data.JobStatus
import com.androidcompress.app.data.Preset
import com.androidcompress.app.data.SettingsJson
import com.androidcompress.app.data.audioOutput
import com.androidcompress.app.data.effectiveAudio
import com.androidcompress.app.data.galleryFolder
import com.androidcompress.app.data.usesWebm
import com.androidcompress.app.di.AppContainer
import com.androidcompress.app.encode.CompressService
import com.androidcompress.app.encode.EncodeQueue
import com.androidcompress.app.encode.FfmpegCommandBuilder
import com.androidcompress.app.encode.FfmpegCommandTemplate
import com.androidcompress.app.encode.Media3EncodePlanner
import com.androidcompress.app.media.DeviceMediaStore
import com.androidcompress.app.media.MediaLibraryAccess
import com.androidcompress.app.container

class JobAgent(
    private val context: Context,
    private val container: AppContainer = context.container(),
) {
    fun describeCapabilities(): AppCapabilities = AppCapabilities(
        summary = "Recording Compressor encodes videos and audio on this device with FFmpeg or Media3. " +
            "It can also combine a picture or video with a separate soundtrack.",
        workflow = "1) If libraryAccessGranted is false, tell the user to grant library access in Settings (Allow all). " +
            "2) listDeviceMedia to find a file, then importDeviceMedia or importFile. " +
            "3) getJob and optionally updateJobSettings or applyPreset. " +
            "4) previewEncode, then startJob or startReadyJobs. " +
            "5) getProgress and getEncodeLog until status is SUCCEEDED or FAILED.",
        presets = JobSettingsCodec.presets,
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
            "Then listDeviceMedia and importDeviceMedia can open files already on the device. " +
            "importDeviceMedia accepts a MediaStore content URI, an absolute path, or an exact display name.",
        restrictions = "This API does not record the screen, delete gallery files, clear history, or send media off the device. " +
            "Job records omit source and output URIs. Extra FFmpeg args cannot add inputs or paths.",
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
                append("The FFmpeg command override is ignored on the Media3 engine.")
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

    suspend fun listDeviceMedia(kind: String?, query: String?, limit: Int): DeviceMediaList {
        val items = DeviceMediaStore.list(context, kind, query, limit).map { row ->
            DeviceMediaItem(
                contentUri = row.contentUri.toString(),
                displayName = row.displayName,
                kind = row.kind,
                mimeType = row.mimeType,
                bytes = row.bytes,
                durationMs = row.durationMs,
                relativePath = row.relativePath,
            )
        }
        return DeviceMediaList(
            items = items,
            kind = kind?.trim()?.uppercase()?.ifBlank { "ANY" } ?: "ANY",
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
    ): AppDefaults {
        if (presetName != null) container.prefs.setDefaultPreset(JobSettingsCodec.requirePreset(presetName))
        if (engineName != null) container.prefs.setDefaultEngine(JobSettingsCodec.requireEngine(engineName))
        if (autoCompressAfterRecord != null) {
            container.prefs.setAutoCompressAfterRecord(autoCompressAfterRecord)
        }
        if (rememberAdvanced != null) container.prefs.setRememberAdvanced(rememberAdvanced)
        return getAppDefaults()
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
