package com.androidcompress.app.media

import com.androidcompress.app.data.CompressJob
import com.androidcompress.app.data.JobStatus
import com.androidcompress.app.data.JobType

object LatestVideo {
    fun fromJobs(jobs: List<CompressJob>): CompressJob? =
        jobs.filter { job ->
            !job.sourceDeleted &&
                job.sourceUri.isNotBlank() &&
                job.status != JobStatus.RECORDING &&
                !job.stillImage &&
                (job.width > 0 || job.height > 0 || job.type == JobType.RECORD)
        }.maxByOrNull { it.createdAt }

    fun shorten(raw: String, maxChars: Int): String {
        val text = raw.trim()
        if (text.length <= maxChars) return text
        if (maxChars <= 1) return text.take(maxChars)
        return text.take(maxChars - 1) + "…"
    }
}
