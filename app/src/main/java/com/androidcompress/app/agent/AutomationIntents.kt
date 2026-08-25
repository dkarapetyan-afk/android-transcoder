package com.androidcompress.app.agent

import com.androidcompress.app.data.CompressJob
import com.androidcompress.app.data.JobStatus

/**
 * Tasker / MacroDroid / `am broadcast` contract.
 *
 * Inbound actions are received by [AutomationReceiver]. Completion is sent as
 * [ACTION_COMPLETED] (explicit to [EXTRA_REPLY_PACKAGE] when that extra was set).
 */
object AutomationIntents {
    const val ACTION_COMPRESS = "com.androidcompress.app.automation.COMPRESS"
    const val ACTION_RECORD_STOP = "com.androidcompress.app.automation.RECORD_STOP"
    const val ACTION_CANCEL_QUEUE = "com.androidcompress.app.automation.CANCEL_QUEUE"
    const val ACTION_COMPLETED = "com.androidcompress.app.automation.COMPLETED"

    const val EXTRA_URI = "uri"
    const val EXTRA_PATH = "path"
    const val EXTRA_FILE = "file"
    const val EXTRA_REQUEST_ID = "requestId"
    const val EXTRA_REPLY_PACKAGE = "replyPackage"
    const val EXTRA_DELETE_SOURCE_AFTER = "deleteSourceAfter"

    const val EXTRA_PRESET = "preset"
    const val EXTRA_ENGINE = "engine"
    const val EXTRA_OUTPUT = "output"
    const val EXTRA_CONTAINER = "container"
    const val EXTRA_CODEC = "codec"
    const val EXTRA_MAX_HEIGHT = "maxHeight"
    const val EXTRA_CLEAR_MAX_HEIGHT = "clearMaxHeight"
    const val EXTRA_FPS_CAP = "fpsCap"
    const val EXTRA_CLEAR_FPS_CAP = "clearFpsCap"
    const val EXTRA_PREFER_HARDWARE = "preferHardware"
    const val EXTRA_VIDEO_BITRATE_KBPS = "videoBitrateKbps"
    const val EXTRA_AUDIO = "audio"
    const val EXTRA_BITRATE_MODE = "bitrateMode"
    const val EXTRA_KEYFRAME_INTERVAL = "keyframeInterval"
    const val EXTRA_H264_PROFILE = "h264Profile"
    const val EXTRA_HDR_MODE = "hdrMode"
    const val EXTRA_AUDIO_VOLUME_PERCENT = "audioVolumePercent"
    const val EXTRA_FAST_START = "fastStart"
    const val EXTRA_B_FRAMES = "bFrames"
    const val EXTRA_FFMPEG_EXTRA_ARGS = "ffmpegExtraArgs"
    const val EXTRA_FFMPEG_COMMAND_OVERRIDE = "ffmpegCommandOverride"
    const val EXTRA_CLEAR_COMMAND_OVERRIDE = "clearCommandOverride"
    const val EXTRA_CLIP_START_MS = "clipStartMs"
    const val EXTRA_CLIP_END_MS = "clipEndMs"
    const val EXTRA_CLEAR_CLIP_END = "clearClipEnd"
    const val EXTRA_CLEAR_CLIP = "clearClip"
    const val EXTRA_TARGET_SIZE_PRESET = "targetSizePreset"
    const val EXTRA_TARGET_SIZE_BYTES = "targetSizeBytes"
    const val EXTRA_CLEAR_TARGET_SIZE = "clearTargetSize"
    const val EXTRA_TWO_PASS = "twoPass"
    const val EXTRA_GRAYSCALE = "grayscale"
    const val EXTRA_CAPTIONS = "captions"

    const val EXTRA_ACTION = "action"
    const val EXTRA_JOB_ID = "jobId"
    const val EXTRA_STATUS = "status"
    const val EXTRA_DISPLAY_NAME = "displayName"
    const val EXTRA_MESSAGE = "message"
    const val EXTRA_ERROR = "error"
    const val EXTRA_OUTPUT_URI = "outputUri"
    const val EXTRA_OUTPUT_BYTES = "outputBytes"
    const val EXTRA_DURATION_MS = "durationMs"
    const val EXTRA_TYPE = "type"
    const val EXTRA_COUNT = "count"

    fun uriOrPath(dataString: String?, streamUri: String?, extras: ExtraLookup): String? {
        return extras.string(EXTRA_URI)
            ?: extras.string(EXTRA_PATH)
            ?: extras.string(EXTRA_FILE)
            ?: dataString?.trim()?.takeIf { it.isNotEmpty() }
            ?: streamUri?.trim()?.takeIf { it.isNotEmpty() }
            ?: extras.string("android.intent.extra.TEXT")
    }

