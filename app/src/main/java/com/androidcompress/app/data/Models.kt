package com.androidcompress.app.data

import androidx.room.Entity
import androidx.room.PrimaryKey

enum class JobType { RECORD, IMPORT, COMPRESS, COMBINE }

enum class JobStatus { DRAFT, RECORDING, READY, QUEUED, RUNNING, SUCCEEDED, FAILED, CANCELLED }

enum class Preset { SMALLER, BALANCED, HIGHER }

/** Fit-to-size caps used by compress. Bytes are binary megabytes (1024²), matching Discord / WhatsApp / Gmail. */
enum class TargetSizePreset {
    OFF,
    DISCORD,
    WHATSAPP,
    WHATSAPP_64,
    GMAIL,
    CUSTOM,
    ;

    val bytes: Long?
        get() = when (this) {
            OFF, CUSTOM -> null
            DISCORD -> 10L shl 20
            WHATSAPP -> 16L shl 20
            WHATSAPP_64 -> 64L shl 20
            GMAIL -> 25L shl 20
        }

    companion object {
        const val MIN_BYTES = 256L * 1024
        const val MAX_BYTES = 2L shl 30

        fun of(bytes: Long?): TargetSizePreset {
            val value = bytes?.takeIf { it > 0L } ?: return OFF
            return entries.firstOrNull { it.bytes == value } ?: CUSTOM
        }
    }
}

enum class VideoCodec { H264, HEVC, VP8, VP9, AV1 }

enum class EncodeEngine { FFMPEG, MEDIA3 }

enum class OutputMode { VIDEO, AUDIO }

enum class ContainerFormat { MP4, WEBM }

enum class AudioOption { COPY, AAC_64, AAC_96, AAC_128, AAC_192, MUTE }

enum class BitrateMode { CBR, VBR }

enum class KeyframeInterval { AUTO, SEC_1, SEC_2, SEC_5 }

enum class H264Profile { AUTO, BASELINE, MAIN, HIGH }

enum class HdrMode { KEEP, TONE_MAP }

enum class BFrameSetting { AUTO, NONE, ONE, TWO }

enum class RecordAudioMode {
    NONE, MICROPHONE, INTERNAL, BOTH;

    val usesMicrophone: Boolean get() = this == MICROPHONE || this == BOTH
    val usesInternalAudio: Boolean get() = this == INTERNAL || this == BOTH
    val needsRecordAudioPermission: Boolean get() = this != NONE

    fun resolvedForSdk(sdk: Int): RecordAudioMode {
        if (!usesInternalAudio) return this
        if (sdk >= 29) return this
        return if (usesMicrophone) MICROPHONE else NONE
    }
}

enum class RecordResolution { P720, P1080, DISPLAY }

data class EncodeSettings(
    val preset: Preset = Preset.BALANCED,
    val maxHeight: Int? = 1080,
    val fpsCap: Int? = 30,
    val codec: VideoCodec = VideoCodec.H264,
    val preferHardware: Boolean = true,
    val videoBitrateKbps: Int = 2500,
    val audio: AudioOption = AudioOption.AAC_128,
    val engine: EncodeEngine = EncodeEngine.FFMPEG,
    val bitrateMode: BitrateMode = BitrateMode.CBR,
    val keyframeInterval: KeyframeInterval = KeyframeInterval.AUTO,
    val h264Profile: H264Profile = H264Profile.AUTO,
    val hdrMode: HdrMode = HdrMode.KEEP,
    val audioVolumePercent: Int = 100,
    val fastStart: Boolean = true,
    val bFrames: BFrameSetting = BFrameSetting.AUTO,
    val ffmpegExtraArgs: String = "",
    val ffmpegCommandOverride: String = "",
    val clipStartMs: Long = 0,
    val clipEndMs: Long? = null,
    val output: OutputMode = OutputMode.VIDEO,
    val container: ContainerFormat = ContainerFormat.MP4,
    val targetSizePreset: TargetSizePreset = TargetSizePreset.OFF,
    val targetSizeBytes: Long? = null,
    val twoPass: Boolean = false,
) {
    companion object {
        fun forPreset(preset: Preset, engine: EncodeEngine = EncodeEngine.FFMPEG): EncodeSettings = when (preset) {
            Preset.SMALLER -> EncodeSettings(
                preset = preset,
                maxHeight = 720,
                fpsCap = 30,
                codec = VideoCodec.H264,
                preferHardware = true,
                videoBitrateKbps = 1500,
                audio = AudioOption.AAC_96,
                engine = engine,
            )
            Preset.BALANCED -> EncodeSettings(
                preset = preset,
                maxHeight = 1080,
                fpsCap = 30,
                codec = VideoCodec.H264,
                preferHardware = true,
                videoBitrateKbps = 2500,
                audio = AudioOption.AAC_128,
                engine = engine,
            )
            Preset.HIGHER -> EncodeSettings(
                preset = preset,
                maxHeight = 1440,
                fpsCap = null,
                codec = VideoCodec.H264,
                preferHardware = true,
                videoBitrateKbps = 6000,
                audio = AudioOption.AAC_192,
                engine = engine,
            )
        }
    }
}

