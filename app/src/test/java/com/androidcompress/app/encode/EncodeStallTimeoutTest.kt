package com.androidcompress.app.encode

import org.junit.Assert.assertEquals
import org.junit.Test

class EncodeStallTimeoutTest {
    @Test
    fun toMsUsesSecondsAsGiven() {
        assertEquals(20_000L, EncodeStallTimeout.toMs(20))
        assertEquals(120_000L, EncodeStallTimeout.toMs(120))
        assertEquals(1_000L, EncodeStallTimeout.toMs(1))
        assertEquals(10_000_000L, EncodeStallTimeout.toMs(10_000))
        assertEquals(300_000L, EncodeStallTimeout.toMs(300))
        assertEquals(600_000L, EncodeStallTimeout.toMs(600))
    }
}
