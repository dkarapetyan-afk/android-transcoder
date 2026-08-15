package com.androidcompress.app.encode

import com.androidcompress.app.data.AudioOption
import com.androidcompress.app.data.BFrameSetting
import com.androidcompress.app.data.BitrateMode
import com.androidcompress.app.data.EncodeEngine
import com.androidcompress.app.data.EncodeSettings
import com.androidcompress.app.data.H264Profile
import com.androidcompress.app.data.HdrMode
import com.androidcompress.app.data.KeyframeInterval
import com.androidcompress.app.data.Preset
import com.androidcompress.app.data.SourceVideo
import com.androidcompress.app.data.VideoCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Media3EncodePlannerTest {

    private val source = SourceVideo(
        uri = "content://video",
        displayName = "clip.mp4",
        width = 1920,
        height = 1080,
        durationMs = 60_000,
        bytes = 80_000_000,
        frameRate = 60f,
        audioCodec = "aac",
        hasAudio = true,
    )

    @Test
    fun balancedH264UsesAvcAndScaledBitrate() {
        val spec = Media3EncodePlanner.plan(EncodeSettings.forPreset(Preset.BALANCED), source)
        assertEquals(Media3EncodePlanner.MIME_H264, spec.videoMimeType)
        assertEquals(1080, spec.outputHeight)
        assertEquals(1920, spec.outputWidth)
        assertEquals(2_500_000, spec.videoBitrateBps)
        assertEquals(128_000, spec.audioBitrateBps)
        assertFalse(spec.removeAudio)
        assertFalse(spec.remuxAudio)
        assertEquals("Media3 · H.264", spec.encoderLabel)
        assertEquals(30, spec.outputFps)
    }

    @Test
    fun hevcSelectsHevcMime() {
        val settings = EncodeSettings.forPreset(Preset.BALANCED).copy(
            codec = VideoCodec.HEVC,
            engine = EncodeEngine.MEDIA3,
        )
        val spec = Media3EncodePlanner.plan(settings, source)
        assertEquals(Media3EncodePlanner.MIME_HEVC, spec.videoMimeType)
        assertEquals("Media3 · HEVC", spec.encoderLabel)
    }

    @Test
    fun muteRemovesAudio() {
        val spec = Media3EncodePlanner.plan(
            EncodeSettings.forPreset(Preset.BALANCED).copy(audio = AudioOption.MUTE),
            source,
        )
        assertTrue(spec.removeAudio)
        assertEquals(0, spec.audioBitrateBps)
    }

    @Test
    fun copyRemuxesAudio() {
        val spec = Media3EncodePlanner.plan(
            EncodeSettings.forPreset(Preset.BALANCED).copy(audio = AudioOption.COPY),
            source,
        )
        assertFalse(spec.removeAudio)
        assertTrue(spec.remuxAudio)
        assertEquals(0, spec.audioBitrateBps)
    }

    @Test
    fun missingAudioIsMuted() {
        val spec = Media3EncodePlanner.plan(
            EncodeSettings.forPreset(Preset.BALANCED),
            source.copy(hasAudio = false),
        )
        assertTrue(spec.removeAudio)
    }

    @Test
    fun originalFpsWhenNoCap() {
        val spec = Media3EncodePlanner.plan(
            EncodeSettings.forPreset(Preset.HIGHER).copy(fpsCap = null),
            source,
        )
        assertEquals(0, spec.outputFps)
        assertEquals(60f, spec.originalFps)
    }

    @Test
    fun h264FallbackClearsHevc() {
        val spec = Media3EncodePlanner.plan(
            EncodeSettings.forPreset(Preset.BALANCED).copy(codec = VideoCodec.HEVC),
            source,
        )
        val fallback = Media3EncodePlanner.h264Fallback(spec)
        assertEquals(Media3EncodePlanner.MIME_H264, fallback?.videoMimeType)
        assertNull(Media3EncodePlanner.h264Fallback(fallback!!))
    }

    @Test
    fun advancedOptionsMap() {
        val spec = Media3EncodePlanner.plan(
            EncodeSettings.forPreset(Preset.BALANCED).copy(
                bitrateMode = BitrateMode.VBR,
                keyframeInterval = KeyframeInterval.SEC_5,
                h264Profile = H264Profile.MAIN,
                hdrMode = HdrMode.TONE_MAP,
                audioVolumePercent = 80,
                bFrames = BFrameSetting.ONE,
                audio = AudioOption.COPY,
            ),
            source,
        )
        assertFalse(spec.preferCbr)
        assertEquals(5f, spec.iFrameIntervalSeconds)
        assertEquals(H264Profile.MAIN, spec.h264Profile)
        assertTrue(spec.toneMapHdr)
        assertEquals(0.8f, spec.audioVolume)
        assertEquals(1, spec.maxBFrames)
        assertFalse(spec.remuxAudio)
        assertEquals(128_000, spec.audioBitrateBps)
    }
}
