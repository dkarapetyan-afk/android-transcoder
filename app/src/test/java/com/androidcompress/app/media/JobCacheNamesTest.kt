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
        assertEquals("abc.mic", InputResolver.cacheJobId("abc.mic.wav"))
        assertEquals("abc", InputResolver.cacheJobId("abc.2pass-0.log"))
        assertEquals("abc", InputResolver.cacheJobId("abc.2pass-0.log.mbtree"))
    }

    @Test
    fun remapsCachedFileName() {
        assertEquals("new.src", InputResolver.remapCachedFileName("old.src", "old", "new"))
        assertEquals("new.mp4", InputResolver.remapCachedFileName("old.mp4", "old", "new"))
        assertEquals(null, InputResolver.remapCachedFileName("other.src", "old", "new"))
        assertEquals(null, InputResolver.remapCachedFileName("old.src", "old", "old"))
    }
}
