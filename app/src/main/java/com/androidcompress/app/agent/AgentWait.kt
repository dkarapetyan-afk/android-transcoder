package com.androidcompress.app.agent

import com.androidcompress.app.data.JobStatus

object AgentWait {
    const val DEFAULT_TIMEOUT_SEC = 45
    const val MIN_TIMEOUT_SEC = 5
    const val MAX_TIMEOUT_SEC = 180

    fun clampTimeout(timeoutSec: Int): Int = timeoutSec.coerceIn(MIN_TIMEOUT_SEC, MAX_TIMEOUT_SEC)

    fun isTerminal(status: JobStatus): Boolean = when (status) {
        JobStatus.SUCCEEDED, JobStatus.FAILED, JobStatus.CANCELLED -> true
        else -> false
    }

    fun canWait(status: JobStatus): Boolean = when (status) {
        JobStatus.QUEUED, JobStatus.RUNNING -> true
        else -> false
    }
}
