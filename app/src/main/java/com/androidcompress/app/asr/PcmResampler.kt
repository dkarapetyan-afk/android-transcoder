package com.androidcompress.app.asr

import java.io.OutputStream
import kotlin.math.floor

/**
 * Mixes interleaved PCM to mono and resamples to [WhisperModels.SAMPLE_RATE] with
 * linear interpolation. Used so captions can feed Whisper 16 kHz s16le regardless
 * of the decoder's native rate (Opus is 48 kHz).
 */
class PcmResampler(
    private val inRate: Int,
    private val channels: Int,
    private val outRate: Int = WhisperModels.SAMPLE_RATE,
) {
    init {
        require(inRate > 0) { "sample rate" }
        require(channels > 0) { "channels" }
        require(outRate > 0) { "output rate" }
    }

    private val step = inRate.toDouble() / outRate.toDouble()
    private val pending = ArrayList<Int>(16)
    private var nextOut = 0.0
    private var dropped = 0L

    fun feedS16(interleaved: ShortArray, frames: Int, dest: OutputStream) {
        val count = frames.coerceAtMost(interleaved.size / channels)
        for (frame in 0 until count) {
            pending += mix(interleaved, frame)
            drain(dest)
        }
    }

    fun feedS16Bytes(bytes: ByteArray, offset: Int, length: Int, dest: OutputStream) {
        val frameBytes = channels * 2
        if (frameBytes <= 0 || length < frameBytes) return
        val frames = length / frameBytes
        val shorts = ShortArray(frames * channels)
        var i = offset
        for (s in shorts.indices) {
            val lo = bytes[i].toInt() and 0xff
            val hi = bytes[i + 1].toInt()
            shorts[s] = ((hi shl 8) or lo).toShort()
            i += 2
        }
        feedS16(shorts, frames, dest)
    }

    fun feedF32Bytes(bytes: ByteArray, offset: Int, length: Int, dest: OutputStream) {
        val frameBytes = channels * 4
        if (frameBytes <= 0 || length < frameBytes) return
        val frames = length / frameBytes
        val shorts = ShortArray(frames * channels)
        var i = offset
        for (s in shorts.indices) {
            val bits = (bytes[i].toInt() and 0xff) or
                ((bytes[i + 1].toInt() and 0xff) shl 8) or
                ((bytes[i + 2].toInt() and 0xff) shl 16) or
                (bytes[i + 3].toInt() shl 24)
            val f = Float.fromBits(bits)
            shorts[s] = (f * 32767f).toInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                .toShort()
            i += 4
        }
        feedS16(shorts, frames, dest)
    }

    fun finish(dest: OutputStream) {
        if (pending.isEmpty()) return
        pending += pending.last()
        drain(dest)
    }

    private fun drain(dest: OutputStream) {
        while (true) {
            val abs = floor(nextOut).toLong()
            val idx = (abs - dropped).toInt()
            if (idx < 0 || idx + 1 >= pending.size) break
            val frac = nextOut - abs
            val y = pending[idx] * (1.0 - frac) + pending[idx + 1] * frac
            writeS16(y.toInt(), dest)
            nextOut += step
            val keepUntil = floor(nextOut).toLong()
            val drop = (keepUntil - dropped).toInt().coerceAtMost(pending.size - 1)
            if (drop > 0) {
                pending.subList(0, drop).clear()
                dropped += drop
            }
        }
    }

    private fun mix(interleaved: ShortArray, frame: Int): Int {
        if (channels == 1) return interleaved[frame].toInt()
        val base = frame * channels
        var sum = 0
        for (c in 0 until channels) {
            sum += interleaved[base + c].toInt()
        }
        return sum / channels
    }

    private fun writeS16(value: Int, dest: OutputStream) {
        val s = value.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
        dest.write(s and 0xff)
        dest.write((s ushr 8) and 0xff)
    }
}