    fun requestId(extras: ExtraLookup): String = extras.string(EXTRA_REQUEST_ID).orEmpty()

    fun replyPackage(extras: ExtraLookup): String = extras.string(EXTRA_REPLY_PACKAGE).orEmpty()

    fun deleteSourceAfter(extras: ExtraLookup): Boolean = extras.boolean(EXTRA_DELETE_SOURCE_AFTER) == true

    fun settingsUpdate(extras: ExtraLookup): JobSettingsUpdate {
        return JobSettingsUpdate(
            preset = extras.string(EXTRA_PRESET)?.let { JobSettingsCodec.requirePreset(it).name },
            engine = extras.string(EXTRA_ENGINE)?.let { JobSettingsCodec.requireEngine(it).name },
            output = extras.string(EXTRA_OUTPUT)?.let {
                JobSettingsCodec.parseOutput(it)?.name
                    ?: error("Unknown output \"$it\". Use one of: ${JobSettingsCodec.outputs.joinToString()}")
            },
            container = extras.string(EXTRA_CONTAINER)?.let {
                JobSettingsCodec.parseContainer(it)?.name
                    ?: error("Unknown container \"$it\". Use one of: ${JobSettingsCodec.containers.joinToString()}")
            },
            codec = extras.string(EXTRA_CODEC)?.let {
                JobSettingsCodec.parseCodec(it)?.name
                    ?: error("Unknown codec \"$it\". Use one of: ${JobSettingsCodec.codecs.joinToString()}")
            },
            maxHeight = extras.int(EXTRA_MAX_HEIGHT),
            clearMaxHeight = extras.boolean(EXTRA_CLEAR_MAX_HEIGHT) == true,
            fpsCap = extras.int(EXTRA_FPS_CAP),
            clearFpsCap = extras.boolean(EXTRA_CLEAR_FPS_CAP) == true,
            preferHardware = extras.boolean(EXTRA_PREFER_HARDWARE),
            videoBitrateKbps = extras.int(EXTRA_VIDEO_BITRATE_KBPS),
            audio = extras.string(EXTRA_AUDIO)?.let {
                JobSettingsCodec.parseAudio(it)?.name
                    ?: error("Unknown audio \"$it\". Use one of: ${JobSettingsCodec.audioOptions.joinToString()}")
            },
            bitrateMode = extras.string(EXTRA_BITRATE_MODE)?.let {
                JobSettingsCodec.parseBitrateMode(it)?.name
                    ?: error("Unknown bitrateMode \"$it\". Use one of: ${JobSettingsCodec.bitrateModes.joinToString()}")
            },
            keyframeInterval = extras.string(EXTRA_KEYFRAME_INTERVAL)?.let {
                JobSettingsCodec.parseKeyframe(it)?.name
                    ?: error(
                        "Unknown keyframeInterval \"$it\". Use one of: ${JobSettingsCodec.keyframeIntervals.joinToString()}",
                    )
            },
            h264Profile = extras.string(EXTRA_H264_PROFILE)?.let {
                JobSettingsCodec.parseH264Profile(it)?.name
                    ?: error("Unknown h264Profile \"$it\". Use one of: ${JobSettingsCodec.h264Profiles.joinToString()}")
            },
            hdrMode = extras.string(EXTRA_HDR_MODE)?.let {
                JobSettingsCodec.parseHdr(it)?.name
                    ?: error("Unknown hdrMode \"$it\". Use one of: ${JobSettingsCodec.hdrModes.joinToString()}")
            },
            audioVolumePercent = extras.int(EXTRA_AUDIO_VOLUME_PERCENT),
            fastStart = extras.boolean(EXTRA_FAST_START),
            bFrames = extras.string(EXTRA_B_FRAMES)?.let {
                JobSettingsCodec.parseBFrames(it)?.name
                    ?: error("Unknown bFrames \"$it\". Use one of: ${JobSettingsCodec.bFrameSettings.joinToString()}")
            },
            ffmpegExtraArgs = extras.string(EXTRA_FFMPEG_EXTRA_ARGS),
            ffmpegCommandOverride = extras.string(EXTRA_FFMPEG_COMMAND_OVERRIDE),
            clearCommandOverride = extras.boolean(EXTRA_CLEAR_COMMAND_OVERRIDE) == true,
            clipStartMs = extras.long(EXTRA_CLIP_START_MS),
            clipEndMs = extras.long(EXTRA_CLIP_END_MS),
            clearClipEnd = extras.boolean(EXTRA_CLEAR_CLIP_END) == true,
            clearClip = extras.boolean(EXTRA_CLEAR_CLIP) == true,
            targetSizePreset = extras.string(EXTRA_TARGET_SIZE_PRESET)?.let {
                JobSettingsCodec.parseTargetSizePreset(it)?.name
                    ?: error(
                        "Unknown targetSizePreset \"$it\". Use one of: ${JobSettingsCodec.targetSizePresets.joinToString()}",
                    )
            },
            targetSizeBytes = extras.long(EXTRA_TARGET_SIZE_BYTES),
            clearTargetSize = extras.boolean(EXTRA_CLEAR_TARGET_SIZE) == true,
            twoPass = extras.boolean(EXTRA_TWO_PASS),
            grayscale = extras.boolean(EXTRA_GRAYSCALE),
            captions = extras.boolean(EXTRA_CAPTIONS),
        )
    }

