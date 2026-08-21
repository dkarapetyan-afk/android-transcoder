package com.androidcompress.app.agent

import androidx.appfunctions.AppFunctionSerializable

/** Allowed values and how to drive Recording Compressor from an agent. */
@AppFunctionSerializable(isDescribedByKDoc = true)
data class AppCapabilities(
    /** Short description of what this app does. */
    val summary: String,
    /** Ordered steps an agent should follow for a typical compress request. */
    val workflow: String,
    /** Built-in quality preset names. */
    val presets: List<String>,
    /** Fit-to-size preset names: OFF, DISCORD, WHATSAPP, WHATSAPP_64, GMAIL, CUSTOM. */
    val targetSizePresets: List<String>,
    /** Encode engines: FFMPEG or MEDIA3. */
    val engines: List<String>,
    /** Output modes: VIDEO or AUDIO. */
    val outputs: List<String>,
    /** Containers: MP4 or WEBM. */
    val containers: List<String>,
    /** Video codecs: H264, HEVC, VP8, VP9, AV1. */
    val codecs: List<String>,
    /** Audio options: COPY, AAC_64, AAC_96, AAC_128, AAC_192, MUTE. */
    val audioOptions: List<String>,
    /** Bitrate modes: CBR or VBR. */
    val bitrateModes: List<String>,
    /** Keyframe intervals: AUTO, SEC_1, SEC_2, SEC_5. */
    val keyframeIntervals: List<String>,
    /** H.264 profiles: AUTO, BASELINE, MAIN, HIGH. */
    val h264Profiles: List<String>,
    /** HDR handling: KEEP or TONE_MAP. */
    val hdrModes: List<String>,
    /** B-frame settings: AUTO, NONE, ONE, TWO. */
    val bFrameSettings: List<String>,
    /** Job statuses the app uses. */
    val jobStatuses: List<String>,
    /** True when the user granted video/audio/photo library access. */
    val libraryAccessGranted: Boolean,
    /** How to list and import files that are already on the device. */
    val libraryAccessNote: String,
    /** Preferred tools for a typical request, in order. */
    val recommendedTools: List<String>,
    /** Actions this API will not perform. */
    val restrictions: String,
)

/** One built-in encode preset. */
@AppFunctionSerializable(isDescribedByKDoc = true)
data class PresetInfo(
    /** Preset name: SMALLER, BALANCED, or HIGHER. */
    val name: String,
    /** Human-readable summary of the preset. */
    val description: String,
    /** Default settings this preset applies. */
    val settings: JobSettingsSnapshot,
)

/** Settings currently stored on a job or preset. */
@AppFunctionSerializable(isDescribedByKDoc = true)
data class JobSettingsSnapshot(
    /** Preset name this settings object is based on. */
    val preset: String,
    /** Encode engine: FFMPEG or MEDIA3. */
    val engine: String,
    /** Output mode: VIDEO or AUDIO. */
    val output: String,
    /** Container: MP4 or WEBM. */
    val container: String,
    /** Video codec: H264, HEVC, VP8, VP9, or AV1. */
    val codec: String,
    /** Max output height in pixels, or null to keep the source height. */
    val maxHeight: Int?,
    /** Frame-rate cap, or null for the source frame rate. */
    val fpsCap: Int?,
    /** Whether hardware encoders are preferred. */
    val preferHardware: Boolean,
    /** Requested video bitrate in kbps. */
    val videoBitrateKbps: Int,
    /** Audio option name. */
    val audio: String,
    /** Bitrate mode: CBR or VBR. */
    val bitrateMode: String,
    /** Keyframe interval name. */
    val keyframeInterval: String,
    /** H.264 profile name. */
    val h264Profile: String,
    /** HDR mode name. */
    val hdrMode: String,
    /** Audio volume percent, 10 to 400. */
    val audioVolumePercent: Int,
    /** Whether to write MP4 faststart flags. */
    val fastStart: Boolean,
    /** B-frame setting name. */
    val bFrames: String,
    /** Extra FFmpeg flags appended to the generated command. */
    val ffmpegExtraArgs: String,
    /** Full FFmpeg command template using INPUT, AUDIO, and OUTPUT placeholders. */
    val ffmpegCommandOverride: String,
    /** Clip start in milliseconds. */
    val clipStartMs: Long,
    /** Clip end in milliseconds, or null for the end of the source. */
    val clipEndMs: Long?,
    /** Fit-to-size preset: OFF, DISCORD, WHATSAPP, WHATSAPP_64, GMAIL, or CUSTOM. */
    val targetSizePreset: String,
    /**
     * Target output size in bytes. Discord free is 10485760, WhatsApp 16777216 or 67108864,
     * Gmail 26214400. Null when fit-to-size is off. Video bitrate is
     * (targetBytes × 8 / duration) minus audio and muxer overhead.
     */
    val targetSizeBytes: Long?,
    /** True when FFmpeg should run a 2-pass VBR encode. Ignored by Media3 and audio-only jobs. */
    val twoPass: Boolean,
)

