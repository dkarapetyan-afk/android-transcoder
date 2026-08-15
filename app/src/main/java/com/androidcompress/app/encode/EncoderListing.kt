package com.androidcompress.app.encode

import com.androidcompress.app.data.EncoderCapabilities

object EncoderListing {
    fun parse(listing: String): EncoderCapabilities {
        fun has(name: String) = listing.contains(Regex("""\b${Regex.escape(name)}\b"""))
        return EncoderCapabilities(
            hasH264MediaCodec = has("h264_mediacodec"),
            hasHevcMediaCodec = has("hevc_mediacodec"),
            hasOpenH264 = has("libopenh264"),
            hasMpeg4 = has("mpeg4") || listing.isBlank(),
        )
    }
}
