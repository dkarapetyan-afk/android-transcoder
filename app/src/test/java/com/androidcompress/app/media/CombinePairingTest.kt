package com.androidcompress.app.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CombinePairingTest {

    @Test
    fun classifiesByMimeAndExtension() {
        assertEquals(MediaKind.IMAGE, CombinePairing.kind("image/jpeg", "a.jpg"))
        assertEquals(MediaKind.VIDEO, CombinePairing.kind("video/mp4", "a.mp4"))
        assertEquals(MediaKind.AUDIO, CombinePairing.kind("audio/mp4", "a.m4a"))
        assertEquals(MediaKind.IMAGE, CombinePairing.kind(null, "cover.webp"))
        assertEquals(MediaKind.AUDIO, CombinePairing.kind("application/ogg", "track.ogg"))
    }

    @Test
    fun pairsImageWithAudio() {
        val pair = CombinePairing.pairItems(listOf("img", "song")) { id ->
            if (id == "img") MediaKind.IMAGE else MediaKind.AUDIO
        }
        assertEquals("img" to "song", pair)
    }

    @Test
    fun pairsVideoWithAudio() {
        val pair = CombinePairing.pairItems(listOf("clip", "song")) { id ->
            if (id == "clip") MediaKind.VIDEO else MediaKind.AUDIO
        }
        assertEquals("clip" to "song", pair)
    }

    @Test
    fun doesNotPairTwoVideos() {
        assertNull(CombinePairing.pairItems(listOf("a", "b")) { MediaKind.VIDEO })
    }

    @Test
    fun oneVisualAndSeveralAudiosMakesOneJobEach() {
        val plan = CombinePairing.planItems(listOf("cover", "a", "b", "c")) { id ->
            if (id == "cover") MediaKind.IMAGE else MediaKind.AUDIO
        }
        assertEquals(
            listOf("cover" to "a", "cover" to "b", "cover" to "c"),
            plan.pairs,
        )
        assertEquals(emptyList<String>(), plan.leftovers)
    }

    @Test
    fun videoPlusSeveralAudiosMakesOneJobEach() {
        val plan = CombinePairing.planItems(listOf("clip", "a", "b")) { id ->
            if (id == "clip") MediaKind.VIDEO else MediaKind.AUDIO
        }
        assertEquals(listOf("clip" to "a", "clip" to "b"), plan.pairs)
    }

    @Test
    fun everyVisualPairsWithEveryAudio() {
        val plan = CombinePairing.planItems(listOf("v1", "v2", "a", "b")) { id ->
            if (id.startsWith("v")) MediaKind.VIDEO else MediaKind.AUDIO
        }
        assertEquals(
            listOf("v1" to "a", "v1" to "b", "v2" to "a", "v2" to "b"),
            plan.pairs,
        )
        assertEquals(emptyList<String>(), plan.leftovers)
    }

    @Test
    fun picturesAndVideosBothCrossWithAudio() {
        val plan = CombinePairing.planItems(listOf("cover", "clip", "song")) { id ->
            when (id) {
                "cover" -> MediaKind.IMAGE
                "clip" -> MediaKind.VIDEO
                else -> MediaKind.AUDIO
            }
        }
        assertEquals(listOf("cover" to "song", "clip" to "song"), plan.pairs)
    }

    @Test
    fun oneFileOrTwoVideosCannotCombine() {
        assertEquals(0, CombinePairing.planItems(listOf("clip")) { MediaKind.VIDEO }.pairs.size)
        assertEquals(0, CombinePairing.planItems(listOf("a", "b")) { MediaKind.VIDEO }.pairs.size)
        assertEquals(0, CombinePairing.planItems(listOf("song", "track")) { MediaKind.AUDIO }.pairs.size)
    }

    @Test
    fun stillUsesAudioDuration() {
        assertEquals(12_000L, CombinePairing.outputDurationMs(0, 12_000, stillImage = true))
        assertEquals(8_000L, CombinePairing.outputDurationMs(8_000, 20_000, stillImage = false))
    }
}
