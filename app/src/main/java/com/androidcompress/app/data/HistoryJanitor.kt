package com.androidcompress.app.data

import com.androidcompress.app.encode.JobLogStore
import com.androidcompress.app.media.InputResolver

data class ClearHistoryResult(
    val removed: Int,
    val kept: Int,
) {
    fun message(): String = when {
        removed == 0 && kept > 0 -> "Nothing to clear. A recording in progress was left alone."
        kept > 0 -> "Cleared $removed job(s). Left a recording in progress."
        removed == 0 -> "Nothing to clear."
        else -> "Cleared $removed job(s) and cache files."
    }
}

class HistoryJanitor(
    private val jobs: JobRepository,
    private val logs: JobLogStore,
    private val inputs: InputResolver,
) {
    suspend fun prune(now: Long = System.currentTimeMillis()) {
        val all = jobs.listAll()
        val remove = JobHistoryPolicy.idsToDelete(all, now)
        for (id in remove) {
            deleteArtifacts(id)
            jobs.delete(id)
        }
        val keep = all.map { it.id }.toSet() - remove
        logs.prune(keepJobIds = keep, now = now)
    }

    suspend fun deleteJob(id: String) {
        deleteArtifacts(id)
        jobs.delete(id)
    }

    suspend fun clearHistory(): ClearHistoryResult {
        val all = jobs.listAll()
        val keepIds = JobHistoryPolicy.idsToKeepOnClear(all)
        val remove = JobHistoryPolicy.idsToClear(all)
        for (id in remove) {
            jobs.delete(id)
        }
        logs.clearExcept(keepIds)
        inputs.clearCacheExcept(keepIds)
        return ClearHistoryResult(removed = remove.size, kept = keepIds.size)
    }

    private fun deleteArtifacts(id: String) {
        logs.delete(id)
        inputs.deleteJobCache(id)
    }
}
