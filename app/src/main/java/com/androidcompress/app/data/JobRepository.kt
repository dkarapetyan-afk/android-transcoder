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
        dao.enqueueQueued(id, settingsJson, System.currentTimeMillis())
    }

    suspend fun cancelQueued(id: String) {
        dao.cancelQueued(id, System.currentTimeMillis())
    }

    suspend fun cancelAllQueued() = dao.cancelAllQueued(System.currentTimeMillis())

    suspend fun markSourceDeleted(id: String, deleted: Boolean) {
        dao.markSourceDeleted(id, deleted)
    }

    suspend fun updateStatus(
        id: String,
        status: JobStatus,
        error: String? = null,
        outputUri: String? = null,
        outputBytes: Long? = null,
        finished: Boolean = false,
    ) {
        dao.updateStatus(id, status, error, outputUri, outputBytes, finished, System.currentTimeMillis())
    }
}