    fun isWatchFinished(job: CompressJob, action: String, autoCompressAfterRecord: Boolean): Boolean {
        if (AgentWait.isTerminal(job.status)) return true
        return action == ACTION_RECORD_STOP &&
            job.status == JobStatus.READY &&
            !autoCompressAfterRecord
    }

    fun completionForJob(
        action: String,
        requestId: String,
        job: CompressJob,
        message: String,
    ): AutomationCompletion {
        val outputUri = job.outputUri?.takeIf { it.isNotBlank() } ?: job.sourceUri
        return AutomationCompletion(
            action = inboundName(action),
            requestId = requestId,
            jobId = job.id,
            status = job.status.name,
            displayName = job.displayName,
            message = message,
            error = job.error.orEmpty(),
            outputUri = outputUri,
            outputBytes = job.outputBytes ?: 0L,
            durationMs = job.durationMs,
            type = job.type.name,
            count = 1,
        )
    }

    fun completionFailed(
        action: String,
        requestId: String,
        error: String,
        jobId: String = "",
    ): AutomationCompletion = AutomationCompletion(
        action = inboundName(action),
        requestId = requestId,
        jobId = jobId,
        status = JobStatus.FAILED.name,
        displayName = "",
        message = error,
        error = error,
        outputUri = "",
        outputBytes = 0L,
        durationMs = 0L,
        type = "",
        count = 0,
    )

    fun completionCancelled(
        requestId: String,
        message: String,
        count: Int,
    ): AutomationCompletion = AutomationCompletion(
        action = inboundName(ACTION_CANCEL_QUEUE),
        requestId = requestId,
        jobId = "",
        status = JobStatus.CANCELLED.name,
        displayName = "",
        message = message,
        error = "",
        outputUri = "",
        outputBytes = 0L,
        durationMs = 0L,
        type = "",
        count = count,
    )

    fun inboundName(action: String): String = when (action) {
        ACTION_COMPRESS -> "COMPRESS"
        ACTION_RECORD_STOP -> "RECORD_STOP"
        ACTION_CANCEL_QUEUE -> "CANCEL_QUEUE"
        else -> action.substringAfterLast('.').ifBlank { action }
    }
}

data class AutomationCompletion(
    val action: String,
    val requestId: String,
    val jobId: String,
    val status: String,
    val displayName: String,
    val message: String,
    val error: String,
    val outputUri: String,
    val outputBytes: Long,
    val durationMs: Long,
    val type: String,
    val count: Int,
)

interface ExtraLookup {
    fun has(key: String): Boolean
    fun string(key: String): String?
    fun int(key: String): Int?
    fun long(key: String): Long?
    fun boolean(key: String): Boolean?
}

class MapExtras(private val values: Map<String, Any?>) : ExtraLookup {
    override fun has(key: String): Boolean = values.containsKey(key)

    override fun string(key: String): String? = stringify(values[key])

    override fun int(key: String): Int? = number(values[key])?.toInt()

    override fun long(key: String): Long? = number(values[key])

    override fun boolean(key: String): Boolean? = flag(values[key])

    companion object {
        fun stringify(raw: Any?): String? = when (raw) {
            null -> null
            is String -> raw.trim().takeIf { it.isNotEmpty() }
            else -> raw.toString().trim().takeIf { it.isNotEmpty() }
        }

        fun number(raw: Any?): Long? = when (raw) {
            null -> null
            is Number -> raw.toLong()
            is String -> raw.trim().toLongOrNull()
            else -> raw.toString().trim().toLongOrNull()
        }

        fun flag(raw: Any?): Boolean? = when (raw) {
            null -> null
            is Boolean -> raw
            is Number -> raw.toInt() != 0
            is String -> when (raw.trim().lowercase()) {
                "true", "1", "yes" -> true
                "false", "0", "no" -> false
                else -> null
            }
            else -> null
        }
    }
}
