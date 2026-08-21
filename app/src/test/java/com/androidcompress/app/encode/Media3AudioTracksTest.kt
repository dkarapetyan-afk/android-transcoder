package com.androidcompress.app.encode

import com.androidcompress.app.data.AudioOption
import com.androidcompress.app.data.EncodeSettings
import com.androidcompress.app.data.Preset
import com.androidcompress.app.data.SourceVideo
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Media3AudioTracksTest {

    private val source = SourceVideo(
        uri = "content://video",
        displayName = "clip.mp4",
        width = 1920,
        height = 1080,
        durationMs = 60_000,
        bytes = 80_000_000,
        frameRate = 30f,
        audioCodec = "aac",
        hasAudio = true,
    )

    @Test
    fun oneTrackUsesDefaultTransformerPath() {
        val spec = Media3EncodePlanner.plan(EncodeSettings.forPreset(Preset.BALANCED), source)
        assertFalse(Media3AudioTracks.shouldPreserveAll(spec, 0))
        assertFalse(Media3AudioTracks.shouldPreserveAll(spec, 1))
    }

    @Test
    fun twoTracksArePreserved() {
        val spec = Media3EncodePlanner.plan(EncodeSettings.forPreset(Preset.BALANCED), source)
        assertTrue(Media3AudioTracks.shouldPreserveAll(spec, 2))
        assertTrue(Media3AudioTracks.shouldPreserveAll(spec, 3))
    }

    @Test
    fun muteDropsEveryAudioTrack() {
        val spec = Media3EncodePlanner.plan(
            EncodeSettings.forPreset(Preset.BALANCED).copy(audio = AudioOption.MUTE),
            source,
        )
        assertFalse(Media3AudioTracks.shouldPreserveAll(spec, 2))
    }

    @Test
    fun combineUsesCompanionSoundtrackOnly() {
        val spec = Media3EncodePlanner.plan(
            EncodeSettings.forPreset(Preset.BALANCED),
            source.copy(audioUri = "content://audio/1"),
        )
        assertFalse(Media3AudioTracks.shouldPreserveAll(spec, 2))
    }

    @Test
    fun aacExtractStaysMp4EvenWhenOutputIsWebm() {
        assertFalse(MediaTrackMux.usesWebmContainer("audio/mp4a-latm", webmOutput = true))
        assertTrue(MediaTrackMux.usesWebmContainer("audio/opus", webmOutput = false))
        assertTrue(MediaTrackMux.usesWebmContainer("audio/webm", webmOutput = true))
    }
}