data class SourceVideo(
    val uri: String,
    val displayName: String,
    val width: Int,
    val height: Int,
    val durationMs: Long,
    val bytes: Long,
    val frameRate: Float,
    val audioCodec: String?,
    val hasAudio: Boolean,
    val hasVideo: Boolean = width > 0 && height > 0,
    val stillImage: Boolean = false,
    val audioUri: String = "",
) {
    val isCombine: Boolean get() = audioUri.isNotBlank() || stillImage
}

fun EncodeSettings.audioOutput(hasVideo: Boolean = true): Boolean =
    output == OutputMode.AUDIO || !hasVideo

fun EncodeSettings.effectiveAudio(hasVideo: Boolean = true): AudioOption =
    if (audioOutput(hasVideo) && audio == AudioOption.MUTE) AudioOption.AAC_128 else audio

fun EncodeSettings.usesWebm(): Boolean = container == ContainerFormat.WEBM

fun VideoCodec.compatibleWith(container: ContainerFormat): Boolean = when (container) {
    ContainerFormat.WEBM -> this == VideoCodec.VP8 || this == VideoCodec.VP9 || this == VideoCodec.AV1
    ContainerFormat.MP4 -> this == VideoCodec.H264 || this == VideoCodec.HEVC || this == VideoCodec.AV1
}

fun EncodeSettings.effectiveVideoCodec(): VideoCodec =
    if (codec.compatibleWith(container)) codec else defaultCodec(container)

fun EncodeSettings.withContainer(next: ContainerFormat): EncodeSettings {
    val nextCodec = if (codec.compatibleWith(next)) codec else defaultCodec(next)
    return copy(container = next, codec = nextCodec)
}

private fun defaultCodec(container: ContainerFormat): VideoCodec =
    if (container == ContainerFormat.WEBM) VideoCodec.VP9 else VideoCodec.H264

fun EncodeSettings.outputExtension(): String = when {
    usesWebm() -> "webm"
    output == OutputMode.AUDIO -> "m4a"
    else -> "mp4"
}

fun EncodeSettings.outputMime(): String = when {
    usesWebm() && output == OutputMode.AUDIO -> "audio/webm"
    usesWebm() -> "video/webm"
    output == OutputMode.AUDIO -> "audio/mp4"
    else -> "video/mp4"
}

fun EncodeSettings.galleryFolder(): String =
    if (output == OutputMode.AUDIO) "Music/RecordingCompressor" else "Movies/RecordingCompressor"

fun EncodeSettings.hasTargetSize(): Boolean =
    targetSizePreset != TargetSizePreset.OFF && (targetSizeBytes ?: 0L) > 0L

fun EncodeSettings.withTargetPreset(preset: TargetSizePreset): EncodeSettings = when (preset) {
    TargetSizePreset.OFF -> copy(targetSizePreset = TargetSizePreset.OFF, targetSizeBytes = null)
    TargetSizePreset.CUSTOM -> copy(
        targetSizePreset = TargetSizePreset.CUSTOM,
        targetSizeBytes = targetSizeBytes?.takeIf { it >= TargetSizePreset.MIN_BYTES }
            ?: TargetSizePreset.DISCORD.bytes,
        bitrateMode = BitrateMode.CBR,
    )
    TargetSizePreset.DISCORD,
    TargetSizePreset.WHATSAPP,
    TargetSizePreset.WHATSAPP_64,
    TargetSizePreset.GMAIL -> namedTarget(preset)
}

