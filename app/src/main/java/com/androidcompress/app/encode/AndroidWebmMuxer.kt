package com.androidcompress.app.encode

import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Build
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.Metadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.muxer.BufferInfo
import androidx.media3.muxer.Muxer
import androidx.media3.muxer.MuxerException
import com.google.common.collect.ImmutableList
import java.nio.ByteBuffer

/**
 * [MediaMuxer] WebM wrapper. Media3 1.8 [DefaultMuxer] only opens MPEG-4.
 */
@OptIn(UnstableApi::class)
class AndroidWebmMuxerFactory : Muxer.Factory {
    override fun create(path: String): Muxer {
        val muxer = try {
            MediaMuxer(path, MediaMuxer.OutputFormat.MUXER_OUTPUT_WEBM)
        } catch (error: Exception) {
            throw MuxerException("Unable to create a WebM muxer", error)
        }
        return AndroidWebmMuxer(muxer)
    }

    override fun getSupportedSampleMimeTypes(trackType: Int): ImmutableList<String> {
        return when (trackType) {
            C.TRACK_TYPE_VIDEO -> ImmutableList.of(MimeTypes.VIDEO_VP8, MimeTypes.VIDEO_VP9)
            C.TRACK_TYPE_AUDIO -> {
                val audio = ImmutableList.Builder<String>().add(MimeTypes.AUDIO_VORBIS)
                if (Build.VERSION.SDK_INT >= 26) audio.add(MimeTypes.AUDIO_OPUS)
                audio.build()
            }
            else -> ImmutableList.of()
        }
    }
}

@OptIn(UnstableApi::class)
private class AndroidWebmMuxer(private val mediaMuxer: MediaMuxer) : Muxer {
    private var started = false
    private var released = false

    override fun addTrack(format: Format): Int {
        val mime = format.sampleMimeType
            ?: throw MuxerException("Missing sample MIME type", IllegalArgumentException("sampleMimeType"))
        val mediaFormat = if (MimeTypes.isVideo(mime)) {
            MediaFormat.createVideoFormat(mime, format.width, format.height).also { out ->
                if (format.rotationDegrees != 0) {
                    runCatching { mediaMuxer.setOrientationHint(format.rotationDegrees) }
                }
            }
        } else {
            MediaFormat.createAudioFormat(mime, format.sampleRate, format.channelCount)
        }
        format.initializationData.forEachIndexed { index, bytes ->
            mediaFormat.setByteBuffer("csd-$index", ByteBuffer.wrap(bytes))
        }
        return try {
            mediaMuxer.addTrack(mediaFormat)
        } catch (error: RuntimeException) {
            throw MuxerException("Failed to add WebM track ($mime)", error)
        }
    }

    override fun writeSampleData(trackId: Int, byteBuffer: ByteBuffer, bufferInfo: BufferInfo) {
        if (!started) start()
        val info = MediaCodec.BufferInfo()
        info.set(
            byteBuffer.position(),
            bufferInfo.size,
            bufferInfo.presentationTimeUs,
            mediaCodecFlags(bufferInfo.flags),
        )
        try {
            mediaMuxer.writeSampleData(trackId, byteBuffer, info)
        } catch (error: RuntimeException) {
            throw MuxerException("Failed to write WebM sample", error)
        }
    }

    override fun addMetadataEntry(metadataEntry: Metadata.Entry) = Unit

    override fun close() {
        if (released) return
        try {
            if (!started) start()
            mediaMuxer.stop()
        } catch (error: RuntimeException) {
            throw MuxerException("Failed to finish the WebM file", error)
        } finally {
            mediaMuxer.release()
            released = true
        }
    }

    private fun start() {
        try {
            mediaMuxer.start()
            started = true
        } catch (error: RuntimeException) {
            throw MuxerException("Failed to start the WebM muxer", error)
        }
    }

    private fun mediaCodecFlags(flags: Int): Int {
        var out = 0
        if (flags and C.BUFFER_FLAG_KEY_FRAME != 0) out = out or MediaCodec.BUFFER_FLAG_KEY_FRAME
        if (flags and C.BUFFER_FLAG_END_OF_STREAM != 0) out = out or MediaCodec.BUFFER_FLAG_END_OF_STREAM
        return out
    }
}
