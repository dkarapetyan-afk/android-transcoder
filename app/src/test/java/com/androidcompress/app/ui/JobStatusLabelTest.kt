package com.androidcompress.app.ui

import com.androidcompress.app.R
import com.androidcompress.app.data.JobStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class JobStatusLabelTest {
    @Test
    fun mapsEachStatusToAStringResource() {
        assertEquals(R.string.status_draft, JobStatus.DRAFT.labelRes())
        assertEquals(R.string.status_recording, JobStatus.RECORDING.labelRes())
        assertEquals(R.string.status_ready, JobStatus.READY.labelRes())
        assertEquals(R.string.status_queued, JobStatus.QUEUED.labelRes())
        assertEquals(R.string.status_running, JobStatus.RUNNING.labelRes())
        assertEquals(R.string.status_succeeded, JobStatus.SUCCEEDED.labelRes())
        assertEquals(R.string.status_failed, JobStatus.FAILED.labelRes())
        assertEquals(R.string.status_cancelled, JobStatus.CANCELLED.labelRes())
    }
}
