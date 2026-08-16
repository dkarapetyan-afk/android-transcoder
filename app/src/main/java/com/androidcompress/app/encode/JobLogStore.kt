package com.androidcompress.app.encode

import android.content.Context
import com.androidcompress.app.data.JobHistoryPolicy
import java.io.File

class JobLogStore(context: Context) {
    private val dir = File(context.applicationContext.filesDir, "job-logs")
    private val lastFile = File(dir, "last-id.txt")

    fun write(jobId: String, text: String) {
        if (jobId.isBlank()) return
        dir.mkdirs()
        val clipped = if (text.length > MAX_CHARS) text.takeLast(MAX_CHARS) else text
        file(jobId).writeText(clipped)
        lastFile.writeText(jobId)
    }

    fun append(jobId: String, text: String) {
        val existing = read(jobId).orEmpty()
        write(jobId, if (existing.isBlank()) text else existing.trimEnd() + "\n" + text)
    }

    fun read(jobId: String): String? {
        if (jobId.isBlank()) return null
        val target = file(jobId)
        if (!target.exists()) return null
        return target.readText().ifBlank { null }
    }

    fun lastJobId(): String? = lastFile.takeIf { it.exists() }?.readText()?.trim()?.ifBlank { null }

    fun lastLog(): String? = lastJobId()?.let(::read)

    fun delete(jobId: String) {
        file(jobId).delete()
        if (lastJobId() == jobId) lastFile.delete()
    }

    fun clearExcept(keepJobIds: Set<String>) {
        val logs = dir.listFiles { candidate -> candidate.isFile && candidate.name.endsWith(".log") }.orEmpty()
        for (log in logs) {
            val id = log.name.removeSuffix(".log")
            if (id !in keepJobIds) log.delete()
        }
        val last = lastJobId()
        if (last == null || last !in keepJobIds || !file(last).exists()) {
            val newest = dir.listFiles { candidate -> candidate.isFile && candidate.name.endsWith(".log") }
                ?.maxByOrNull { it.lastModified() }
            if (newest == null) lastFile.delete() else lastFile.writeText(newest.name.removeSuffix(".log"))
        }
    }

    fun prune(
        keepJobIds: Set<String>,
        now: Long = System.currentTimeMillis(),
        maxAgeMs: Long = JobHistoryPolicy.MAX_AGE_MS,
        maxFiles: Int = JobHistoryPolicy.MAX_LOG_FILES,
    ) {
        val logs = dir.listFiles { candidate -> candidate.isFile && candidate.name.endsWith(".log") }.orEmpty()
        for (log in logs) {
            val id = log.name.removeSuffix(".log")
            val stale = now - log.lastModified() > maxAgeMs
            if (id !in keepJobIds || stale) log.delete()
        }
        val remaining = dir.listFiles { candidate -> candidate.isFile && candidate.name.endsWith(".log") }
            .orEmpty()
            .sortedByDescending { it.lastModified() }
        remaining.drop(maxFiles).forEach { it.delete() }
        val last = lastJobId()
        if (last != null && !file(last).exists()) {
            val newest = dir.listFiles { candidate -> candidate.isFile && candidate.name.endsWith(".log") }
                ?.maxByOrNull { it.lastModified() }
            if (newest == null) lastFile.delete() else lastFile.writeText(newest.name.removeSuffix(".log"))
        }
    }

    private fun file(jobId: String) = File(dir, "$jobId.log")

    companion object {
        const val MAX_CHARS = 512 * 1024
    }
}