/**
 * Partial update for a job. Null or omitted fields stay unchanged.
 * Setting [preset] first resets to that preset, then the other fields overlay it.
 */
@AppFunctionSerializable(isDescribedByKDoc = true)
data class JobSettingsUpdate(
    /** Preset to apply first: SMALLER, BALANCED, or HIGHER. */
    val preset: String? = null,
    /** Encode engine: FFMPEG or MEDIA3. */
    val engine: String? = null,
    /** Output mode: VIDEO or AUDIO. */
    val output: String? = null,
    /** Container: MP4 or WEBM. */
    val container: String? = null,
    /** Video codec: H264, HEVC, VP8, VP9, or AV1. */
    val codec: String? = null,
    /** Max output height in pixels. */
    val maxHeight: Int? = null,
    /** If true, remove the height cap and keep the source height. */
    val clearMaxHeight: Boolean = false,
    /** Frame-rate cap. */
    val fpsCap: Int? = null,
    /** If true, keep the source frame rate. */
    val clearFpsCap: Boolean = false,
    /** Whether hardware encoders are preferred. */
    val preferHardware: Boolean? = null,
    /** Requested video bitrate in kbps, 100 to 40000. */
    val videoBitrateKbps: Int? = null,
    /** Audio option: COPY, AAC_64, AAC_96, AAC_128, AAC_192, or MUTE. */
    val audio: String? = null,
    /** Bitrate mode: CBR or VBR. */
    val bitrateMode: String? = null,
    /** Keyframe interval: AUTO, SEC_1, SEC_2, or SEC_5. */
    val keyframeInterval: String? = null,
    /** H.264 profile: AUTO, BASELINE, MAIN, or HIGH. */
    val h264Profile: String? = null,
    /** HDR handling: KEEP or TONE_MAP. */
    val hdrMode: String? = null,
    /** Audio volume percent, 10 to 400. */
    val audioVolumePercent: Int? = null,
    /** Whether to write MP4 faststart flags. */
    val fastStart: Boolean? = null,
    /** B-frame setting: AUTO, NONE, ONE, or TWO. */
    val bFrames: String? = null,
    /** Extra FFmpeg flags. Empty string clears them. */
    val ffmpegExtraArgs: String? = null,
    /** Full FFmpeg command template with INPUT/OUTPUT placeholders. */
    val ffmpegCommandOverride: String? = null,
    /** If true, drop any custom command template. */
    val clearCommandOverride: Boolean = false,
    /** Clip start in milliseconds. */
    val clipStartMs: Long? = null,
    /** Clip end in milliseconds. */
    val clipEndMs: Long? = null,
    /** If true, encode through the end of the source. */
    val clearClipEnd: Boolean = false,
    /** If true, encode the whole source with no clip. */
    val clearClip: Boolean = false,
    /** Fit-to-size preset: OFF, DISCORD, WHATSAPP, WHATSAPP_64, GMAIL, or CUSTOM. */
    val targetSizePreset: String? = null,
    /**
     * Target output size in bytes, 262144 to 2147483648.
     * Discord free = 10485760, WhatsApp = 16777216 or 67108864, Gmail = 26214400.
     */
    val targetSizeBytes: Long? = null,
    /** If true, turn off fit-to-size and use videoBitrateKbps instead. */
    val clearTargetSize: Boolean = false,
    /** True to run FFmpeg 2-pass VBR. Media3 and audio-only jobs ignore this. */
    val twoPass: Boolean? = null,
)

/** Compact job row for lists. */
@AppFunctionSerializable(isDescribedByKDoc = true)
data class JobSummary(
    /** Job id to pass to other functions. */
    val jobId: String,
    /** Display name of the source. */
    val displayName: String,
    /** Job type: RECORD, IMPORT, COMPRESS, or COMBINE. */
    val type: String,
    /** Current status name. */
    val status: String,
    /** Source duration in milliseconds. */
    val durationMs: Long,
    /** Source width in pixels. */
    val width: Int,
    /** Source height in pixels. */
    val height: Int,
    /** Source size in bytes. */
    val sourceBytes: Long,
    /** Compressed size in bytes when finished. */
    val outputBytes: Long?,
    /** True when this job muxes a separate soundtrack. */
    val combine: Boolean,
    /** Encode engine currently stored on the job. */
    val engine: String,
    /** Container currently stored on the job. */
    val container: String,
    /** Output mode currently stored on the job. */
    val output: String,
    /** Error text when the job failed. */
    val error: String?,
    /** Created-at epoch milliseconds. */
    val createdAt: Long,
    /** Finished-at epoch milliseconds when known. */
    val finishedAt: Long?,
    /** One-based queue position when queued or running, otherwise 0. */
    val queuePosition: Int,
    /** Number of queued plus running jobs. */
    val queueSize: Int,
)

