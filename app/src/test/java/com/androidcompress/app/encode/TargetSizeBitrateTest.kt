package com.androidcompress.app.encode

import com.androidcompress.app.data.AudioOption
import com.androidcompress.app.data.BitrateMode
import com.androidcompress.app.data.EncodeEngine
import com.androidcompress.app.data.EncodeSettings
import com.androidcompress.app.data.EncoderCapabilities
import com.androidcompress.app.data.H264Profile
import com.androidcompress.app.data.OutputMode
import com.androidcompress.app.data.Preset
import com.androidcompress.app.data.SourceVideo
import com.androidcompress.app.data.TargetSizePreset
import com.androidcompress.app.data.VideoCodec
import com.androidcompress.app.data.hasTargetSize
import com.androidcompress.app.data.withTargetPreset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.roundToInt

class TargetSizeBitrateTest {

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

    private val caps = EncoderCapabilities(hasOpenH264 = true, hasMpeg4 = true)

    @Test
    fun formulaSubtractsAudioAndMuxerOverhead() {
        val target = 10L shl 20
        val durationMs = 60_000L
        val audioKbps = 128
        val seconds = 60.0
        val totalBps = target * 8.0 / seconds
        val muxer = TargetSizeBitrate.MUXER_OVERHEAD_BYTES * 8.0 / seconds +
            totalBps * TargetSizeBitrate.OVERSHOOT_RATIO
        val expected = ((totalBps - audioKbps * 1000.0 - muxer) / 1000.0)
            .roundToInt()
            .coerceIn(TargetSizeBitrate.MIN_VIDEO_KBPS, TargetSizeBitrate.MAX_VIDEO_KBPS)
        assertEquals(expected, TargetSizeBitrate.videoKbps(target, durationMs, audioKbps))
        assertEquals(1206, TargetSizeBitrate.videoKbps(target, durationMs, audioKbps))
    }

    @Test
    fun shorterClipGetsHigherVideoBitrate() {
        val target = 10L shl 20
        val minute = TargetSizeBitrate.videoKbps(target, 60_000, 128)
        val half = TargetSizeBitrate.videoKbps(target, 30_000, 128)
        assertTrue(half > minute)
        assertTrue(half in 2_000..3_000)
    }

    @Test
    fun muteLeavesMoreBudgetForVideo() {
        val target = 10L shl 20
        val withAudio = TargetSizeBitrate.videoKbps(target, 60_000, 128)
        val mute = TargetSizeBitrate.videoKbps(target, 60_000, 0)
        assertTrue(mute > withAudio)
    }

    @Test
    fun ffmpegAndMedia3UseTheSameFitToSizeBitrate() {
        val settings = EncodeSettings.forPreset(Preset.BALANCED).withTargetPreset(TargetSizePreset.DISCORD)
        val ffmpeg = FfmpegCommandBuilder.build("in.mp4", "out.mp4", settings, source, caps)
        val media3 = Media3EncodePlanner.plan(settings, source)
        val expected = TargetSizeBitrate.videoKbps(
            TargetSizePreset.DISCORD.bytes!!,
            source.durationMs,
            128,
        )
        assertEquals(expected, ffmpeg.videoBitrateKbps)
        assertEquals("${expected}k", ffmpeg.args[ffmpeg.args.indexOf("-b:v") + 1])
        assertTrue(ffmpeg.args.contains("-maxrate"))
        assertEquals(expected * 1000, media3.videoBitrateBps)
        assertTrue(media3.preferCbr)
        assertEquals(128_000, media3.audioBitrateBps)
        val vbr = settings.copy(bitrateMode = BitrateMode.VBR)
        assertTrue(FfmpegCommandBuilder.build("in.mp4", "out.mp4", vbr, source, caps).args.contains("-maxrate"))
        assertTrue(Media3EncodePlanner.plan(vbr, source).preferCbr)
    }

    @Test
    fun estimateStaysAtOrUnderTheCap() {
        val settings = EncodeSettings.forPreset(Preset.BALANCED).withTargetPreset(TargetSizePreset.DISCORD)
        val estimate = FfmpegCommandBuilder.estimateOutputBytes(source, settings)
        assertTrue(estimate <= TargetSizePreset.DISCORD.bytes!!)
        assertTrue(estimate > TargetSizePreset.DISCORD.bytes!! * 9 / 10)
    }

