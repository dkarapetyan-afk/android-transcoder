package com.androidcompress.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EncodeLiveUpdateTest {

    @Test
    fun singleJobShowsPercentChip() {
        val live = EncodeLiveUpdate.create(42)
        assertEquals(42, live.percent)
        assertEquals(42, live.progress)
        assertEquals(100, live.progressMax)
        assertEquals("42%", live.chipText)
        assertFalse(live.indeterminate)
        assertEquals(1, live.segmentCount)
        assertNull(live.passSplitAt)
        assertTrue(live.chipText!!.length <= EncodeLiveUpdate.CHIP_MAX_CHARS)
    }

    @Test
    fun startIsIndeterminateWithoutChip() {
        val live = EncodeLiveUpdate.create(0)
        assertTrue(live.indeterminate)
        assertNull(live.chipText)
        assertEquals(0, live.progress)
    }

    @Test
    fun queueUsesOneHundredUnitsPerJob() {
        val live = EncodeLiveUpdate.create(percent = 40, queueIndex = 2, queueTotal = 3)
        assertEquals(40, live.percent)
        assertEquals(140, live.progress)
        assertEquals(300, live.progressMax)
        assertEquals(3, live.segmentCount)
        assertEquals("40%", live.chipText)
        assertFalse(live.indeterminate)
    }

    @Test
    fun twoPassMarksTheMidpointOfTheCurrentJob() {
        val first = EncodeLiveUpdate.create(10, twoPass = true)
        assertEquals(50, first.passSplitAt)
        assertTrue(first.twoPass)
        val secondJob = EncodeLiveUpdate.create(percent = 20, queueIndex = 2, queueTotal = 2, twoPass = true)
        assertEquals(150, secondJob.passSplitAt)
        assertEquals(120, secondJob.progress)
        assertEquals(200, secondJob.progressMax)
    }

    @Test
    fun clampsPercentAndQueueIndex() {
        val high = EncodeLiveUpdate.create(150)
        assertEquals(100, high.percent)
        assertEquals(100, high.progress)
        assertEquals("100%", high.chipText)
        val next = EncodeLiveUpdate.create(percent = 10, queueIndex = 9, queueTotal = 2)
        assertEquals(110, next.progress)
        assertEquals(200, next.progressMax)
    }
}
