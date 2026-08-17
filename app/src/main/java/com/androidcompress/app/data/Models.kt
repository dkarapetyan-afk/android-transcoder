package com.androidcompress.app.data

import androidx.room.Entity
import androidx.room.PrimaryKey

enum class JobType { RECORD, IMPORT, COMPRESS, COMBINE }

enum class JobStatus { DRAFT, RECORDING, READY, QUEUED, RUNNING, SUCCEEDED, FAILED, CANCELLED }

enum class Preset { SMALLER, BALANCED, HIGHER }

enum class VideoCodec { H264, HEVC, VP8, VP9 }

enum class EncodeEngine { FFMPEG, MEDIA3 }

enum class OutputMode { VIDEO, AUDIO }

enum class ContainerFormat { MP4, WEBM }

enum class AudioOption { COPY, AAC_64, AAC_96, AAC_128, AAC_192, MUTE }

enum class BitrateMode { CBR, VBR }

enum class KeyframeInterval { AUTO, SEC_1, SEC_2, SEC_5 }

enum class H264Profile { AUTO, BASELINE, MAIN, HIGH }

enum class HdrMode { KEEP, TONE_MAP }

enum class BFrameSetting { AUTO, NONE, ONE, TWO }

enum class RecordAudioMode { NONE, MICROPHONE, INTERNAL }

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

fun EncodeSettings.effectiveVideoCodec(): VideoCodec =
    if (usesWebm()) {
        if (codec == VideoCodec.VP8) VideoCodec.VP8 else VideoCodec.VP9
    } else {
        if (codec == VideoCodec.HEVC) VideoCodec.HEVC else VideoCodec.H264
    }

fun EncodeSettings.withContainer(next: ContainerFormat): EncodeSettings {
    val nextCodec = when {
        next == ContainerFormat.WEBM && codec != VideoCodec.VP8 && codec != VideoCodec.VP9 -> VideoCodec.VP9
        next == ContainerFormat.MP4 && codec != VideoCodec.H264 && codec != VideoCodec.HEVC -> VideoCodec.H264
        else -> codec
    }
    return copy(container = next, codec = nextCodec)
}

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
    val hasLibvpx: Boolean = false,
    val hasLibvpxVp9: Boolean = false,
    val hasLibOpus: Boolean = false,
) {
    val hardwareH264: Boolean get() = hasH264MediaCodec
    val hardwareHevc: Boolean get() = hasHevcMediaCodec
}

data class EncodeStats(
    val timeMs: Long,
    val sizeBytes: Long,
    val speed: Float,
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

fun JobStatus.label(): String = when (this) {
    JobStatus.DRAFT -> "Draft"
    JobStatus.RECORDING -> "Recording"
    JobStatus.READY -> "Ready"
    JobStatus.QUEUED -> "Queued"
    JobStatus.RUNNING -> "Compressing"
    JobStatus.SUCCEEDED -> "Done"
    JobStatus.FAILED -> "Failed"
    JobStatus.CANCELLED -> "Cancelled"
}
