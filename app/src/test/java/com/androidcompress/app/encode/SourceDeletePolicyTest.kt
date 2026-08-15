package com.androidcompress.app.encode

import com.androidcompress.app.media.SourceDeletePolicy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceDeletePolicyTest {
    @Test
    fun skipsBlankSource() {
        assertTrue(SourceDeletePolicy.shouldSkip("", "content://out"))
    }

    @Test
    fun skipsWhenSourceIsTheOutput() {
        assertTrue(SourceDeletePolicy.shouldSkip("content://video/12", "content://video/12"))
        assertTrue(SourceDeletePolicy.shouldSkip("file:///cache/a.mp4", "/cache/a.mp4"))
    }

    @Test
    fun allowsDistinctFiles() {
        assertFalse(SourceDeletePolicy.shouldSkip("content://video/1", "content://video/2"))
        assertFalse(SourceDeletePolicy.shouldSkip("file:///cache/src.mp4", "content://media/out"))
    }
}