private fun EncodeSettings.namedTarget(preset: TargetSizePreset): EncodeSettings {
    val bytes = preset.bytes ?: return copy(targetSizePreset = TargetSizePreset.OFF, targetSizeBytes = null)
    val nextAudio = when {
        audio == AudioOption.MUTE -> AudioOption.MUTE
        preset == TargetSizePreset.WHATSAPP -> AudioOption.AAC_96
        audio == AudioOption.COPY -> AudioOption.AAC_128
        else -> audio
    }
    var next = copy(
        targetSizePreset = preset,
        targetSizeBytes = bytes,
        bitrateMode = BitrateMode.CBR,
        audio = nextAudio,
    )
    if (next.output == OutputMode.AUDIO) return next
    next = next.withContainer(ContainerFormat.MP4).copy(
        codec = VideoCodec.H264,
        fpsCap = fpsCap ?: 30,
        maxHeight = if (preset == TargetSizePreset.WHATSAPP) 720 else 1080,
        h264Profile = if (preset == TargetSizePreset.WHATSAPP) {
            H264Profile.BASELINE
        } else {
            h264Profile
        },
    )
    return next
}

fun EncodeSettings.canCopyAudio(source: SourceVideo): Boolean {
    val codec = source.audioCodec.orEmpty().lowercase()
    return if (usesWebm()) {
        codec.contains("opus") || codec.contains("vorbis")
    } else {
        codec.contains("aac")
    }
}

fun OutputMode.fileExtension(): String = if (this == OutputMode.AUDIO) "m4a" else "mp4"

fun OutputMode.mimeType(): String = if (this == OutputMode.AUDIO) "audio/mp4" else "video/mp4"

fun OutputMode.galleryFolder(): String =
    if (this == OutputMode.AUDIO) "Music/RecordingCompressor" else "Movies/RecordingCompressor"

data class EncoderCapabilities(
    val hasH264MediaCodec: Boolean = false,
    val hasHevcMediaCodec: Boolean = false,
    val hasOpenH264: Boolean = false,
    val hasMpeg4: Boolean = true,
    val hasVp8MediaCodec: Boolean = false,
    val hasVp9MediaCodec: Boolean = false,
    val hasAv1MediaCodec: Boolean = false,
    val hasLibvpx: Boolean = false,
    val hasLibvpxVp9: Boolean = false,
    val hasLibaomAv1: Boolean = false,
    val hasLibSvtAv1: Boolean = false,
    val hasLibOpus: Boolean = false,
) {
    val hardwareH264: Boolean get() = hasH264MediaCodec
    val hardwareHevc: Boolean get() = hasHevcMediaCodec
    val hardwareAv1: Boolean get() = hasAv1MediaCodec
    val softwareAv1: Boolean get() = hasLibaomAv1 || hasLibSvtAv1

    fun av1Available(engine: EncodeEngine): Boolean =
        hasAv1MediaCodec || (engine == EncodeEngine.FFMPEG && softwareAv1)
}

data class EncodeStats(
    val timeMs: Long,
    val sizeBytes: Long,
    val speed: Float,
    val videoFrameNumber: Int = 0,
)

data class EncodeProgress(
    val jobId: String,
    val fraction: Float,
    val timeMs: Long,
    val message: String? = null,
)

data class EncodeResult(
    val success: Boolean,
    val cancelled: Boolean,
    val outputPath: String?,
    val error: String?,
    val logs: String,
)

@Entity(tableName = "compress_jobs")
data class CompressJob(
    @PrimaryKey val id: String,
    val type: JobType,
    val status: JobStatus,
    val sourceUri: String,
    val outputUri: String?,
    val displayName: String,
    val sourceBytes: Long,
    val outputBytes: Long?,
    val durationMs: Long,
    val width: Int,
    val height: Int,
    val settingsJson: String,
    val error: String?,
    val createdAt: Long,
    val finishedAt: Long?,
    val queuedAt: Long? = null,
    val deleteSourceAfter: Boolean = false,
    val sourceDeleted: Boolean = false,
    val audioUri: String = "",
    val stillImage: Boolean = false,
) {
    val isCombine: Boolean get() = type == JobType.COMBINE || audioUri.isNotBlank()
}

