package com.androidcompress.app.encode

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.muxer.Muxer
import androidx.media3.muxer.MuxerException
import androidx.media3.muxer.SeekableMuxerOutput
import androidx.media3.muxer.WebmMuxer
import com.google.common.collect.ImmutableList

/** Transformer factory that writes VP8/VP9 + Opus/Vorbis with Media3 [WebmMuxer]. */
@OptIn(UnstableApi::class)
class AndroidWebmMuxerFactory : Muxer.Factory {
    override fun create(path: String): Muxer {
        return try {
            WebmMuxer.Builder(SeekableMuxerOutput.of(path))
                .setSampleCopyEnabled(true)
                .build()
        } catch (error: Exception) {
            throw MuxerException("Unable to create a WebM muxer", error)
        }
    }

    override fun getSupportedSampleMimeTypes(trackType: Int): ImmutableList<String> {
        return when (trackType) {
            C.TRACK_TYPE_VIDEO -> ImmutableList.of(MimeTypes.VIDEO_VP8, MimeTypes.VIDEO_VP9)
            C.TRACK_TYPE_AUDIO -> ImmutableList.of(MimeTypes.AUDIO_OPUS, MimeTypes.AUDIO_VORBIS)
            else -> ImmutableList.of()
        }
    }
}
