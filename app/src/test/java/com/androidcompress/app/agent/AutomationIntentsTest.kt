package com.androidcompress.app.agent

import com.androidcompress.app.data.AudioOption
import com.androidcompress.app.data.CompressJob
import com.androidcompress.app.data.ContainerFormat
import com.androidcompress.app.data.EncodeEngine
import com.androidcompress.app.data.EncodeSettings
import com.androidcompress.app.data.JobStatus
import com.androidcompress.app.data.JobType
import com.androidcompress.app.data.OutputMode
import com.androidcompress.app.data.Preset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AutomationIntentsTest {

    @Test
    fun uriPrefersExplicitExtras() {
        val extras = MapExtras(
            mapOf(
                AutomationIntents.EXTRA_URI to "content://media/1",
                AutomationIntents.EXTRA_PATH to "/sdcard/Download/clip.mp4",
            ),
        )
        assertEquals(
            "content://media/1",
            AutomationIntents.uriOrPath("content://data", "content://stream", extras),
        )
    }

    @Test
    fun uriFallsBackToDataThenStream() {
        assertEquals(
            "content://data",
            AutomationIntents.uriOrPath("content://data", "content://stream", MapExtras(emptyMap())),
        )
        assertEquals(
            "content://stream",
            AutomationIntents.uriOrPath(null, "content://stream", MapExtras(emptyMap())),
        )
        assertEquals(
            "/sdcard/Download/clip.mp4",
            AutomationIntents.uriOrPath(
                null,
                null,
                MapExtras(mapOf(AutomationIntents.EXTRA_PATH to "/sdcard/Download/clip.mp4")),
            ),
        )
    }

    @Test
    fun settingsPatchMapsTaskerStringExtras() {
        val extras = MapExtras(
            mapOf(
                AutomationIntents.EXTRA_PRESET to "smaller",
                AutomationIntents.EXTRA_ENGINE to "media3",
                AutomationIntents.EXTRA_CONTAINER to "webm",
                AutomationIntents.EXTRA_OUTPUT to "audio",
                AutomationIntents.EXTRA_TWO_PASS to "true",
                AutomationIntents.EXTRA_GRAYSCALE to 1,
                AutomationIntents.EXTRA_CAPTIONS to "yes",
                AutomationIntents.EXTRA_BURN_CAPTIONS to "true",
                AutomationIntents.EXTRA_CLIP_START_MS to "1000",
                AutomationIntents.EXTRA_CLIP_END_MS to 5_000L,
                AutomationIntents.EXTRA_DELETE_SOURCE_AFTER to "false",
            ),
        )
        val update = AutomationIntents.settingsUpdate(extras)
        val next = JobSettingsCodec.apply(
            EncodeSettings.forPreset(Preset.BALANCED),
            JobSettingsCodec.patchFromUpdate(update),
        )
        assertEquals(Preset.SMALLER, next.preset)
        assertEquals(EncodeEngine.MEDIA3, next.engine)
        assertEquals(ContainerFormat.WEBM, next.container)
        assertEquals(OutputMode.AUDIO, next.output)
        assertEquals(AudioOption.AAC_96, next.audio)
        assertTrue(next.twoPass)
        assertTrue(next.grayscale)
        assertTrue(next.captions)
        assertTrue(next.burnCaptions)
        assertEquals(1_000L, next.clipStartMs)
        assertEquals(5_000L, next.clipEndMs)
        assertFalse(AutomationIntents.deleteSourceAfter(extras))
    }

    @Test
    fun missingSettingsLeaveJobDefaults() {
        val update = AutomationIntents.settingsUpdate(MapExtras(emptyMap()))
        val next = JobSettingsCodec.apply(
            EncodeSettings.forPreset(Preset.BALANCED),
            JobSettingsCodec.patchFromUpdate(update),
        )
        assertEquals(EncodeSettings.forPreset(Preset.BALANCED), next)
    }

    @Test(expected = IllegalStateException::class)
    fun unknownPresetFailsFast() {
        AutomationIntents.settingsUpdate(MapExtras(mapOf(AutomationIntents.EXTRA_PRESET to "TINY")))
    }

    @Test
    fun compressWatchWaitsForTerminal() {
        val running = job(JobStatus.RUNNING)
        assertFalse(
            AutomationIntents.isWatchFinished(running, AutomationIntents.ACTION_COMPRESS, false),
        )
        assertTrue(
            AutomationIntents.isWatchFinished(
                running.copy(status = JobStatus.SUCCEEDED),
                AutomationIntents.ACTION_COMPRESS,
                false,
            ),
        )
    }

    @Test
    fun recordStopCompletesOnReadyUnlessAutoCompress() {
        val ready = job(JobStatus.READY)
        assertTrue(
            AutomationIntents.isWatchFinished(ready, AutomationIntents.ACTION_RECORD_STOP, false),
        )
        assertFalse(
            AutomationIntents.isWatchFinished(ready, AutomationIntents.ACTION_RECORD_STOP, true),
        )
        assertTrue(
            AutomationIntents.isWatchFinished(
                ready.copy(status = JobStatus.SUCCEEDED),
                AutomationIntents.ACTION_RECORD_STOP,
                true,
            ),
        )
    }

    @Test
    fun completionUsesOutputUriWhenPresent() {
        val job = job(JobStatus.SUCCEEDED).copy(
            outputUri = "content://media/out",
            outputBytes = 12_000L,
        )
        val done = AutomationIntents.completionForJob(
            AutomationIntents.ACTION_COMPRESS,
            "req-1",
            job,
            "ok",
        )
        assertEquals("COMPRESS", done.action)
        assertEquals("req-1", done.requestId)
        assertEquals(job.id, done.jobId)
        assertEquals("SUCCEEDED", done.status)
        assertEquals("content://media/out", done.outputUri)
        assertEquals(12_000L, done.outputBytes)
    }

    @Test
    fun recordReadyCompletionFallsBackToSourceUri() {
        val job = job(JobStatus.READY)
        val done = AutomationIntents.completionForJob(
            AutomationIntents.ACTION_RECORD_STOP,
            "",
            job,
            "ready",
        )
        assertEquals("RECORD_STOP", done.action)
        assertEquals("content://src", done.outputUri)
        assertNull(job.outputUri)
    }

    private fun job(status: JobStatus) = CompressJob(
        id = "job",
        type = JobType.IMPORT,
        status = status,
        sourceUri = "content://src",
        outputUri = null,
        displayName = "clip.mp4",
        sourceBytes = 1,
        outputBytes = null,
        durationMs = 1_000,
        width = 1920,
        height = 1080,
        settingsJson = "{}",
        error = null,
        createdAt = 1,
        finishedAt = null,
    )
}
