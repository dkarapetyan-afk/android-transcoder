package com.androidcompress.app.media

import com.androidcompress.app.agent.AgentLaunch
import com.androidcompress.app.data.CompressJob
import com.androidcompress.app.data.JobStatus
import com.androidcompress.app.data.JobType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LatestVideoTest {

    @Test
    fun prefersNewestVideoJobAndSkipsAudioAndDeleted() {
        val audio = job("audio", createdAt = 30, width = 0, height = 0, type = JobType.IMPORT)
        val deleted = job("gone", createdAt = 40, sourceDeleted = true)
        val older = job("old", createdAt = 10)
        val newest = job("new", createdAt = 20)
        val recording = job("live", createdAt = 50, status = JobStatus.RECORDING, sourceUri = "")
        assertEquals(
            "new",
            LatestVideo.fromJobs(listOf(recording, deleted, audio, newest, older))?.id,
        )
    }

    @Test
    fun treatsRecordJobsAsVideo() {
        val record = job("rec", type = JobType.RECORD, width = 0, height = 0)
        assertEquals("rec", LatestVideo.fromJobs(listOf(record))?.id)
    }

    @Test
    fun shortensLabels() {
        assertEquals("clip.mp4", LatestVideo.shorten("clip.mp4", 10))
        assertEquals("night_rec…", LatestVideo.shorten("night_recording.mp4", 10))
        assertEquals("", LatestVideo.shorten("  ", 10))
    }

    @Test
    fun shortcutActionsMapToDestinations() {
        assertEquals(
            AgentLaunch.OPEN_RECORD,
            AppShortcuts.destinationFrom(AppShortcuts.ACTION_RECORD, null),
        )
        assertEquals(
            AgentLaunch.OPEN_COMPRESS_LATEST,
            AppShortcuts.destinationFrom(AppShortcuts.ACTION_COMPRESS_LATEST, null),
        )
        assertEquals(
            AgentLaunch.OPEN_EXTRACT_AUDIO,
            AppShortcuts.destinationFrom(AppShortcuts.ACTION_EXTRACT_AUDIO, null),
        )
        assertEquals(
            AgentLaunch.OPEN_SETTINGS,
            AppShortcuts.destinationFrom("android.intent.action.MAIN", AgentLaunch.OPEN_SETTINGS),
        )
        assertNull(AppShortcuts.destinationFrom("android.intent.action.MAIN", null))
        assertEquals(AppShortcuts.ID_RECORD, AppShortcuts.shortcutIdFor(AgentLaunch.OPEN_RECORD))
        assertEquals(AppShortcuts.ID_COMPRESS_LATEST, AppShortcuts.shortcutIdFor(AgentLaunch.OPEN_COMPRESS_LATEST))
        assertEquals(AppShortcuts.ID_EXTRACT_AUDIO, AppShortcuts.shortcutIdFor(AgentLaunch.OPEN_EXTRACT_AUDIO))
    }

    private fun job(
        id: String,
        createdAt: Long = 1,
        width: Int = 1920,
        height: Int = 1080,
        type: JobType = JobType.IMPORT,
        status: JobStatus = JobStatus.READY,
        sourceUri: String = "content://$id",
        sourceDeleted: Boolean = false,
    ) = CompressJob(
        id = id,
        type = type,
        status = status,
        sourceUri = sourceUri,
        outputUri = null,
        displayName = "$id.mp4",
        sourceBytes = 1,
        outputBytes = null,
        durationMs = 1_000,
        width = width,
        height = height,
        settingsJson = "{}",
        error = null,
        createdAt = createdAt,
        finishedAt = null,
        sourceDeleted = sourceDeleted,
    )
}
