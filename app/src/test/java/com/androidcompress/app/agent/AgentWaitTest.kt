package com.androidcompress.app.agent

import com.androidcompress.app.data.JobStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentWaitTest {
    @Test
    fun clampsTimeout() {
        assertEquals(5, AgentWait.clampTimeout(1))
        assertEquals(45, AgentWait.clampTimeout(45))
        assertEquals(180, AgentWait.clampTimeout(10_000))
    }

    @Test
    fun terminalAndWaitableStatuses() {
        assertTrue(AgentWait.isTerminal(JobStatus.SUCCEEDED))
        assertTrue(AgentWait.isTerminal(JobStatus.FAILED))
        assertTrue(AgentWait.isTerminal(JobStatus.CANCELLED))
        assertFalse(AgentWait.isTerminal(JobStatus.RUNNING))
        assertTrue(AgentWait.canWait(JobStatus.QUEUED))
        assertTrue(AgentWait.canWait(JobStatus.RUNNING))
        assertFalse(AgentWait.canWait(JobStatus.READY))
        assertFalse(AgentWait.canWait(JobStatus.SUCCEEDED))
    }
}
