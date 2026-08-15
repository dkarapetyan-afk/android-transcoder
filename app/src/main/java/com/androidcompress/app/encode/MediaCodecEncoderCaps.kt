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
            for (info in list.codecInfos) {
                if (!info.isEncoder) continue
                for (type in info.supportedTypes) {
                    if (type.equals(MediaFormat.MIMETYPE_VIDEO_AVC, ignoreCase = true)) h264 = true
                    if (type.equals(MediaFormat.MIMETYPE_VIDEO_HEVC, ignoreCase = true)) hevc = true
                }
            }
            EncoderCapabilities(hasH264MediaCodec = h264, hasHevcMediaCodec = hevc)
        } catch (_: Throwable) {
            EncoderCapabilities()
        }
    }
}
