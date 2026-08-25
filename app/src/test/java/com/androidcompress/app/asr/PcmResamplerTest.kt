package com.androidcompress.app.asr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

class PcmResamplerTest {

    @Test
    fun identity16kMonoKeepsSamples() {
        val pcm = shortArrayOf(100, 200, 300, 400, 500)
        val out = resample(pcm, inRate = 16_000, channels = 1)
        assertEquals(pcm.toList(), out.toList().take(pcm.size))
    }

    @Test
    fun stereoDownmixAveragesChannels() {
        val pcm = shortArrayOf(100, 300, 0, 0)
        val out = resample(pcm, inRate = 16_000, channels = 2)
        assertEquals(200, out[0].toInt())
        assertEquals(0, out[1].toInt())
    }

    @Test
    fun fortyEightKToSixteenKEmitsOneThird() {
        val frames = 48_000
        val pcm = ShortArray(frames) { 1_000 }
        val out = resample(pcm, inRate = 48_000, channels = 1)
        assertTrue(out.size in 15_900..16_100)
        assertEquals(1_000, out[0].toInt())
        assertEquals(1_000, out[out.size / 2].toInt())
    }

    @Test
    fun littleEndianRoundTrip() {
        val bytes = byteArrayOf(0x34, 0x12, 0x78.toByte(), 0x56)
        val dest = ByteArrayOutputStream()
        val resampler = PcmResampler(inRate = 16_000, channels = 1)
        resampler.feedS16Bytes(bytes, 0, bytes.size, dest)
        resampler.finish(dest)
        val out = toShorts(dest.toByteArray())
        assertEquals(0x1234, out[0].toInt() and 0xffff)
        assertEquals(0x5678, out[1].toInt() and 0xffff)
    }

    private fun resample(interleaved: ShortArray, inRate: Int, channels: Int): ShortArray {
        val dest = ByteArrayOutputStream()
        val resampler = PcmResampler(inRate = inRate, channels = channels)
        resampler.feedS16(interleaved, interleaved.size / channels, dest)
        resampler.finish(dest)
        return toShorts(dest.toByteArray())
    }

    private fun toShorts(bytes: ByteArray): ShortArray {
        val out = ShortArray(bytes.size / 2)
        for (i in out.indices) {
            val lo = bytes[i * 2].toInt() and 0xff
            val hi = bytes[i * 2 + 1].toInt()
            out[i] = ((hi shl 8) or lo).toShort()
        }
        return out
    }
}
