package com.androidcompress.app.asr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class CaptionNoticeThrottleTest {

    @Test
    fun firstTickAlwaysPosts() {
        val throttle = CaptionNoticeThrottle(minIntervalMs = 200)
        val tick = throttle.accept(0L, 0.02f, "Downloading speech model")
        assertEquals(2, tick!!.percent)
        assertEquals("Downloading speech model", tick.message)
    }

    @Test
    fun samePercentAndMessageAreDropped() {
        val throttle = CaptionNoticeThrottle(minIntervalMs = 200)
        assertNotNull(throttle.accept(0L, 0.4f, "Transcribing"))
        assertNull(throttle.accept(50L, 0.4f, "Transcribing"))
    }

    @Test
    fun percentChangesWaitForInterval() {
        val throttle = CaptionNoticeThrottle(minIntervalMs = 200)
        assertEquals(40, throttle.accept(0L, 0.4f, "Transcribing")!!.percent)
        assertNull(throttle.accept(100L, 0.5f, "Transcribing"))
        assertEquals(50, throttle.accept(200L, 0.5f, "Transcribing")!!.percent)
    }

    @Test
    fun messageChangePostsImmediately() {
        val throttle = CaptionNoticeThrottle(minIntervalMs = 200)
        assertNotNull(throttle.accept(0L, 0.22f, "Extracting audio"))
        val next = throttle.accept(10L, 0.28f, "Transcribing")
        assertEquals("Transcribing", next!!.message)
        assertEquals(28, next.percent)
    }

    @Test
    fun completionPostsEvenInsideInterval() {
        val throttle = CaptionNoticeThrottle(minIntervalMs = 200)
        assertNotNull(throttle.accept(0L, 0.99f, "Muxing captions"))
        val done = throttle.accept(20L, 1f, "Muxing captions")
        assertEquals(100, done!!.percent)
    }

    @Test
    fun resetAllowsFirstTickAgain() {
        val throttle = CaptionNoticeThrottle(minIntervalMs = 200)
        assertNotNull(throttle.accept(0L, 0.5f, "Transcribing"))
        throttle.reset()
        assertNotNull(throttle.accept(10L, 0.02f, "Downloading speech model"))
    }
}
