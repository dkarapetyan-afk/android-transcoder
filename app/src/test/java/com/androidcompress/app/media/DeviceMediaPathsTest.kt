package com.androidcompress.app.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceMediaPathsTest {

    @Test
    fun stripsQuotesAndClassifies() {
        assertTrue(DeviceMediaPaths.looksLikeContent("content://media/external/video/media/12"))
        assertTrue(DeviceMediaPaths.looksLikeFileUri("file:///sdcard/Download/clip.mp4"))
        assertTrue(DeviceMediaPaths.looksLikeAbsolutePath("/sdcard/Download/clip.mp4"))
        assertEquals(
            "/sdcard/Download/clip.mp4",
            DeviceMediaPaths.filePath("\"/sdcard/Download/clip.mp4\""),
        )
    }

    @Test
    fun fileUriBecomesPath() {
        assertEquals("/sdcard/Download/clip.mp4", DeviceMediaPaths.filePath("file:///sdcard/Download/clip.mp4"))
        assertEquals("/sdcard/Download/clip.mp4", DeviceMediaPaths.filePath("file://sdcard/Download/clip.mp4"))
    }

    @Test
    fun displayNameAndRelativeHint() {
        val path = "/storage/emulated/0/Download/clip.mp4"
        assertEquals("clip.mp4", DeviceMediaPaths.displayNameOf(path))
        assertEquals("Download/", DeviceMediaPaths.relativeHint(path))
        assertEquals("DCIM/Camera/", DeviceMediaPaths.relativeHint("/sdcard/DCIM/Camera/VID_001.mp4"))
        assertNull(DeviceMediaPaths.relativeHint("clip.mp4"))
        assertNull(DeviceMediaPaths.filePath("clip.mp4"))
    }
}
