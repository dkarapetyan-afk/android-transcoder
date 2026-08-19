package com.androidcompress.app.encode

import android.media.MediaCodecList
import android.media.MediaFormat
import com.androidcompress.app.data.EncoderCapabilities

object MediaCodecEncoderCaps {
    fun detect(): EncoderCapabilities {
        return try {
            val list = MediaCodecList(MediaCodecList.REGULAR_CODECS)
            var h264 = false
            var hevc = false
            var vp8 = false
            var vp9 = false
            var av1 = false
            for (info in list.codecInfos) {
                if (!info.isEncoder) continue
                for (type in info.supportedTypes) {
                    if (type.equals(MediaFormat.MIMETYPE_VIDEO_AVC, ignoreCase = true)) h264 = true
                    if (type.equals(MediaFormat.MIMETYPE_VIDEO_HEVC, ignoreCase = true)) hevc = true
                    if (type.equals(MediaFormat.MIMETYPE_VIDEO_VP8, ignoreCase = true)) vp8 = true
                    if (type.equals(MediaFormat.MIMETYPE_VIDEO_VP9, ignoreCase = true)) vp9 = true
                    if (type.equals(AV1_MIME, ignoreCase = true)) av1 = true
                }
            }
            EncoderCapabilities(
                hasH264MediaCodec = h264,
                hasHevcMediaCodec = hevc,
                hasVp8MediaCodec = vp8,
                hasVp9MediaCodec = vp9,
                hasAv1MediaCodec = av1,
            )
        } catch (_: Throwable) {
            EncoderCapabilities()
        }
    }

    private const val AV1_MIME = "video/av01"
}
