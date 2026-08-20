package com.androidcompress.app.capture

import com.androidcompress.app.data.RecordAudioMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingStateTest {

    @Test
    fun elapsedIgnoresTimeWhilePaused() {
        val state = RecordingState(
            active = true,
            paused = true,
            startedAt = 10_000L,
            pausedAccumulatedMs = 2_000L,
            pauseStartedAt = 20_000L,
        )
        assertEquals(8_000L, state.elapsedMs(now = 22_000L))
    }

    @Test
    fun elapsedSubtractsAccumulatedPauseWhenRunning() {
        val state = RecordingState(
            active = true,
            startedAt = 10_000L,
            pausedAccumulatedMs = 3_000L,
        )
        assertEquals(7_000L, state.elapsedMs(now = 20_000L))
    }

    @Test
    fun elapsedIsZeroWhenInactive() {
        assertEquals(0L, RecordingState().elapsedMs(now = 50_000L))
    }

    @Test
    fun setPausedAccumulatesWallClock() {
        val store = RecordingStore()
        store.start("job")
        store.setPaused(true, now = 1_000L)
        assertTrue(store.state.value.paused)
        store.setPaused(false, now = 4_000L)
        assertFalse(store.state.value.paused)
        assertEquals(3_000L, store.state.value.pausedAccumulatedMs)
        assertEquals(0L, store.state.value.pauseStartedAt)
    }

    @Test
    fun markSavingClearsPause() {
        val store = RecordingStore()
        store.start("job")
        store.setPaused(true, now = 1_000L)
        store.markSaving()
        assertTrue(store.state.value.saving)
        assertFalse(store.state.value.paused)
        assertTrue(store.state.value.active)
        assertEquals(RecordPhase.SAVING, store.state.value.phase)
    }

    @Test
    fun prepareThenStartCapturingSetsElapsedClock() {
        val store = RecordingStore()
        store.prepare("job", RecordPhase.COUNTDOWN)
        assertTrue(store.state.value.active)
        assertEquals(0L, store.state.value.elapsedMs(now = 50_000L))
        store.startCapturing()
        assertEquals(RecordPhase.RECORDING, store.state.value.phase)
        assertTrue(store.state.value.startedAt > 0L)
        assertTrue(store.state.value.capturing)
    }

    @Test
    fun finishCanOpenResult() {
        val store = RecordingStore()
        store.finish("job", openResult = true, notice = "Stopped")
        assertEquals("job", store.state.value.finishedJobId)
        assertTrue(store.state.value.openResult)
        assertEquals("Stopped", store.state.value.notice)
        store.consumeFinished()
        assertEquals(null, store.state.value.finishedJobId)
        assertFalse(store.state.value.openResult)
    }

    @Test
    fun addBookmarkUsesElapsedAndDedupes() {
        val store = RecordingStore()
        store.start("job")
        val started = store.state.value.startedAt
        val first = store.addBookmark(now = started + 2_000L)
        assertEquals(2_000L, first)
        assertEquals(listOf(2_000L), store.state.value.bookmarks)
        store.addBookmark(now = started + 2_200L)
        assertEquals(1, store.state.value.bookmarks.size)
        store.addBookmark(now = started + 5_000L)
        assertEquals(listOf(2_000L, 5_000L), store.state.value.bookmarks)
    }
}

class RecordAudioModeTest {
    @Test
    fun bothUsesMicrophoneAndInternal() {
        assertTrue(RecordAudioMode.BOTH.usesMicrophone)
        assertTrue(RecordAudioMode.BOTH.usesInternalAudio)
        assertTrue(RecordAudioMode.BOTH.needsRecordAudioPermission)
        assertTrue(RecordAudioMode.MICROPHONE.usesMicrophone)
        assertFalse(RecordAudioMode.MICROPHONE.usesInternalAudio)
        assertTrue(RecordAudioMode.INTERNAL.usesInternalAudio)
        assertFalse(RecordAudioMode.INTERNAL.usesMicrophone)
        assertFalse(RecordAudioMode.NONE.needsRecordAudioPermission)
    }

    @Test
    fun resolveAudioModeDropsInternalBelowApi29() {
        assertEquals(RecordAudioMode.MICROPHONE, RecordAudioMode.BOTH.resolvedForSdk(28))
        assertEquals(RecordAudioMode.NONE, RecordAudioMode.INTERNAL.resolvedForSdk(28))
        assertEquals(RecordAudioMode.BOTH, RecordAudioMode.BOTH.resolvedForSdk(29))
        assertEquals(RecordAudioMode.MICROPHONE, RecordAudioMode.MICROPHONE.resolvedForSdk(26))
    }
}
