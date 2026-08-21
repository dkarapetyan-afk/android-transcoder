package com.androidcompress.app.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InputResolverStorageTest {

    @Test
    fun copyReserveIsSourcePlusOverhead() {
        val tenGb = 10L * 1024 * 1024 * 1024
        assertEquals(tenGb + InputResolver.STORAGE_OVERHEAD_BYTES, InputResolver.bytesNeededForCopy(tenGb))
        assertEquals(InputResolver.STORAGE_OVERHEAD_BYTES, InputResolver.bytesNeededForCopy(-1))
    }

    @Test
    fun encodeReserveUsesEstimatedOutputNotTwiceSource() {
        val tenGb = 10L * 1024 * 1024 * 1024
        val estimated = 1_500_000_000L
        val needed = InputResolver.bytesNeededForEncode(estimated, tenGb, durationMs = 3_600_000)
        val oldHeuristic = tenGb * 2 + InputResolver.STORAGE_OVERHEAD_BYTES
        assertEquals(estimated * 2 + InputResolver.STORAGE_OVERHEAD_BYTES, needed)
        assertTrue(needed < tenGb)
        assertTrue(needed < oldHeuristic / 4)
    }

    @Test
    fun encodeReserveFallsBackToSourceWhenDurationUnknown() {
        val tenGb = 10L * 1024 * 1024 * 1024
        val estimated = 400_000L
        val needed = InputResolver.bytesNeededForEncode(estimated, tenGb, durationMs = 0)
        assertEquals(tenGb * 2 + InputResolver.STORAGE_OVERHEAD_BYTES, needed)
    }
}
