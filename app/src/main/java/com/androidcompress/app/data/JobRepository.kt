package com.androidcompress.app.data

import kotlinx.coroutines.flow.Flow

class JobRepository(private val dao: JobDao) {
    fun observeAll(): Flow<List<CompressJob>> = dao.observeAll()
    fun observe(id: String): Flow<CompressJob?> = dao.observe(id)
    suspend fun get(id: String): CompressJob? = dao.get(id)
    suspend fun listAll(): List<CompressJob> = dao.listAll()
    suspend fun upsert(job: CompressJob) = dao.upsert(job)
    suspend fun delete(id: String) = dao.delete(id)

    fun observeActive() = dao.observeActive()
    suspend fun nextQueued(): CompressJob? = dao.nextQueued()
    suspend fun listActive(): List<CompressJob> = dao.listActive()

    suspend fun enqueue(id: String, settingsJson: String? = null) {
        val current = dao.get(id) ?: return
        if (current.status == JobStatus.QUEUED || current.status == JobStatus.RUNNING) return
        dao.upsert(
            current.copy(
                status = JobStatus.QUEUED,
                settingsJson = settingsJson ?: current.settingsJson,
                queuedAt = System.currentTimeMillis(),
                error = null,
                finishedAt = null,
            ),
        )
    }

    suspend fun cancelQueued(id: String) {
        val current = dao.get(id) ?: return
        if (current.status == JobStatus.QUEUED) {
            dao.upsert(
                current.copy(
                    status = JobStatus.CANCELLED,
                    finishedAt = System.currentTimeMillis(),
                ),
            )
        }
    }

    suspend fun cancelAllQueued() = dao.cancelAllQueued(System.currentTimeMillis())

    suspend fun markSourceDeleted(id: String, deleted: Boolean) {
        val current = dao.get(id) ?: return
        dao.upsert(current.copy(sourceDeleted = deleted))
    }

    suspend fun updateStatus(
        id: String,
        status: JobStatus,
        error: String? = null,
        outputUri: String? = null,
        outputBytes: Long? = null,
        finished: Boolean = false,
    ) {
        val current = dao.get(id) ?: return
        dao.upsert(
            current.copy(
                status = status,
                error = error ?: current.error,
                outputUri = outputUri ?: current.outputUri,
                outputBytes = outputBytes ?: current.outputBytes,
                finishedAt = if (finished) System.currentTimeMillis() else current.finishedAt,
            ),
        )
    }
}
