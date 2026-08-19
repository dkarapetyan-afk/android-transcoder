package com.androidcompress.app.agent

import com.androidcompress.app.data.AudioOption
import com.androidcompress.app.data.BFrameSetting
import com.androidcompress.app.data.BitrateMode
import com.androidcompress.app.data.CompressJob
import com.androidcompress.app.data.ContainerFormat
import com.androidcompress.app.data.EncodeEngine
import com.androidcompress.app.data.EncodeSettings
import com.androidcompress.app.data.H264Profile
import com.androidcompress.app.data.HdrMode
import com.androidcompress.app.data.JobStatus
import com.androidcompress.app.data.KeyframeInterval
import com.androidcompress.app.data.OutputMode
import com.androidcompress.app.data.Preset
import com.androidcompress.app.data.SourceVideo
import com.androidcompress.app.data.VideoCodec
import com.androidcompress.app.data.withContainer
import com.androidcompress.app.encode.ExtraArgsSanitizer
import com.androidcompress.app.encode.FfmpegCommandTemplate

data class SettingsPatch(
    val preset: Preset? = null,
    val engine: EncodeEngine? = null,
    val output: OutputMode? = null,
    val container: ContainerFormat? = null,
    val codec: VideoCodec? = null,
    val maxHeight: Int? = null,
    val clearMaxHeight: Boolean = false,
    val fpsCap: Int? = null,
    val clearFpsCap: Boolean = false,
    val preferHardware: Boolean? = null,
    val videoBitrateKbps: Int? = null,
    val audio: AudioOption? = null,
    val bitrateMode: BitrateMode? = null,
    val keyframeInterval: KeyframeInterval? = null,
    val h264Profile: H264Profile? = null,
    val hdrMode: HdrMode? = null,
    val audioVolumePercent: Int? = null,
    val fastStart: Boolean? = null,
    val bFrames: BFrameSetting? = null,
    val ffmpegExtraArgs: String? = null,
    val ffmpegCommandOverride: String? = null,
    val clearCommandOverride: Boolean = false,
    val clipStartMs: Long? = null,
    val clipEndMs: Long? = null,
    val clearClipEnd: Boolean = false,
    val clearClip: Boolean = false,
)

object JobSettingsCodec {
    val presets = enumNames<Preset>()
    val engines = enumNames<EncodeEngine>()
    val outputs = enumNames<OutputMode>()
    val containers = enumNames<ContainerFormat>()
    val codecs = enumNames<VideoCodec>()
    val audioOptions = enumNames<AudioOption>()
    val bitrateModes = enumNames<BitrateMode>()
    val keyframeIntervals = enumNames<KeyframeInterval>()
    val h264Profiles = enumNames<H264Profile>()
    val hdrModes = enumNames<HdrMode>()
    val bFrameSettings = enumNames<BFrameSetting>()
    val jobStatuses = enumNames<JobStatus>()

    fun apply(base: EncodeSettings, patch: SettingsPatch): EncodeSettings {
        var next = if (patch.preset != null) {
            EncodeSettings.forPreset(patch.preset, patch.engine ?: base.engine)
        } else {
            base
        }
        if (patch.engine != null) next = next.copy(engine = patch.engine)
        if (patch.output != null) next = next.copy(output = patch.output)
        if (patch.container != null) next = next.withContainer(patch.container)
        if (patch.codec != null) next = next.copy(codec = patch.codec)
        next = when {
            patch.clearMaxHeight -> next.copy(maxHeight = null)
            patch.maxHeight != null -> next.copy(maxHeight = sanitizeHeight(patch.maxHeight))
            else -> next
        }
        next = when {
            patch.clearFpsCap -> next.copy(fpsCap = null)
            patch.fpsCap != null -> next.copy(fpsCap = sanitizeFps(patch.fpsCap))
            else -> next
        }
        if (patch.preferHardware != null) next = next.copy(preferHardware = patch.preferHardware)
        if (patch.videoBitrateKbps != null) {
            next = next.copy(videoBitrateKbps = sanitizeBitrate(patch.videoBitrateKbps))
        }
        if (patch.audio != null) next = next.copy(audio = patch.audio)
        if (patch.bitrateMode != null) next = next.copy(bitrateMode = patch.bitrateMode)
        if (patch.keyframeInterval != null) next = next.copy(keyframeInterval = patch.keyframeInterval)
        if (patch.h264Profile != null) next = next.copy(h264Profile = patch.h264Profile)
        if (patch.hdrMode != null) next = next.copy(hdrMode = patch.hdrMode)
        if (patch.audioVolumePercent != null) {
            next = next.copy(audioVolumePercent = sanitizeVolume(patch.audioVolumePercent))
        }
        if (patch.fastStart != null) next = next.copy(fastStart = patch.fastStart)
        if (patch.bFrames != null) next = next.copy(bFrames = patch.bFrames)
        if (patch.ffmpegExtraArgs != null) {
            next = next.copy(ffmpegExtraArgs = sanitizeExtraArgs(patch.ffmpegExtraArgs))
        }
        next = when {
            patch.clearCommandOverride -> next.copy(ffmpegCommandOverride = "")
            patch.ffmpegCommandOverride != null -> {
                next.copy(ffmpegCommandOverride = sanitizeCommandOverride(patch.ffmpegCommandOverride))
            }
            else -> next
        }
        next = when {
            patch.clearClip -> next.copy(clipStartMs = 0L, clipEndMs = null)
            else -> {
                val start = patch.clipStartMs ?: next.clipStartMs
                val end = when {
                    patch.clearClipEnd -> null
                    patch.clipEndMs != null -> patch.clipEndMs
                    else -> next.clipEndMs
                }
                validateClip(start, end)
                next.copy(clipStartMs = start, clipEndMs = end)
            }
        }
        return next
    }

