package com.androidcompress.app.encode

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

class OpusCodecPrivateTest {

    @Test
    fun unwrapsAndroidAopusHdrBlob() {
        val blob = androidCsd()
        val head = OpusCodecPrivate.unwrapAndroidCsd(blob)
        requireNotNull(head)
        assertTrue(OpusCodecPrivate.isRfcOpusHead(head))
        assertEquals("OpusHead", head.copyOfRange(0, 8).toString(Charsets.US_ASCII))
        assertEquals(1, head[8].toInt())
        assertEquals(2, head[9].toInt())
        assertEquals(19, blob[8].toInt() and 0xFF)
    }

    @Test
    fun rfcHeadPrefersInnerOpusHeadOverGenerated() {
        val blob = androidCsd()
        val head = OpusCodecPrivate.rfcHead(blob, channelCount = 1, sampleRate = 16_000)
        assertArrayEquals(OpusCodecPrivate.unwrapAndroidCsd(blob), head)
        assertEquals(2, head[9].toInt())
    }

    @Test
    fun rfcHeadLeavesValidOpusHead() {
        val header = opusIdentificationHeader(1, 48_000)
        val out = OpusCodecPrivate.rfcHead(header, channelCount = 2, sampleRate = 48_000)
        assertArrayEquals(header, out)
    }

    @Test
    fun rfcHeadGeneratesWhenCsdMissing() {
        val out = OpusCodecPrivate.rfcHead(null, channelCount = 1, sampleRate = 48_000)
        assertTrue(OpusCodecPrivate.isRfcOpusHead(out))
        assertEquals(1, out[9].toInt())
    }

    @Test
    fun repairWebmOverwritesAopusHdrWithOpusHead() {
        val blob = androidCsd()
        val prefix = ByteArray(64) { 0x11 }
        val suffix = ByteArray(32) { 0x22 }
        val dir = createTempDirectory("opus-csd").toFile()
        val file = File(dir, "clip.webm")
        file.writeBytes(prefix + blob + suffix)
        assertTrue(OpusCodecPrivate.repairWebmFile(file))
        val patched = file.readBytes()
        val expected = prefix + opusIdentificationHeader(2, 48_000) + blob.copyOfRange(19, blob.size) + suffix
        assertArrayEquals(expected, patched)
        assertFalse(OpusCodecPrivate.repairWebmFile(file))
        dir.deleteRecursively()
    }

    @OptIn(UnstableApi::class)
    @Test
    fun sanitizeForWebmReplacesAopusHdrCodecPrivate() {
        val format = Format.Builder()
            .setSampleMimeType(MimeTypes.AUDIO_OPUS)
            .setChannelCount(2)
            .setSampleRate(48_000)
            .setLanguage(C.LANGUAGE_UNDETERMINED)
            .setInitializationData(listOf(androidCsd()))
            .build()
        val sanitized = sanitizeForWebm(format)
        assertEquals(1, sanitized.initializationData.size)
        assertTrue(OpusCodecPrivate.isRfcOpusHead(sanitized.initializationData[0]))
        assertFalse(sanitized.initializationData[0].copyOfRange(0, 8).contentEquals("AOPUSHDR".toByteArray()))
    }

    private fun androidCsd(): ByteArray {
        val head = opusIdentificationHeader(2, 48_000)
        val out = ByteArray(8 + 8 + head.size)
        "AOPUSHDR".toByteArray(Charsets.US_ASCII).copyInto(out)
        out[8] = 19
        head.copyInto(out, 16)
        return out
    }
}
