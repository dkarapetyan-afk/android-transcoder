package com.androidcompress.app.data

import com.androidcompress.app.encode.JobLogStore
import com.androidcompress.app.media.InputResolver

class HistoryJanitor(
    private val jobs: JobRepository,
    private val logs: JobLogStore,
    private val inputs: InputResolver,
) {
    suspend fun prune(now: Long = System.currentTimeMillis()) {
        val all = jobs.listAll()
        val remove = JobHistoryPolicy.idsToDelete(all, now)
        for (id in remove) {
            logs.delete(id)
            inputs.deleteImportCopy(id)
            jobs.delete(id)
        }
        val keep = all.map { it.id }.toSet() - remove
        logs.prune(keepJobIds = keep, now = now)
    }
}
