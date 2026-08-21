package com.androidcompress.app.encode

import com.androidcompress.app.data.AudioOption
import com.androidcompress.app.data.CompressJob
import com.androidcompress.app.data.ContainerFormat
import com.androidcompress.app.data.EncodeEngine
import com.androidcompress.app.data.EncodeSettings
import com.androidcompress.app.data.JobStatus
import com.androidcompress.app.data.JobType
import com.androidcompress.app.data.OutputMode
import com.androidcompress.app.data.Preset
import com.androidcompress.app.data.SettingsJson
import com.androidcompress.app.data.TargetSizePreset
import com.androidcompress.app.data.VideoCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BatchQueueSettingsTest {

    @Test
    fun targetsReadyAndQueuedButNotRunning() {
        val jobs = listOf(
            job("ready", JobStatus.READY),
            job("queued", JobStatus.QUEUED),
            job("run", JobStatus.RUNNING),
            job("done", JobStatus.SUCCEEDED),
            job("blank", JobStatus.READY, sourceUri = ""),
        )
        assertEquals(
            listOf("ready", "queued"),
            BatchQueueSettings.targets(jobs, queuedOnly = false).map { it.id },
        )
        assertEquals(
            listOf("queued"),
            BatchQueueSettings.targets(jobs, queuedOnly = true).map { it.id },
        )
    }

    @Test
    fun webm720SetsVp9AndKeepsClip() {
        val settings = EncodeSettings.forPreset(Preset.HIGHER, EncodeEngine.MEDIA3).copy(
            clipStartMs = 1_000,
            clipEndMs = 5_000,
            twoPass = true,
            targetSizePreset = TargetSizePreset.DISCORD,
            targetSizeBytes = 10L shl 20,
        )
        val updated = BatchQueueSettings.apply(
            job("q", JobStatus.QUEUED, settingsJson = SettingsJson.encode(settings)),
            BatchRecipe.WEBM_720,
        )
        val next = SettingsJson.decode(updated.settingsJson)
        assertEquals(Preset.SMALLER, next.preset)
        assertEquals(720, next.maxHeight)
        assertEquals(ContainerFormat.WEBM, next.container)
        assertEquals(VideoCodec.VP9, next.codec)
        assertEquals(EncodeEngine.MEDIA3, next.engine)
        assertEquals(1_000L, next.clipStartMs)
        assertEquals(5_000L, next.clipEndMs)
        assertTrue(next.twoPass)
        assertEquals(TargetSizePreset.OFF, next.targetSizePreset)
        assertEquals(null, next.targetSizeBytes)
    }

    @Test
    fun audioOnlyStaysAudio() {
        val settings = EncodeSettings.forPreset(Preset.BALANCED).copy(output = OutputMode.AUDIO)
        val updated = BatchQueueSettings.apply(
            job("a", JobStatus.READY, width = 0, height = 0, settingsJson = SettingsJson.encode(settings)),
            BatchRecipe.SMALLER,
        )
        val next = SettingsJson.decode(updated.settingsJson)
        assertEquals(OutputMode.AUDIO, next.output)
        assertEquals(Preset.SMALLER, next.preset)
        assertFalse(next.audio == AudioOption.MUTE)
    }

    @Test
    fun combineStaysVideo() {
        val settings = EncodeSettings.forPreset(Preset.BALANCED).copy(audio = AudioOption.MUTE)
        val updated = BatchQueueSettings.apply(
            job(
                "c",
                JobStatus.QUEUED,
                type = JobType.COMBINE,
                audioUri = "content://audio",
                settingsJson = SettingsJson.encode(settings),
            ),
            BatchRecipe.WEBM_1080,
        )
        val next = SettingsJson.decode(updated.settingsJson)
        assertEquals(OutputMode.VIDEO, next.output)
        assertEquals(AudioOption.AAC_128, next.audio)
        assertEquals(1080, next.maxHeight)
        assertEquals(ContainerFormat.WEBM, next.container)
    }

    private fun job(
        id: String,
        status: JobStatus,
        width: Int = 1920,
        height: Int = 1080,
        sourceUri: String = "content://$id",
        type: JobType = JobType.IMPORT,
        audioUri: String = "",
        settingsJson: String = "{}",
    ) = CompressJob(
        id = id,
        type = type,
        status = status,
        sourceUri = sourceUri,
        outputUri = null,
        displayName = id,
        sourceBytes = 1,
        outputBytes = null,
        durationMs = 1_000,
        width = width,
        height = height,
        settingsJson = settingsJson,
        error = null,
        createdAt = 1,
        finishedAt = null,
        audioUri = audioUri,
    )
}
