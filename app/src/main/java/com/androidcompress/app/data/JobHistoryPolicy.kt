package com.androidcompress.app.data

object JobHistoryPolicy {
    const val MAX_JOBS = 40
    const val MAX_AGE_MS = 30L * 24 * 60 * 60 * 1000
    const val MAX_LOG_FILES = 40

    fun isProtected(status: JobStatus): Boolean = when (status) {
        JobStatus.RUNNING, JobStatus.QUEUED, JobStatus.RECORDING -> true
        else -> false
    }

    fun idsToKeepOnClear(jobs: List<CompressJob>): Set<String> =
        jobs.filter { it.status == JobStatus.RECORDING }.map { it.id }.toSet()

    fun idsToClear(jobs: List<CompressJob>): Set<String> {
        val keep = idsToKeepOnClear(jobs)
        return jobs.map { it.id }.filterNotTo(mutableSetOf()) { it in keep }
    }

    fun idsToDelete(jobs: List<CompressJob>, now: Long = System.currentTimeMillis()): Set<String> {
        val protectedIds = jobs.filter { isProtected(it.status) }.map { it.id }.toSet()
        val deletable = jobs.filter { it.id !in protectedIds }.sortedByDescending { it.createdAt }
        val tooOld = deletable.filter { now - it.createdAt > MAX_AGE_MS }.map { it.id }.toSet()
        val overflow = deletable.filter { it.id !in tooOld }.drop(MAX_JOBS).map { it.id }.toSet()
        return tooOld + overflow
    }
}
