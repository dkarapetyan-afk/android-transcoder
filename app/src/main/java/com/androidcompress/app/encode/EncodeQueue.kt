package com.androidcompress.app.encode

import com.androidcompress.app.data.CompressJob
import com.androidcompress.app.data.JobStatus

object EncodeQueue {
    fun next(jobs: Iterable<CompressJob>): CompressJob? =
        jobs.filter { it.status == JobStatus.QUEUED }
            .minWithOrNull(
                compareBy<CompressJob> { it.queuedAt ?: Long.MAX_VALUE }.thenBy { it.createdAt },
            )

    fun active(jobs: Iterable<CompressJob>): List<CompressJob> =
        jobs.filter { it.status == JobStatus.QUEUED || it.status == JobStatus.RUNNING }
            .sortedWith(
                compareBy<CompressJob> { if (it.status == JobStatus.RUNNING) 0 else 1 }
                    .thenBy { it.queuedAt ?: Long.MAX_VALUE }
                    .thenBy { it.createdAt },
            )

    fun position(jobs: Iterable<CompressJob>, jobId: String): Pair<Int, Int> {
        val items = active(jobs)
        val index = items.indexOfFirst { it.id == jobId }
        return (if (index >= 0) index + 1 else 0) to items.size
    }
}