/** Full job record plus settings. Source and output URIs are omitted. */
@AppFunctionSerializable(isDescribedByKDoc = true)
data class JobDetail(
    /** Job summary. */
    val summary: JobSummary,
    /** Current encode settings. */
    val settings: JobSettingsSnapshot,
    /** True when a compressed file was exported to the gallery. */
    val hasOutput: Boolean,
    /** Gallery folder used for a successful output. */
    val outputFolder: String,
    /** True when the job is set to delete the source after a successful encode. */
    val deleteSourceAfter: Boolean,
    /** True when the source was already deleted. */
    val sourceDeleted: Boolean,
)

/** Result of listing jobs. */
@AppFunctionSerializable(isDescribedByKDoc = true)
data class JobListResult(
    /** Matching jobs, newest first. */
    val jobs: List<JobSummary>,
    /** How many jobs matched before the limit. */
    val totalMatched: Int,
    /** Filter that was applied, or ALL. */
    val statusFilter: String,
)

/** Live queue snapshot. */
@AppFunctionSerializable(isDescribedByKDoc = true)
data class QueueSnapshot(
    /** Running and queued jobs in run order. */
    val active: List<JobSummary>,
    /** Count of READY jobs that can be started. */
    val readyCount: Int,
    /** Count of currently running jobs. */
    val runningCount: Int,
    /** Count of queued jobs waiting to run. */
    val queuedCount: Int,
)

/** Progress for one job. */
@AppFunctionSerializable(isDescribedByKDoc = true)
data class ProgressSnapshot(
    /** Job id, or empty if nothing is encoding. */
    val jobId: String,
    /** Current status name, or NONE. */
    val status: String,
    /** Display name, if a job was found. */
    val displayName: String,
    /** Encode fraction from 0 to 1 when known. */
    val fraction: Float,
    /** Percent complete from 0 to 100 when known. */
    val percent: Int,
    /** Encoded media time in milliseconds when the encoder reports it. */
    val timeMs: Long,
    /** Latest progress message. */
    val message: String,
    /** Queue position when queued or running. */
    val queuePosition: Int,
    /** Number of queued plus running jobs. */
    val queueSize: Int,
    /** Failure message when the job failed. */
    val error: String,
)

/** Tail of an encode log. */
@AppFunctionSerializable(isDescribedByKDoc = true)
data class EncodeLogSnapshot(
    /** Job id whose log was read. */
    val jobId: String,
    /** True when a log file exists. */
    val found: Boolean,
    /** Log text, possibly truncated to the last characters. */
    val text: String,
    /** Number of characters returned. */
    val returnedChars: Int,
)

/** Command and size preview for the current job settings. */
@AppFunctionSerializable(isDescribedByKDoc = true)
data class EncodePreview(
    /** Job id. */
    val jobId: String,
    /** Encoder label the app would show. */
    val encoderLabel: String,
    /** FFmpeg command template with INPUT/OUTPUT placeholders. Empty for Media3. */
    val command: String,
    /** True when the stored command override differs from the generated command. */
    val commandCustomized: Boolean,
    /** Estimated output size in bytes. */
    val estimateBytes: Long,
    /** Current settings used for the preview. */
    val settings: JobSettingsSnapshot,
    /** Extra notes, such as unused extra args on Media3. */
    val notes: String,
)

/** Result of start, cancel, or import. */
@AppFunctionSerializable(isDescribedByKDoc = true)
data class JobActionResult(
    /** Human-readable result. */
    val message: String,
    /** Jobs affected by the action. */
    val jobs: List<JobDetail>,
)

/** App-wide defaults used for newly imported or recorded jobs. */
@AppFunctionSerializable(isDescribedByKDoc = true)
data class AppDefaults(
    /** Default preset name. */
    val defaultPreset: String,
    /** Default encode engine. */
    val defaultEngine: String,
    /** Whether a finished recording jumps straight into encode. */
    val autoCompressAfterRecord: Boolean,
    /** Whether the compress screen restores the last advanced settings. */
    val rememberAdvanced: Boolean,
    /** Whether new compress-screen jobs default to deleting the source after success. */
    val deleteOriginalAfterEncode: Boolean,
    /** True when the user granted video/audio/photo library access. */
    val libraryAccessGranted: Boolean,
)

