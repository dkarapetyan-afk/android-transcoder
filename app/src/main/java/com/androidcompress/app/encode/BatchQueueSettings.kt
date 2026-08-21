package com.androidcompress.app.encode

import com.androidcompress.app.data.AudioOption
import com.androidcompress.app.data.CompressJob
import com.androidcompress.app.data.ContainerFormat
import com.androidcompress.app.data.EncodeSettings
import com.androidcompress.app.data.JobStatus
import com.androidcompress.app.data.OutputMode
import com.androidcompress.app.data.Preset
import com.androidcompress.app.data.SettingsJson
import com.androidcompress.app.data.TargetSizePreset
import com.androidcompress.app.data.withContainer

data class BatchRecipe(
    val preset: Preset,
    val container: ContainerFormat? = null,
) {
    companion object {
        val SMALLER = BatchRecipe(Preset.SMALLER)
        val BALANCED = BatchRecipe(Preset.BALANCED)
        val HIGHER = BatchRecipe(Preset.HIGHER)
        val WEBM_720 = BatchRecipe(Preset.SMALLER, ContainerFormat.WEBM)
        val WEBM_1080 = BatchRecipe(Preset.BALANCED, ContainerFormat.WEBM)

        fun listed(): List<BatchRecipe> = listOf(SMALLER, BALANCED, HIGHER, WEBM_720, WEBM_1080)
    }
}

object BatchQueueSettings {
    fun eligible(job: CompressJob, queuedOnly: Boolean): Boolean {
        if (job.sourceUri.isBlank() || job.sourceDeleted) return false
        return if (queuedOnly) {
            job.status == JobStatus.QUEUED
        } else {
            job.status == JobStatus.QUEUED || job.status == JobStatus.READY
        }
    }

    fun targets(jobs: Iterable<CompressJob>, queuedOnly: Boolean): List<CompressJob> =
        jobs.filter { eligible(it, queuedOnly) }

    fun apply(job: CompressJob, recipe: BatchRecipe): CompressJob {
        val current = SettingsJson.decode(job.settingsJson)
        var next = EncodeSettings.forPreset(recipe.preset, current.engine).copy(
            bitrateMode = current.bitrateMode,
            keyframeInterval = current.keyframeInterval,
            h264Profile = current.h264Profile,
            hdrMode = current.hdrMode,
            audioVolumePercent = current.audioVolumePercent,
            fastStart = current.fastStart,
            bFrames = current.bFrames,
            ffmpegExtraArgs = current.ffmpegExtraArgs,
            ffmpegCommandOverride = current.ffmpegCommandOverride,
            twoPass = current.twoPass,
            clipStartMs = current.clipStartMs,
            clipEndMs = current.clipEndMs,
            output = current.output,
            targetSizePreset = TargetSizePreset.OFF,
            targetSizeBytes = null,
        ).withContainer(recipe.container ?: current.container)
        next = when {
            job.isCombine -> next.copy(
                output = OutputMode.VIDEO,
                audio = if (next.audio == AudioOption.MUTE) AudioOption.AAC_128 else next.audio,
            )
            job.width <= 0 && job.height <= 0 && !job.stillImage -> next.copy(
                output = OutputMode.AUDIO,
                audio = if (next.audio == AudioOption.MUTE) AudioOption.AAC_128 else next.audio,
            )
            else -> next
        }
        return job.copy(settingsJson = SettingsJson.encode(next))
    }
}
