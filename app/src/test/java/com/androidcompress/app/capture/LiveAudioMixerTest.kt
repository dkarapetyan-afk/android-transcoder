package com.androidcompress.app.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveAudioMixerTest {

    @Test
    fun duckEnvelopeDropsWhenMicIsLoud() {
        val duck = DuckEnvelope(threshold = 0.05f, ducked = 0.2f, attack = 1f, release = 1f)
        assertEquals(1f, duck.gain, 0.001f)
        val quiet = duck.processPeak(0.01f)
        assertEquals(1f, quiet, 0.05f)
        val loud = duck.processPeak(0.4f)
        assertTrue(loud < 0.5f)
    }

    @Test
    fun mixSumsStereoWithGain() {
        val mic = pcm(frames = 2, channels = 2, value = 1000)
        val internal = pcm(frames = 2, channels = 2, value = 2000)
        val dest = ByteArray(16)
        val bytes = LiveAudioMixer.mix(
            micBytes = mic,
            micRead = mic.size,
            micChannels = 2,
            intBytes = internal,
            intRead = internal.size,
            intChannels = 2,
            frames = 2,
            dest = dest,
            micGain = 1f,
            internalGain = 0.5f,
            duck = null,
        )
        assertEquals(8, bytes)
        assertEquals(2000, sample(dest, 0))
        assertEquals(2000, sample(dest, 2))
    }

    @Test
    fun mixUpliftsMonoMic() {
        val mic = pcm(frames = 1, channels = 1, value = 3000)
        val dest = ByteArray(8)
        LiveAudioMixer.mix(
            micBytes = mic,
            micRead = mic.size,
            micChannels = 1,
            intBytes = ByteArray(0),
            intRead = 0,
            intChannels = 0,
            frames = 1,
            dest = dest,
            micGain = 1f,
            internalGain = 1f,
            duck = null,
        )
        assertEquals(3000, sample(dest, 0))
        assertEquals(3000, sample(dest, 2))
    }

    @Test
    fun clamp16Limits() {
        assertEquals(32767, LiveAudioMixer.clamp16(80_000f))
        assertEquals(-32768, LiveAudioMixer.clamp16(-80_000f))
    }

    private fun pcm(frames: Int, channels: Int, value: Int): ByteArray {
        val out = ByteArray(frames * channels * 2)
        var i = 0
        repeat(frames * channels) {
            out[i++] = (value and 0xFF).toByte()
            out[i++] = ((value shr 8) and 0xFF).toByte()
        }
        return out
    }

    private fun sample(bytes: ByteArray, offset: Int): Int {
        val lo = bytes[offset].toInt() and 0xFF
        val hi = bytes[offset + 1].toInt()
        return ((hi shl 8) or lo).toShort().toInt()
    }
}
