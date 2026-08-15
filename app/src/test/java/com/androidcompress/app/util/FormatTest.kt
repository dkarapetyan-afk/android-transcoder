package com.androidcompress.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FormatTest {

    @Test
    fun parseDurationAcceptsSecondsAndClock() {
        assertEquals(10_000L, parseDurationMs("10"))
        assertEquals(90_000L, parseDurationMs("1:30"))
        assertEquals(3_661_000L, parseDurationMs("1:01:01"))
        assertEquals(1_500L, parseDurationMs("1.5"))
        assertNull(parseDurationMs(""))
        assertNull(parseDurationMs("nope"))
    }
}
