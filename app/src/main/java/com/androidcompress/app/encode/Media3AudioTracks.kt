package com.androidcompress.app.encode

/**
 * Transformer muxes one audio stream (track-type keyed). Isolated Voice +
 * System recordings need a separate extract → encode → mux pass.
 */
object Media3AudioTracks {
    fun shouldPreserveAll(spec: Media3EncodeSpec, audioTrackCount: Int): Boolean =
        audioTrackCount > 1 &&
            !spec.removeAudio &&
            spec.companionAudioUri.isNullOrBlank()
}
