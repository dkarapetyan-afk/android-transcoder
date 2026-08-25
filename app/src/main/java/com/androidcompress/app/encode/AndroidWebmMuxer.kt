package com.androidcompress.app.encode

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.Metadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.muxer.BufferInfo
import androidx.media3.muxer.Muxer
import androidx.media3.muxer.MuxerException
import androidx.media3.muxer.SeekableMuxerOutput
import androidx.media3.muxer.WebmMuxer
import com.google.common.collect.ImmutableList
import java.nio.ByteBuffer

/**
 * Transformer factory that writes VP8/VP9/AV1 + Opus/Vorbis with Media3 [WebmMuxer].
 *
 * Media3 1.11 WebM muxing is incomplete: [WebmMuxer.addMetadataEntry] throws,
 * and [WebmMuxer] requires [Format.language] plus Opus CodecPrivate. Transformer
 * still emits those gaps, so this wrapper fills them in. Android MediaCodec's
 * Opus CSD is an `AOPUSHDR` blob; FFmpeg needs the inner RFC 7845 OpusHead.
 */
@OptIn(UnstableApi::class)
class AndroidWebmMuxerFactory : Muxer.Factory {
    override fun create(path: String): Muxer {
        return try {
            val muxer = WebmMuxer.Builder(SeekableMuxerOutput.of(path))
                .setSampleCopyEnabled(true)
                .build()
            CompatibleWebmMuxer(muxer)
        } catch (error: Exception) {
            throw MuxerException("Unable to create a WebM muxer", error)
        }
    }

    override fun getSupportedSampleMimeTypes(trackType: Int): ImmutableList<String> {
        return when (trackType) {
            C.TRACK_TYPE_VIDEO -> ImmutableList.of(
                MimeTypes.VIDEO_VP8,
                MimeTypes.VIDEO_VP9,
                MimeTypes.VIDEO_AV1,
            )
            C.TRACK_TYPE_AUDIO -> ImmutableList.of(MimeTypes.AUDIO_OPUS, MimeTypes.AUDIO_VORBIS)
            else -> ImmutableList.of()
        }
    }
}

@OptIn(UnstableApi::class)
private class CompatibleWebmMuxer(private val delegate: Muxer) : Muxer {
    override fun addTrack(format: Format): Int {
        val sanitized = sanitizeForWebm(format)
        return try {
            delegate.addTrack(sanitized)
        } catch (error: RuntimeException) {
            throw MuxerException(
                "WebM addTrack failed mime=${sanitized.sampleMimeType} lang=${sanitized.language} " +
                    "ch=${sanitized.channelCount} hz=${sanitized.sampleRate} " +
                    "csd=${sanitized.initializationData.size}",
                error,
            )
        }
    }

    override fun writeSampleData(trackId: Int, byteBuffer: ByteBuffer, bufferInfo: BufferInfo) {
        delegate.writeSampleData(trackId, byteBuffer, bufferInfo)
    }

    override fun addMetadataEntry(metadataEntry: Metadata.Entry) = Unit

    override fun close() = delegate.close()
}

@OptIn(UnstableApi::class)
internal fun sanitizeForWebm(format: Format): Format {
    val builder = format.buildUpon()
    var changed = false
    if (format.language == null) {
        builder.setLanguage(C.LANGUAGE_UNDETERMINED)
        changed = true
    }
    val mime = format.sampleMimeType
    if (mime != null && MimeTypes.isAudio(mime)) {
        val channels = if (format.channelCount > 0) format.channelCount else 2
        val sampleRate = if (format.sampleRate > 0) format.sampleRate else 48_000
        if (format.channelCount <= 0) {
            builder.setChannelCount(channels)
            changed = true
        }
        if (format.sampleRate <= 0) {
            builder.setSampleRate(sampleRate)
            changed = true
        }
        if (mime == MimeTypes.AUDIO_OPUS) {
            val current = format.initializationData.firstOrNull()
            val head = OpusCodecPrivate.rfcHead(current, channels, sampleRate)
            val already = format.initializationData.size == 1 && current != null && current.contentEquals(head)
            if (!already) {
                builder.setInitializationData(listOf(head))
                changed = true
            }
        }
    }
    return if (changed) builder.build() else format
}

internal fun opusIdentificationHeader(channelCount: Int, sampleRate: Int): ByteArray {
    val header = ByteArray(19)
    header[0] = 'O'.code.toByte()
    header[1] = 'p'.code.toByte()
    header[2] = 'u'.code.toByte()
    header[3] = 's'.code.toByte()
    header[4] = 'H'.code.toByte()
    header[5] = 'e'.code.toByte()
    header[6] = 'a'.code.toByte()
    header[7] = 'd'.code.toByte()
    header[8] = 1
    header[9] = channelCount.coerceIn(1, 8).toByte()
    header[10] = 0x38
    header[11] = 0x01
    val rate = if (sampleRate > 0) sampleRate else 48_000
    header[12] = (rate and 0xFF).toByte()
    header[13] = ((rate ushr 8) and 0xFF).toByte()
    header[14] = ((rate ushr 16) and 0xFF).toByte()
    header[15] = ((rate ushr 24) and 0xFF).toByte()
    return header
}
