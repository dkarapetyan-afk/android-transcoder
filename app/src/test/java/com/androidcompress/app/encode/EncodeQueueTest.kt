package com.androidcompress.app.encode

import com.androidcompress.app.data.CompressJob
import com.androidcompress.app.data.JobStatus
import com.androidcompress.app.data.JobType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EncodeQueueTest {

    @Test
    fun nextIsOldestQueued() {
        val jobs = listOf(
            job("ready", JobStatus.READY, queuedAt = 1),
            job("second", JobStatus.QUEUED, queuedAt = 20),
            job("first", JobStatus.QUEUED, queuedAt = 10),
            job("running", JobStatus.RUNNING, queuedAt = 5),
        )
        assertEquals("first", EncodeQueue.next(jobs)?.id)
    }

    @Test
    fun nextNullWhenNothingQueued() {
        assertNull(EncodeQueue.next(listOf(job("a", JobStatus.RUNNING, 1))))
    }

    @Test
    fun activePutsRunningFirstThenQueueOrder() {
        val jobs = listOf(
            job("q2", JobStatus.QUEUED, 20),
            job("run", JobStatus.RUNNING, 50),
            job("q1", JobStatus.QUEUED, 10),
            job("done", JobStatus.SUCCEEDED, 1),
        )
        assertEquals(listOf("run", "q1", "q2"), EncodeQueue.active(jobs).map { it.id })
    }

    @Test
    fun positionIsOneBased() {
        val jobs = listOf(
            job("run", JobStatus.RUNNING, 1),
            job("q1", JobStatus.QUEUED, 2),
        )
        assertEquals(1 to 2, EncodeQueue.position(jobs, "run"))
        assertEquals(2 to 2, EncodeQueue.position(jobs, "q1"))
        assertEquals(0 to 2, EncodeQueue.position(jobs, "missing"))
    }

    private fun job(id: String, status: JobStatus, queuedAt: Long) = CompressJob(
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
        createdAt = queuedAt,
        finishedAt = null,
        queuedAt = queuedAt,
    )
}
