package com.androidcompress.app.encode

import java.io.File
import java.io.RandomAccessFile

/**
 * WebM Opus CodecPrivate helpers.
 *
 * RFC 7845 identification headers start with `OpusHead` and put version at
 * byte 8. Android MediaCodec instead writes a concatenated CSD blob:
 *
 * ```
 * AOPUSHDR + int64le size + OpusHead
 * AOPUSDLY + int64le size + delay
 * AOPUSPRL + int64le size + preroll
 * ```
 *
 * FFmpeg 8 reads `extradata[8]` as the Opus version, so that blob looks like
 * "Extradata version 19" (the AOPUSHDR payload size) and refuses to open the
 * stream. Media3 [androidx.media3.muxer.WebmMuxer] stores `initializationData[0]`
 * as CodecPrivate, so we unwrap it before writing and repair files already
 * muxed that way before handing them to FFmpeg.
 */
internal object OpusCodecPrivate {
    const val RFC_HEAD_SIZE = 19
    private const val HEADER_SCAN_BYTES = 1_048_576
    private val OPUS_HEAD = "OpusHead".toByteArray(Charsets.US_ASCII)
    private val AOPUS_HDR = "AOPUSHDR".toByteArray(Charsets.US_ASCII)

    fun isRfcOpusHead(data: ByteArray, offset: Int = 0): Boolean {
        if (data.size - offset < RFC_HEAD_SIZE) return false
        if (!data.matchesAt(offset, OPUS_HEAD)) return false
        val version = data[offset + 8].toInt() and 0xFF
        return version <= 15
    }

    fun unwrapAndroidCsd(csd: ByteArray): ByteArray? {
        if (!csd.matchesAt(0, AOPUS_HDR) || csd.size < 16 + RFC_HEAD_SIZE) return null
        val payloadSize = leU64(csd, 8)
        if (payloadSize < RFC_HEAD_SIZE || payloadSize > csd.size - 16L) return null
        val head = csd.copyOfRange(16, 16 + payloadSize.toInt())
        return head.takeIf { isRfcOpusHead(it) }
    }

    fun rfcHead(csd: ByteArray?, channelCount: Int, sampleRate: Int): ByteArray {
        if (csd != null) {
            if (isRfcOpusHead(csd)) return trimHead(csd)
            unwrapAndroidCsd(csd)?.let { return trimHead(it) }
        }
        return opusIdentificationHeader(channelCount, sampleRate)
    }

    /**
     * Overwrites an Android `AOPUSHDR` CodecPrivate in-place with the inner
     * RFC OpusHead so FFmpeg can copy the audio stream. Returns true when
     * bytes were changed.
     */
    fun repairWebmFile(file: File, maxHeaderBytes: Int = HEADER_SCAN_BYTES): Boolean {
        if (!file.isFile || file.length() < 16L + RFC_HEAD_SIZE) return false
        RandomAccessFile(file, "rw").use { raf ->
            val n = minOf(maxHeaderBytes.toLong(), file.length()).toInt()
            val buf = ByteArray(n)
            raf.readFully(buf)
            val pos = indexOf(buf, AOPUS_HDR)
            if (pos < 0 || buf.size - pos < 16 + RFC_HEAD_SIZE) return false
            val head = unwrapAndroidCsd(buf.copyOfRange(pos, buf.size)) ?: return false
            val rfc = trimHead(head)
            if (buf.matchesAt(pos, rfc)) return false
            raf.seek(pos.toLong())
            raf.write(rfc)
            return true
        }
    }

    private fun trimHead(data: ByteArray): ByteArray {
        val mapType = data[18].toInt() and 0xFF
        if (mapType == 0 && data.size > RFC_HEAD_SIZE) return data.copyOf(RFC_HEAD_SIZE)
        return data
    }

    private fun ByteArray.matchesAt(offset: Int, needle: ByteArray): Boolean {
        if (offset < 0 || offset + needle.size > size) return false
        for (i in needle.indices) {
            if (this[offset + i] != needle[i]) return false
        }
        return true
    }

    private fun leU64(data: ByteArray, offset: Int): Long {
        var value = 0L
        for (i in 0 until 8) {
            value = value or ((data[offset + i].toLong() and 0xFFL) shl (8 * i))
        }
        return value
    }

    private fun indexOf(hay: ByteArray, needle: ByteArray): Int {
        if (needle.isEmpty() || hay.size < needle.size) return -1
        outer@ for (i in 0..hay.size - needle.size) {
            for (j in needle.indices) {
                if (hay[i + j] != needle[j]) continue@outer
            }
            return i
        }
        return -1
    }
}
