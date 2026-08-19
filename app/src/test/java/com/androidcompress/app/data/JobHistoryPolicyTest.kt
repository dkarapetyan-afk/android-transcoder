package com.androidcompress.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class JobHistoryPolicyTest {

    private val now = 1_700_000_000_000L

    @Test
    fun dropsOldestWhenOverCap() {
        val jobs = (0 until 45).map { index ->
            job("j$index", JobStatus.SUCCEEDED, createdAt = now - index * 1_000L)
        }
        val removed = JobHistoryPolicy.idsToDelete(jobs, now)
        assertEquals(5, removed.size)
        assertTrue(removed.containsAll(listOf("j40", "j41", "j42", "j43", "j44")))
        assertFalse(removed.contains("j0"))
    }

    @Test
    fun neverDeletesActiveJobs() {
        val jobs = listOf(
            job("run", JobStatus.RUNNING, createdAt = now - JobHistoryPolicy.MAX_AGE_MS * 2),
            job("queue", JobStatus.QUEUED, createdAt = now - JobHistoryPolicy.MAX_AGE_MS * 2),
            job("rec", JobStatus.RECORDING, createdAt = now - JobHistoryPolicy.MAX_AGE_MS * 2),
            job("old", JobStatus.FAILED, createdAt = now - JobHistoryPolicy.MAX_AGE_MS * 2),
        )
        val removed = JobHistoryPolicy.idsToDelete(jobs, now)
        assertEquals(setOf("old"), removed)
    }

    @Test
    fun deletesFinishedJobsPastMaxAge() {
        val jobs = listOf(
            job("fresh", JobStatus.SUCCEEDED, createdAt = now - 1_000L),
            job("stale", JobStatus.CANCELLED, createdAt = now - JobHistoryPolicy.MAX_AGE_MS - 1),
        )
        assertEquals(setOf("stale"), JobHistoryPolicy.idsToDelete(jobs, now))
    }

    @Test
    fun keepsSmallRecentHistory() {
        val jobs = listOf(
            job("a", JobStatus.SUCCEEDED, createdAt = now),
            job("b", JobStatus.FAILED, createdAt = now - 1_000L),
        )
        assertTrue(JobHistoryPolicy.idsToDelete(jobs, now).isEmpty())
    }

    @Test
    fun clearAllRemovesHistoryButKeepsRecording() {
        val jobs = listOf(
            job("done", JobStatus.SUCCEEDED, createdAt = now),
            job("ready", JobStatus.READY, createdAt = now),
            job("run", JobStatus.RUNNING, createdAt = now),
            job("queue", JobStatus.QUEUED, createdAt = now),
            job("rec", JobStatus.RECORDING, createdAt = now),
        )
        assertEquals(setOf("rec"), JobHistoryPolicy.idsToKeepOnClear(jobs))
        assertEquals(setOf("done", "ready", "run", "queue"), JobHistoryPolicy.idsToClear(jobs))
    }

    @Test
    fun clearAllCounts() {
        assertEquals(0, ClearHistoryResult(0, 0).removed)
        assertEquals(0, ClearHistoryResult(0, 0).kept)
        assertEquals(3, ClearHistoryResult(3, 0).removed)
        assertEquals(1, ClearHistoryResult(2, 1).kept)
        assertEquals(1, ClearHistoryResult(0, 1).kept)
    }

    private fun job(id: String, status: JobStatus, createdAt: Long) = CompressJob(
        id = id,
        type = JobType.IMPORT,
        status = status,
        sourceUri = "content://$id",
        outputUri = null,
        displayName = id,
        sourceBytes = 1,
        outputBytes = null,
        durationMs = 1,
        width = 2,
        height = 2,
        settingsJson = "{}",
        error = null,
        createdAt = createdAt,
        finishedAt = createdAt,
    )
}
