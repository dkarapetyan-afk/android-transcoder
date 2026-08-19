package com.androidcompress.app.data

import android.content.Context
import com.androidcompress.app.R
import com.androidcompress.app.encode.JobLogStore
import com.androidcompress.app.media.InputResolver

data class ClearHistoryResult(
    val removed: Int,
    val kept: Int,
) {
    fun message(context: Context): String = when {
        removed == 0 && kept > 0 -> context.getString(R.string.history_cleared_none_recording)
        kept > 0 -> context.resources.getQuantityString(
            R.plurals.history_cleared_left_recording,
            removed,
            removed,
        )
        removed == 0 -> context.getString(R.string.history_cleared_none)
        else -> context.resources.getQuantityString(R.plurals.history_cleared, removed, removed)
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
