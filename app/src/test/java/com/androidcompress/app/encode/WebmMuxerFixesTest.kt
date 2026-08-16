package com.androidcompress.app.encode

import org.junit.Assert.assertEquals
import org.junit.Test

class WebmMuxerFixesTest {
    @Test
    fun opusIdentificationHeaderIs19ByteOpusHead() {
        val header = opusIdentificationHeader(2, 48_000)
        assertEquals(19, header.size)
        assertEquals("OpusHead", header.copyOfRange(0, 8).toString(Charsets.US_ASCII))
        assertEquals(1, header[8].toInt())
        assertEquals(2, header[9].toInt())
        assertEquals(312, (header[10].toInt() and 0xFF) or ((header[11].toInt() and 0xFF) shl 8))
        val rate = (header[12].toInt() and 0xFF) or
            ((header[13].toInt() and 0xFF) shl 8) or
            ((header[14].toInt() and 0xFF) shl 16) or
            ((header[15].toInt() and 0xFF) shl 24)
        assertEquals(48_000, rate)
    }
}