    fun snapshot(settings: EncodeSettings) = JobSettingsSnapshot(
        preset = settings.preset.name,
        engine = settings.engine.name,
        output = settings.output.name,
        container = settings.container.name,
        codec = settings.codec.name,
        maxHeight = settings.maxHeight,
        fpsCap = settings.fpsCap,
        preferHardware = settings.preferHardware,
        videoBitrateKbps = settings.videoBitrateKbps,
        audio = settings.audio.name,
        bitrateMode = settings.bitrateMode.name,
        keyframeInterval = settings.keyframeInterval.name,
        h264Profile = settings.h264Profile.name,
        hdrMode = settings.hdrMode.name,
        audioVolumePercent = settings.audioVolumePercent,
        fastStart = settings.fastStart,
        bFrames = settings.bFrames.name,
        ffmpegExtraArgs = settings.ffmpegExtraArgs,
        ffmpegCommandOverride = settings.ffmpegCommandOverride,
        clipStartMs = settings.clipStartMs,
        clipEndMs = settings.clipEndMs,
    )

    fun parsePreset(raw: String?): Preset? = parseEnum(raw)
    fun parseEngine(raw: String?): EncodeEngine? = parseEnum(raw)
    fun parseOutput(raw: String?): OutputMode? = parseEnum(raw)
    fun parseContainer(raw: String?): ContainerFormat? = parseEnum(raw)
    fun parseCodec(raw: String?): VideoCodec? = parseEnum(raw)
    fun parseAudio(raw: String?): AudioOption? = parseEnum(raw)
    fun parseBitrateMode(raw: String?): BitrateMode? = parseEnum(raw)
    fun parseKeyframe(raw: String?): KeyframeInterval? = parseEnum(raw)
    fun parseH264Profile(raw: String?): H264Profile? = parseEnum(raw)
    fun parseHdr(raw: String?): HdrMode? = parseEnum(raw)
    fun parseBFrames(raw: String?): BFrameSetting? = parseEnum(raw)
    fun parseStatus(raw: String?): JobStatus? = parseEnum(raw)

    fun requirePreset(raw: String): Preset =
        parsePreset(raw) ?: error("Unknown preset \"$raw\". Use one of: ${presets.joinToString()}")

    fun requireEngine(raw: String): EncodeEngine =
        parseEngine(raw) ?: error("Unknown engine \"$raw\". Use one of: ${engines.joinToString()}")

    fun requireStatus(raw: String): JobStatus =
        parseStatus(raw) ?: error("Unknown status \"$raw\". Use one of: ${jobStatuses.joinToString()}")

    fun patchFromUpdate(update: JobSettingsUpdate): SettingsPatch = SettingsPatch(
        preset = parsePreset(update.preset),
        engine = parseEngine(update.engine),
        output = parseOutput(update.output),
        container = parseContainer(update.container),
        codec = parseCodec(update.codec),
        maxHeight = update.maxHeight,
        clearMaxHeight = update.clearMaxHeight,
        fpsCap = update.fpsCap,
        clearFpsCap = update.clearFpsCap,
        preferHardware = update.preferHardware,
        videoBitrateKbps = update.videoBitrateKbps,
        audio = parseAudio(update.audio),
        bitrateMode = parseBitrateMode(update.bitrateMode),
        keyframeInterval = parseKeyframe(update.keyframeInterval),
        h264Profile = parseH264Profile(update.h264Profile),
        hdrMode = parseHdr(update.hdrMode),
        audioVolumePercent = update.audioVolumePercent,
        fastStart = update.fastStart,
        bFrames = parseBFrames(update.bFrames),
        ffmpegExtraArgs = update.ffmpegExtraArgs,
        ffmpegCommandOverride = update.ffmpegCommandOverride,
        clearCommandOverride = update.clearCommandOverride,
        clipStartMs = update.clipStartMs,
        clipEndMs = update.clipEndMs,
        clearClipEnd = update.clearClipEnd,
        clearClip = update.clearClip,
    )