/** One file from the device media library. */
@AppFunctionSerializable(isDescribedByKDoc = true)
data class DeviceMediaItem(
    /** MediaStore content URI. Pass this to importFile or importDeviceMedia. */
    val contentUri: String,
    /** File name shown in the library. */
    val displayName: String,
    /** VIDEO, AUDIO, or IMAGE. */
    val kind: String,
    /** MIME type when known. */
    val mimeType: String,
    /** Size in bytes. */
    val bytes: Long,
    /** Duration in milliseconds for video and audio. */
    val durationMs: Long,
    /** MediaStore relative folder, such as Download/. */
    val relativePath: String,
    /** DATE_ADDED as epoch milliseconds, or 0 if unknown. */
    val dateAddedEpochMs: Long,
    /** DATE_MODIFIED as epoch milliseconds, or 0 if unknown. */
    val dateModifiedEpochMs: Long,
)

/** Result of listing on-device media. */
@AppFunctionSerializable(isDescribedByKDoc = true)
data class DeviceMediaList(
    /** Matching library items, newest first. */
    val items: List<DeviceMediaItem>,
    /** Kind filter that was applied. */
    val kind: String,
    /** Relative-path filter that was applied, if any. */
    val relativePath: String,
    /** DATE_ADDED lower bound that was applied, or 0. */
    val addedAfterEpochMs: Long,
    /** Minimum duration filter that was applied, or 0. */
    val minDurationMs: Long,
    /** Maximum duration filter that was applied, or 0. */
    val maxDurationMs: Long,
    /** True when library access is granted. */
    val libraryAccessGranted: Boolean,
)

/** Result of waiting for one job or the whole queue. */
@AppFunctionSerializable(isDescribedByKDoc = true)
data class WaitResult(
    /** Human-readable result. */
    val message: String,
    /** True when the job or queue was still encoding when the timeout expired. Call wait again. */
    val timedOut: Boolean,
    /** Jobs that were watched. */
    val jobs: List<JobDetail>,
    /** Latest progress for the primary watched job. */
    val progress: ProgressSnapshot,
    /** Encode log tail after a failure or timeout. */
    val log: EncodeLogSnapshot,
)

/** Result of importing several files. */
@AppFunctionSerializable(isDescribedByKDoc = true)
data class BatchImportResult(
    /** Human-readable result. */
    val message: String,
    /** Jobs that were created. */
    val jobs: List<JobDetail>,
    /** Per-file errors for items that could not be imported. */
    val errors: List<String>,
    /** How many files were imported. */
    val importedCount: Int,
    /** How many files failed. */
    val failedCount: Int,
)

/** Hardware and software encoders this device can use. */
@AppFunctionSerializable(isDescribedByKDoc = true)
data class DeviceEncodeCaps(
    /** True when a hardware H.264 encoder is available. */
    val hardwareH264: Boolean,
    /** True when a hardware HEVC encoder is available. */
    val hardwareHevc: Boolean,
    /** True when a hardware VP8 encoder is available. */
    val hardwareVp8: Boolean,
    /** True when a hardware VP9 encoder is available. */
    val hardwareVp9: Boolean,
    /** True when a hardware AV1 encoder is available. */
    val hardwareAv1: Boolean,
    /** True when FFmpeg libvpx (VP8) is available. */
    val softwareVp8: Boolean,
    /** True when FFmpeg libvpx-vp9 is available. */
    val softwareVp9: Boolean,
    /** True when FFmpeg libaom-av1 or libsvtav1 is available. */
    val softwareAv1: Boolean,
    /** True when FFmpeg Opus is available. */
    val opus: Boolean,
    /** True when FFmpeg OpenH264 is available. */
    val openH264: Boolean,
    /** How an agent should use these flags. */
    val note: String,
)

/** Probed source metadata for one job. Omits file URIs. */
@AppFunctionSerializable(isDescribedByKDoc = true)
data class SourceInfo(
    /** Job id. */
    val jobId: String,
    /** Display name of the source. */
    val displayName: String,
    /** Duration in milliseconds. */
    val durationMs: Long,
    /** Width in pixels. */
    val width: Int,
    /** Height in pixels. */
    val height: Int,
    /** Source size in bytes. */
    val sourceBytes: Long,
    /** Detected frame rate, or 0 if unknown. */
    val frameRate: Float,
    /** True when the source or soundtrack has audio. */
    val hasAudio: Boolean,
    /** True when the source has a video track or is a still image. */
    val hasVideo: Boolean,
    /** True when the visual is a still image. */
    val stillImage: Boolean,
    /** True when this job muxes a separate soundtrack. */
    val combine: Boolean,
    /** Audio codec name when known. */
    val audioCodec: String,
)

/** Result of requesting device library access. */
@AppFunctionSerializable(isDescribedByKDoc = true)
data class LibraryAccessResult(
    /** True when videos, audio, or photos can already be listed. */
    val granted: Boolean,
    /** What the agent should tell the user. */
    val message: String,
)
