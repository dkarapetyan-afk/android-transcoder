package com.androidcompress.app.media

import android.provider.MediaStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceMediaQueriesTest {
    @Test
    fun emptySpecHasNoSelection() {
        val (selection, args) = DeviceMediaQueries.selection(DeviceMediaQuerySpec())
        assertNull(selection)
        assertNull(args)
    }

    @Test
    fun buildsNamePathDateAndDurationFilters() {
        val (selection, args) = DeviceMediaQueries.selection(
            DeviceMediaQuerySpec(
                displayNameQuery = "clip",
                relativePath = "Download/",
                addedAfterEpochMs = 1_700_000_000_000L,
                minDurationMs = 1_000,
                maxDurationMs = 60_000,
            ),
        )
        requireNotNull(selection)
        requireNotNull(args)
        assertTrue(selection.contains(MediaStore.MediaColumns.DISPLAY_NAME))
        assertTrue(selection.contains(MediaStore.MediaColumns.RELATIVE_PATH))
        assertTrue(selection.contains(MediaStore.MediaColumns.DATE_ADDED))
        assertTrue(selection.contains(MediaStore.MediaColumns.DURATION))
        assertEquals("%clip%", args[0])
        assertEquals("%Download%", args[1])
        assertEquals("1700000000", args[2])
        assertEquals("1000", args[3])
        assertEquals("60000", args[4])
    }

    @Test
    fun skipsDurationAndPathWhenDisabled() {
        val (selection, args) = DeviceMediaQueries.selection(
            DeviceMediaQuerySpec(
                relativePath = "DCIM/Camera",
                minDurationMs = 5_000,
                includeDuration = false,
                includeRelativePath = false,
            ),
        )
        assertNull(selection)
        assertNull(args)
    }

    @Test
    fun normalizesRelativePath() {
        assertEquals("DCIM/Camera", DeviceMediaQueries.normalizeRelativePath(" /DCIM/Camera\\ "))
        assertEquals("", DeviceMediaQueries.normalizeRelativePath(null))
    }
}
