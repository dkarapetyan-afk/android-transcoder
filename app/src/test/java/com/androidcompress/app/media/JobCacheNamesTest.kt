package com.androidcompress.app.media

import org.junit.Assert.assertEquals
import org.junit.Test

class JobCacheNamesTest {
    @Test
    fun cacheJobIdStripsExtension() {
        assertEquals("abc", InputResolver.cacheJobId("abc.src"))
        assertEquals("abc", InputResolver.cacheJobId("abc.mp4"))
        assertEquals("abc", InputResolver.cacheJobId("abc.webm"))
        assertEquals("abc", InputResolver.cacheJobId("abc.wav"))
    }
}
