package com.androidcompress.app.encode

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExtraArgsSanitizerTest {

    @Test
    fun acceptsFilterAndBitrate() {
        val parsed = ExtraArgsSanitizer.parse("-vf eq=contrast=1.1 -b:v 1800k")
        assertTrue(parsed.isValid)
        assertEquals(listOf("-vf", "eq=contrast=1.1", "-b:v", "1800k"), parsed.tokens)
    }

    @Test
    fun quotedFilterSurvives() {
        val parsed = ExtraArgsSanitizer.parse("-vf \"eq=contrast=1.1:brightness=0.02\"")
        assertTrue(parsed.isValid)
        assertEquals("eq=contrast=1.1:brightness=0.02", parsed.tokens[1])
    }

    @Test
    fun rejectsExtraInput() {
        val parsed = ExtraArgsSanitizer.parse("-i other.mp4")
        assertFalse(parsed.isValid)
        assertTrue(parsed.error!!.contains("-i"))
    }

    @Test
    fun rejectsPathsAndUrls() {
        assertFalse(ExtraArgsSanitizer.parse("-vf movie=/sdcard/x.mp4").isValid)
        assertFalse(ExtraArgsSanitizer.parse("-i https://example.com/v.mp4").isValid)
        assertFalse(ExtraArgsSanitizer.parse("-progress /sdcard/log.txt").isValid)
    }

    @Test
    fun rejectsOutputFile() {
        val parsed = ExtraArgsSanitizer.parse("out.mp4")
        assertFalse(parsed.isValid)
    }

    @Test
    fun insertsBeforeOutput() {
        val base = listOf("-y", "-i", "in.mp4", "-c:v", "h264_mediacodec", "out.mp4")
        val merged = ExtraArgsSanitizer.insert(base, "-vf hflip")
        assertEquals("out.mp4", merged.last())
        assertEquals(listOf("-vf", "hflip"), merged.takeLast(3).dropLast(1))
    }

    @Test
    fun rejectsPassFlags() {
        assertFalse(ExtraArgsSanitizer.parse("-pass 1").isValid)
        assertFalse(ExtraArgsSanitizer.parse("-passlogfile /tmp/x").isValid)
    }

    @Test
    fun emptyIsValid() {
        val parsed = ExtraArgsSanitizer.parse("   ")
        assertTrue(parsed.isValid)
        assertTrue(parsed.tokens.isEmpty())
    }

    @Test
    fun rejectsUnmatchedQuote() {
        assertFalse(ExtraArgsSanitizer.parse("-vf \"eq=contrast=1.1").isValid)
    }

    @Test
    fun stripsLeadingFfmpeg() {
        val parsed = ExtraArgsSanitizer.parse("ffmpeg -vf hflip")
        assertTrue(parsed.isValid)
        assertEquals(listOf("-vf", "hflip"), parsed.tokens)
    }
}
