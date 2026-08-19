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
            hasVp8MediaCodec = has("vp8_mediacodec"),
            hasVp9MediaCodec = has("vp9_mediacodec"),
            hasAv1MediaCodec = has("av1_mediacodec"),
            hasLibvpx = listing.contains("libvpx"),
            hasLibvpxVp9 = listing.contains("libvpx-vp9"),
            hasLibaomAv1 = listing.contains("libaom-av1") || has("libaom"),
            hasLibSvtAv1 = has("libsvtav1"),
            hasLibOpus = has("libopus") || has("opus"),
        )
    }
}