    @Test
    fun clipWindowDrivesFitToSizeBitrate() {
        val settings = EncodeSettings.forPreset(Preset.BALANCED)
            .withTargetPreset(TargetSizePreset.DISCORD)
            .copy(clipStartMs = 0, clipEndMs = 15_000, engine = EncodeEngine.MEDIA3)
        val full = FfmpegCommandBuilder.scaledVideoBitrate(source, settings.copy(clipEndMs = null))
        val clipped = FfmpegCommandBuilder.scaledVideoBitrate(source, settings)
        val media3 = Media3EncodePlanner.plan(settings, source)
        assertTrue(clipped > full)
        assertEquals(clipped, media3.videoBitrateBps / 1000)
    }

    @Test
    fun copyAudioIsReencodedUnderASizeCap() {
        val settings = EncodeSettings.forPreset(Preset.BALANCED).copy(
            audio = AudioOption.COPY,
            targetSizePreset = TargetSizePreset.GMAIL,
            targetSizeBytes = TargetSizePreset.GMAIL.bytes,
        )
        val plan = FfmpegCommandBuilder.build("in.mp4", "out.mp4", settings, source, caps)
        assertEquals("aac", plan.args[plan.args.indexOf("-c:a") + 1])
        assertEquals("128k", plan.args[plan.args.indexOf("-b:a") + 1])
        val spec = Media3EncodePlanner.plan(settings, source)
        assertFalse(spec.remuxAudio)
        assertEquals(128_000, spec.audioBitrateBps)
    }

    @Test
    fun whatsappPresetUsesBaseline720pAndCbr() {
        val settings = EncodeSettings.forPreset(Preset.HIGHER).withTargetPreset(TargetSizePreset.WHATSAPP)
        assertEquals(TargetSizePreset.WHATSAPP, settings.targetSizePreset)
        assertEquals(16L shl 20, settings.targetSizeBytes)
        assertEquals(720, settings.maxHeight)
        assertEquals(H264Profile.BASELINE, settings.h264Profile)
        assertEquals(VideoCodec.H264, settings.codec)
        assertEquals(BitrateMode.CBR, settings.bitrateMode)
        assertEquals(AudioOption.AAC_96, settings.audio)
        assertTrue(settings.hasTargetSize())
    }

    @Test
    fun audioOnlyFitToSizeUsesAudioFormula() {
        val settings = EncodeSettings.forPreset(Preset.BALANCED)
            .copy(output = OutputMode.AUDIO)
            .withTargetPreset(TargetSizePreset.GMAIL)
        val plan = FfmpegCommandBuilder.build("in.mp4", "out.m4a", settings, source, caps)
        val expected = TargetSizeBitrate.audioKbps(TargetSizePreset.GMAIL.bytes!!, source.durationMs)
        assertEquals(0, plan.videoBitrateKbps)
        assertEquals(expected, plan.audioBitrateKbps)
        assertEquals("${expected}k", plan.args[plan.args.indexOf("-b:a") + 1])
        val spec = Media3EncodePlanner.plan(settings, source)
        assertTrue(spec.removeVideo)
        assertEquals(expected * 1000, spec.audioBitrateBps)
    }

    @Test
    fun clampsToMinimumWhenTheCapIsTooSmall() {
        val kbps = TargetSizeBitrate.videoKbps(300_000, 3_600_000, 128)
        assertEquals(TargetSizeBitrate.MIN_VIDEO_KBPS, kbps)
    }

    @Test
    fun namedCapsMatchPlatformLimits() {
        assertEquals(10L * 1024 * 1024, TargetSizePreset.DISCORD.bytes)
        assertEquals(16L * 1024 * 1024, TargetSizePreset.WHATSAPP.bytes)
        assertEquals(64L * 1024 * 1024, TargetSizePreset.WHATSAPP_64.bytes)
        assertEquals(25L * 1024 * 1024, TargetSizePreset.GMAIL.bytes)
        assertEquals(TargetSizePreset.DISCORD, TargetSizePreset.of(10L shl 20))
        assertEquals(TargetSizePreset.CUSTOM, TargetSizePreset.of(12L shl 20))
        assertEquals(TargetSizePreset.OFF, TargetSizePreset.of(null))
    }
}
