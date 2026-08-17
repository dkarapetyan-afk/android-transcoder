package com.androidcompress.app.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShareIntentsTest {

    @Test
    fun ignoresLauncherAndUnknownActions() {
        assertTrue(
            ShareIntents.collectUriStrings(
                action = "android.intent.action.MAIN",
                stream = "content://video/1",
            ).isEmpty(),
        )
        assertFalse(ShareIntents.isIncomingAction("android.intent.action.MAIN"))
        assertTrue(ShareIntents.isIncomingAction("android.intent.action.SEND"))
        assertTrue(ShareIntents.isIncomingAction("android.intent.action.SEND_MULTIPLE"))
        assertTrue(ShareIntents.isIncomingAction("android.intent.action.VIEW"))
    }

    @Test
    fun extractsSingleSendStream() {
        assertEquals(
            listOf("content://media/video/12"),
            ShareIntents.collectUriStrings(
                action = "android.intent.action.SEND",
                stream = "content://media/video/12",
            ),
        )
    }

    @Test
    fun extractsMultipleStreamsAndDedupesClipData() {
        assertEquals(
            listOf("content://a", "content://b"),
            ShareIntents.collectUriStrings(
                action = "android.intent.action.SEND_MULTIPLE",
                stream = null,
                streams = listOf("content://a", "content://b"),
                clipUris = listOf("content://a"),
            ),
        )
    }

    @Test
    fun usesViewDataUri() {
        assertEquals(
            listOf("content://downloads/clip.mp4"),
            ShareIntents.collectUriStrings(
                action = "android.intent.action.VIEW",
                stream = null,
                data = "content://downloads/clip.mp4",
            ),
        )
    }

    @Test
    fun acceptsVideoAudioAndGenericTypes() {
        assertTrue(ShareIntents.isLikelyMedia("video/mp4"))
        assertTrue(ShareIntents.isLikelyMedia("audio/mpeg"))
        assertTrue(ShareIntents.isLikelyMedia("application/ogg"))
        assertTrue(ShareIntents.isLikelyMedia("*/*"))
        assertTrue(ShareIntents.isLikelyMedia(null))
        assertFalse(ShareIntents.isLikelyMedia("image/jpeg"))
        assertFalse(ShareIntents.isLikelyMedia("text/plain"))
        assertTrue(ShareIntents.isLikelyImage("image/jpeg"))
        assertTrue(ShareIntents.isLikelyShareItem("image/png"))
        assertFalse(ShareIntents.isLikelyImage("video/mp4"))
    }
}