    fun canEdit(status: JobStatus): Boolean = when (status) {
        JobStatus.QUEUED, JobStatus.RUNNING, JobStatus.RECORDING -> false
        else -> true
    }

    fun canStart(job: CompressJob): Boolean {
        if (job.sourceUri.isBlank()) return false
        return when (job.status) {
            JobStatus.READY, JobStatus.FAILED, JobStatus.CANCELLED, JobStatus.SUCCEEDED, JobStatus.DRAFT -> true
            JobStatus.QUEUED, JobStatus.RUNNING, JobStatus.RECORDING -> false
        }
    }

    fun canRetry(status: JobStatus): Boolean = when (status) {
        JobStatus.FAILED, JobStatus.CANCELLED, JobStatus.SUCCEEDED -> true
        else -> false
    }

    fun canDiscard(status: JobStatus): Boolean = when (status) {
        JobStatus.QUEUED, JobStatus.RUNNING, JobStatus.RECORDING -> false
        else -> true
    }

    fun canClone(job: CompressJob): Boolean {
        if (job.sourceUri.isBlank() || job.sourceDeleted) return false
        return job.status != JobStatus.RECORDING
    }

    fun requireEditable(job: CompressJob) {
        if (!canEdit(job.status)) {
            error("Job ${job.id} is ${job.status.name} and cannot be edited. Cancel it first.")
        }
    }

    fun requireStartable(job: CompressJob) {
        if (job.sourceUri.isBlank()) error("Job ${job.id} has no source file.")
        if (!canStart(job)) {
            error("Job ${job.id} is ${job.status.name} and cannot be started.")
        }
    }

    fun requireRetryable(job: CompressJob) {
        if (job.sourceDeleted) error("The source for job ${job.id} was deleted. Import the file again.")
        if (!canRetry(job.status)) {
            error("Job ${job.id} is ${job.status.name} and does not need a retry. Use startJob.")
        }
        requireStartable(job)
    }

    fun requireDiscardable(job: CompressJob) {
        if (!canDiscard(job.status)) {
            error("Job ${job.id} is ${job.status.name}. Cancel it before discarding history.")
        }
    }

    fun requireCloneable(job: CompressJob) {
        if (job.sourceDeleted) error("The source for job ${job.id} was deleted. Import the file again.")
        if (!canClone(job)) {
            error("Job ${job.id} is ${job.status.name} and cannot be cloned.")
        }
    }

    fun toSource(job: CompressJob): SourceVideo = SourceVideo(
        uri = job.sourceUri,
        displayName = job.displayName,
        width = job.width,
        height = job.height,
        durationMs = job.durationMs,
        bytes = job.sourceBytes,
        frameRate = 30f,
        audioCodec = null,
        hasAudio = !job.isCombine || job.audioUri.isNotBlank(),
        hasVideo = job.width > 0 && job.height > 0 || job.stillImage,
        stillImage = job.stillImage,
        audioUri = job.audioUri,
    )

    fun sanitizeExtraArgs(raw: String): String {
        val parsed = ExtraArgsSanitizer.parse(raw)
        if (!parsed.isValid) error(parsed.error ?: "Invalid extra FFmpeg arguments.")
        return parsed.canonical
    }

    fun sanitizeCommandOverride(raw: String): String {
        if (raw.isBlank()) return ""
        val parsed = FfmpegCommandTemplate.parse(raw)
        if (!parsed.isValid) error(parsed.error ?: "Invalid FFmpeg command template.")
        return parsed.canonical
    }

    private fun sanitizeHeight(value: Int): Int {
        if (value !in 144..4320) error("maxHeight must be between 144 and 4320, or clearMaxHeight.")
        return value
    }

    private fun sanitizeFps(value: Int): Int {
        if (value !in 1..120) error("fpsCap must be between 1 and 120, or clearFpsCap.")
        return value
    }

    private fun sanitizeBitrate(value: Int): Int {
        if (value !in 100..40_000) error("videoBitrateKbps must be between 100 and 40000.")
        return value
    }

    private fun sanitizeVolume(value: Int): Int {
        if (value !in 10..400) error("audioVolumePercent must be between 10 and 400.")
        return value
    }

    private fun validateClip(startMs: Long, endMs: Long?) {
        if (startMs < 0L) error("clipStartMs cannot be negative.")
        if (endMs != null && endMs <= startMs) error("clipEndMs must be greater than clipStartMs.")
    }

    private inline fun <reified T : Enum<T>> parseEnum(raw: String?): T? {
        if (raw.isNullOrBlank()) return null
        val normalized = raw.trim().uppercase().replace('-', '_').replace(' ', '_')
        return enumValues<T>().firstOrNull { it.name == normalized }
    }

    private inline fun <reified T : Enum<T>> enumNames(): List<String> = enumValues<T>().map { it.name }
}
