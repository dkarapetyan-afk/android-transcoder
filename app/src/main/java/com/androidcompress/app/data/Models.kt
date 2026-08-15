package com.androidcompress.app.data

import androidx.room.Entity
import androidx.room.PrimaryKey

enum class JobType { RECORD, IMPORT, COMPRESS }

enum class JobStatus { DRAFT, RECORDING, READY, QUEUED, RUNNING, SUCCEEDED, FAILED, CANCELLED }

enum class Preset { SMALLER, BALANCED, HIGHER }

enum class VideoCodec { H264, HEVC }

enum class EncodeEngine { FFMPEG, MEDIA3 }

enum class OutputMode { VIDEO, AUDIO }

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
)

fun EncodeSettings.audioOutput(hasVideo: Boolean = true): Boolean =
    output == OutputMode.AUDIO || !hasVideo

fun EncodeSettings.effectiveAudio(hasVideo: Boolean = true): AudioOption =
    if (audioOutput(hasVideo) && audio == AudioOption.MUTE) AudioOption.AAC_128 else audio

fun OutputMode.fileExtension(): String = if (this == OutputMode.AUDIO) "m4a" else "mp4"

fun OutputMode.mimeType(): String = if (this == OutputMode.AUDIO) "audio/mp4" else "video/mp4"

fun OutputMode.galleryFolder(): String =
    if (this == OutputMode.AUDIO) "Music/RecordingCompressor" else "Movies/RecordingCompressor"

data class EncoderCapabilities(
    val hasH264MediaCodec: Boolean = false,
    val hasHevcMediaCodec: Boolean = false,
    val hasOpenH264: Boolean = false,
    val hasMpeg4: Boolean = true,
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
)

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
