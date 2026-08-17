package com.androidcompress.app.encode

import com.androidcompress.app.data.AudioOption
import com.androidcompress.app.data.BFrameSetting
import com.androidcompress.app.data.BitrateMode
import com.androidcompress.app.data.EncodeSettings
import com.androidcompress.app.data.EncoderCapabilities
import com.androidcompress.app.data.ContainerFormat
import com.androidcompress.app.data.OutputMode
import com.androidcompress.app.data.H264Profile
import com.androidcompress.app.data.HdrMode
import com.androidcompress.app.data.KeyframeInterval
import com.androidcompress.app.data.Preset
import com.androidcompress.app.data.SourceVideo
import com.androidcompress.app.data.VideoCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FfmpegCommandBuilderTest {

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

    private val hardwareCaps = EncoderCapabilities(
        hasH264MediaCodec = true,
        hasHevcMediaCodec = true,
        hasOpenH264 = false,
        hasMpeg4 = true,
    )

    @Test
    fun balancedUses1080AndHardwareH264() {
        val plan = FfmpegCommandBuilder.build(
            input = "in.mp4",
            output = "out.mp4",
            settings = EncodeSettings.forPreset(Preset.BALANCED),
            source = source,
            capabilities = hardwareCaps,
        )
        assertEquals("h264_mediacodec", plan.videoEncoder)
        assertEquals("nv12", plan.pixFmt)
        assertTrue(plan.args.contains("-c:v"))
        assertEquals("h264_mediacodec", plan.args[plan.args.indexOf("-c:v") + 1])
        assertTrue(plan.args.contains("-movflags"))
        assertEquals("out.mp4", plan.args.last())
        assertEquals(2500, plan.videoBitrateKbps)
    }

    @Test
    fun smallerDownscales1080To720WithEvenWidth() {
        val plan = FfmpegCommandBuilder.build(
            "in.mp4",
            "out.mp4",
            EncodeSettings.forPreset(Preset.SMALLER),
            source,
            hardwareCaps,
        )
        assertEquals(720, plan.outputHeight)
        assertEquals(1280, plan.outputWidth)
        assertTrue(plan.args.contains("scale=1280:720"))
        assertTrue(plan.args.contains("-r"))
        assertEquals("30", plan.args[plan.args.indexOf("-r") + 1])
    }

    @Test
    fun oddSourceWidthBecomesEven() {
        val odd = source.copy(width = 1081, height = 721)
        val plan = FfmpegCommandBuilder.build(
            "in.mp4",
            "out.mp4",
            EncodeSettings.forPreset(Preset.SMALLER).copy(maxHeight = 721),
            odd,
            hardwareCaps,
        )
        assertEquals(0, plan.outputWidth % 2)
        assertEquals(0, plan.outputHeight % 2)
    }

    @Test
    fun hevcUsesHardwareAndHvc1Tag() {
        val settings = EncodeSettings.forPreset(Preset.BALANCED).copy(codec = VideoCodec.HEVC)
        val plan = FfmpegCommandBuilder.build("in.mp4", "out.mp4", settings, source, hardwareCaps)
        assertEquals("hevc_mediacodec", plan.videoEncoder)
        assertTrue(plan.args.contains("hvc1"))
    }

    @Test
    fun hevcFallsBackToH264WhenNoHardwareHevc() {
        val settings = EncodeSettings.forPreset(Preset.BALANCED).copy(codec = VideoCodec.HEVC)
        val caps = hardwareCaps.copy(hasHevcMediaCodec = false)
        val encoder = FfmpegCommandBuilder.selectVideoEncoder(settings, caps)
        assertEquals("h264_mediacodec", encoder)
    }

    @Test
    fun muteRemovesAudio() {
        val settings = EncodeSettings.forPreset(Preset.BALANCED).copy(audio = AudioOption.MUTE)
        val plan = FfmpegCommandBuilder.build("in.mp4", "out.mp4", settings, source, hardwareCaps)
        assertTrue(plan.args.contains("-an"))
        assertFalse(plan.args.contains("-c:a"))
    }

    @Test
    fun copyAacKeepsAudioStream() {
        val settings = EncodeSettings.forPreset(Preset.BALANCED).copy(audio = AudioOption.COPY)
        val plan = FfmpegCommandBuilder.build("in.mp4", "out.mp4", settings, source, hardwareCaps)
        assertEquals("copy", plan.args[plan.args.indexOf("-c:a") + 1])
    }

    @Test
    fun softwareFallbackWhenHardwareDisabled() {
        val settings = EncodeSettings.forPreset(Preset.BALANCED).copy(preferHardware = false)
        val encoder = FfmpegCommandBuilder.selectVideoEncoder(settings, hardwareCaps)
        assertEquals("mpeg4", encoder)
    }

    @Test
    fun fallbackChangesPixFmtThenEncoder() {
        val first = FfmpegCommandBuilder.build("in.mp4", "out.mp4", EncodeSettings.forPreset(Preset.BALANCED), source, hardwareCaps)
        val second = FfmpegCommandBuilder.fallbackPlan(first, "in.mp4", "out.mp4", EncodeSettings.forPreset(Preset.BALANCED), source, hardwareCaps)
        assertNotNull(second)
        assertEquals("yuv420p", second!!.pixFmt)
        val third = FfmpegCommandBuilder.fallbackPlan(second, "in.mp4", "out.mp4", EncodeSettings.forPreset(Preset.BALANCED), source, hardwareCaps)
        assertEquals("mpeg4", third?.videoEncoder)
        val last = FfmpegCommandBuilder.fallbackPlan(third!!, "in.mp4", "out.mp4", EncodeSettings.forPreset(Preset.BALANCED), source, hardwareCaps)
        assertNull(last)
    }

    @Test
    fun estimateIsPositiveAndUsesDuration() {
        val bytes = FfmpegCommandBuilder.estimateOutputBytes(source, EncodeSettings.forPreset(Preset.BALANCED))
        assertTrue(bytes > 1_000_000)
        val shortClip = FfmpegCommandBuilder.estimateOutputBytes(source.copy(durationMs = 1_000), EncodeSettings.forPreset(Preset.BALANCED))
        assertTrue(shortClip < bytes)
    }

    @Test
    fun encoderListingParser() {
        val listing = """
            Encoders:
             V..... h264_mediacodec
             V..... mpeg4
             V..... libx264
        """.trimIndent()
        val caps = EncoderListing.parse(listing)
        assertTrue(caps.hasH264MediaCodec)
        assertFalse(caps.hasHevcMediaCodec)
        assertTrue(caps.hasMpeg4)
        assertFalse(caps.hasOpenH264)
    }

    @Test
    fun vbrOmitsMaxrate() {
        val settings = EncodeSettings.forPreset(Preset.BALANCED).copy(bitrateMode = BitrateMode.VBR)
        val plan = FfmpegCommandBuilder.build("in.mp4", "out.mp4", settings, source, hardwareCaps)
        assertFalse(plan.args.contains("-maxrate"))
        assertTrue(plan.args.contains("-b:v"))
    }

    @Test
    fun keyframeAndProfileAndBframes() {
        val settings = EncodeSettings.forPreset(Preset.BALANCED).copy(
            keyframeInterval = KeyframeInterval.SEC_2,
            h264Profile = H264Profile.HIGH,
            bFrames = BFrameSetting.TWO,
        )
        val plan = FfmpegCommandBuilder.build("in.mp4", "out.mp4", settings, source, hardwareCaps)
        assertEquals("high", plan.args[plan.args.indexOf("-profile:v") + 1])
        assertEquals("60", plan.args[plan.args.indexOf("-g") + 1])
        assertEquals("2", plan.args[plan.args.indexOf("-bf") + 1])
    }

    @Test
    fun volumeRewritesCopyAudio() {
        val settings = EncodeSettings.forPreset(Preset.BALANCED).copy(
            audio = AudioOption.COPY,
            audioVolumePercent = 150,
        )
        val plan = FfmpegCommandBuilder.build("in.mp4", "out.mp4", settings, source, hardwareCaps)
        assertTrue(plan.args.contains("volume=1.5"))
        assertEquals("aac", plan.args[plan.args.indexOf("-c:a") + 1])
    }

    @Test
    fun fastStartCanBeDisabled() {
        val settings = EncodeSettings.forPreset(Preset.BALANCED).copy(fastStart = false)
        val plan = FfmpegCommandBuilder.build("in.mp4", "out.mp4", settings, source, hardwareCaps)
        assertFalse(plan.args.contains("-movflags"))
    }

    @Test
    fun extraArgsInsertedBeforeOutput() {
        val settings = EncodeSettings.forPreset(Preset.BALANCED).copy(ffmpegExtraArgs = "-vf hflip")
        val plan = FfmpegCommandBuilder.build("in.mp4", "out.mp4", settings, source, hardwareCaps)
        assertEquals("out.mp4", plan.args.last())
        assertEquals(listOf("-vf", "hflip"), plan.args.dropLast(1).takeLast(2))
    }

    @Test
    fun invalidExtraArgsAreIgnored() {
        val settings = EncodeSettings.forPreset(Preset.BALANCED).copy(ffmpegExtraArgs = "-i other.mp4")
        val plan = FfmpegCommandBuilder.build("in.mp4", "out.mp4", settings, source, hardwareCaps)
        assertFalse(plan.args.contains("other.mp4"))
        assertEquals("out.mp4", plan.args.last())
    }

    @Test
    fun audioOnlyDropsVideoAndWritesAac() {
        val settings = EncodeSettings.forPreset(Preset.BALANCED).copy(output = OutputMode.AUDIO)
        val plan = FfmpegCommandBuilder.build("in.mp4", "out.m4a", settings, source, hardwareCaps)
        assertTrue(plan.args.contains("-vn"))
        assertFalse(plan.args.contains("-c:v"))
        assertEquals("aac", plan.args[plan.args.indexOf("-c:a") + 1])
        assertEquals("128k", plan.args[plan.args.indexOf("-b:a") + 1])
        assertEquals("out.m4a", plan.args.last())
        assertEquals("", plan.videoEncoder)
        assertNull(FfmpegCommandBuilder.fallbackPlan(plan, "in.mp4", "out.m4a", settings, source, hardwareCaps))
    }

    @Test
    fun audioOnlyFromAudioFile() {
        val audio = source.copy(width = 0, height = 0, hasVideo = false, displayName = "song.m4a")
        val plan = FfmpegCommandBuilder.build(
            "in.m4a",
            "out.m4a",
            EncodeSettings.forPreset(Preset.BALANCED),
            audio,
            hardwareCaps,
        )
        assertTrue(plan.args.contains("-vn"))
        assertFalse(plan.args.contains("-c:v"))
    }

    @Test
    fun audioOnlyClipAddsSeekAndDuration() {
        val settings = EncodeSettings.forPreset(Preset.BALANCED).copy(
            output = OutputMode.AUDIO,
            clipStartMs = 5_000,
            clipEndMs = 20_000,
        )
        val plan = FfmpegCommandBuilder.build("in.mp4", "out.m4a", settings, source, hardwareCaps)
        assertEquals("5.000", plan.args[plan.args.indexOf("-ss") + 1])
        assertEquals("15.000", plan.args[plan.args.indexOf("-t") + 1])
    }

    @Test
    fun audioEstimateOmitsVideoBitrate() {
        val video = FfmpegCommandBuilder.estimateOutputBytes(source, EncodeSettings.forPreset(Preset.BALANCED))
        val audio = FfmpegCommandBuilder.estimateOutputBytes(
            source,
            EncodeSettings.forPreset(Preset.BALANCED).copy(output = OutputMode.AUDIO),
        )
        assertTrue(audio < video)
        assertTrue(audio > 10_000)
    }

    @Test
    fun webmUsesVp9AndOpus() {
        val caps = hardwareCaps.copy(hasVp9MediaCodec = true, hasLibvpxVp9 = true, hasLibOpus = true)
        val settings = EncodeSettings.forPreset(Preset.BALANCED).copy(
            container = ContainerFormat.WEBM,
            codec = VideoCodec.VP9,
        )
        val plan = FfmpegCommandBuilder.build("in.mp4", "out.webm", settings, source, caps)
        assertEquals("libvpx-vp9", plan.videoEncoder)
        assertEquals("yuv420p", plan.pixFmt)
        assertTrue(plan.args.contains("format=yuv420p"))
        assertEquals("libopus", plan.args[plan.args.indexOf("-c:a") + 1])
        assertFalse(plan.args.contains("-movflags"))
        assertFalse(plan.args.contains("-bf"))
        assertEquals("out.webm", plan.args.last())
    }

    @Test
    fun webmIgnoresHardwareVp9WhenLibvpxIsPresent() {
        val caps = hardwareCaps.copy(hasVp9MediaCodec = true, hasLibvpxVp9 = true, hasLibvpx = true)
        val settings = EncodeSettings.forPreset(Preset.BALANCED).copy(
            container = ContainerFormat.WEBM,
            preferHardware = true,
        )
        val encoder = FfmpegCommandBuilder.selectVideoEncoder(settings, caps)
        assertEquals("libvpx-vp9", encoder)
    }

    @Test
    fun webmHardwareOnlyUsesYuv420pNotNv12() {
        val caps = hardwareCaps.copy(hasVp9MediaCodec = true)
        val settings = EncodeSettings.forPreset(Preset.BALANCED).copy(container = ContainerFormat.WEBM)
        val plan = FfmpegCommandBuilder.build("in.mp4", "out.webm", settings, source, caps)
        assertEquals("vp9_mediacodec", plan.videoEncoder)
        assertEquals("yuv420p", plan.pixFmt)
        assertFalse(plan.args.contains("nv12"))
    }

    @Test
    fun webmSoftwareUsesLibvpx() {
        val caps = EncoderCapabilities(hasLibvpx = true, hasLibvpxVp9 = true, hasLibOpus = true)
        val settings = EncodeSettings.forPreset(Preset.BALANCED).copy(
            container = ContainerFormat.WEBM,
            codec = VideoCodec.VP9,
            preferHardware = false,
        )
        val plan = FfmpegCommandBuilder.build("in.mp4", "out.webm", settings, source, caps)
        assertEquals("libvpx-vp9", plan.videoEncoder)
        assertEquals("yuv420p", plan.pixFmt)
        assertEquals("good", plan.args[plan.args.indexOf("-deadline") + 1])
        assertEquals("1", plan.args[plan.args.indexOf("-row-mt") + 1])
    }

    @Test
    fun webmDoesNotCopyAacAudio() {
        val settings = EncodeSettings.forPreset(Preset.BALANCED).copy(
            container = ContainerFormat.WEBM,
            audio = AudioOption.COPY,
        )
        val plan = FfmpegCommandBuilder.build("in.mp4", "out.webm", settings, source, hardwareCaps)
        assertEquals("libopus", plan.args[plan.args.indexOf("-c:a") + 1])
    }

    @Test
    fun webmFallbackStaysInWebmFamily() {
        val caps = hardwareCaps.copy(hasVp9MediaCodec = true, hasLibvpx = true, hasLibvpxVp9 = true)
        val settings = EncodeSettings.forPreset(Preset.BALANCED).copy(container = ContainerFormat.WEBM)
        val first = FfmpegCommandBuilder.build("in.mp4", "out.webm", settings, source, caps)
        assertEquals("libvpx-vp9", first.videoEncoder)
        val second = FfmpegCommandBuilder.fallbackPlan(first, "in.mp4", "out.webm", settings, source, caps)
        assertEquals("libvpx", second?.videoEncoder)
        assertEquals("yuv420p", second!!.pixFmt)
        assertEquals("0", second.args[second.args.indexOf("-auto-alt-ref") + 1])
        assertNull(FfmpegCommandBuilder.fallbackPlan(second, "in.mp4", "out.webm", settings, source, caps))
    }

    @Test
    fun webmMediaCodecFallsBackToLibvpx() {
        val caps = hardwareCaps.copy(hasVp9MediaCodec = true, hasLibvpx = true, hasLibvpxVp9 = true)
        val settings = EncodeSettings.forPreset(Preset.BALANCED).copy(container = ContainerFormat.WEBM)
        val hardware = FfmpegCommandBuilder.build(
            "in.mp4", "out.webm", settings, source, caps,
            encoderOverride = "vp9_mediacodec",
        )
        assertEquals("vp9_mediacodec", hardware.videoEncoder)
        val software = FfmpegCommandBuilder.fallbackPlan(hardware, "in.mp4", "out.webm", settings, source, caps)
        assertEquals("libvpx-vp9", software?.videoEncoder)
    }

    @Test
    fun webmAudioOnlyWritesOpus() {
        val settings = EncodeSettings.forPreset(Preset.BALANCED).copy(
            output = OutputMode.AUDIO,
            container = ContainerFormat.WEBM,
        )
        val plan = FfmpegCommandBuilder.build("in.mp4", "out.webm", settings, source, hardwareCaps)
        assertTrue(plan.args.contains("-vn"))
        assertEquals("libopus", plan.args[plan.args.indexOf("-c:a") + 1])
        assertFalse(plan.args.contains("-movflags"))
        assertEquals("out.webm", plan.args.last())
    }

    @Test
    fun encoderListingParserReadsVpx() {
        val listing = """
            Encoders:
             V..... vp9_mediacodec
             V..... libvpx-vp9
             A..... libopus
        """.trimIndent()
        val caps = EncoderListing.parse(listing)
        assertTrue(caps.hasVp9MediaCodec)
        assertTrue(caps.hasLibvpxVp9)
        assertTrue(caps.hasLibvpx)
        assertTrue(caps.hasLibOpus)
        assertFalse(caps.hasH264MediaCodec)
    }

    @Test
    fun toneMapAddsRec709() {
        val settings = EncodeSettings.forPreset(Preset.BALANCED).copy(hdrMode = HdrMode.TONE_MAP)
        val plan = FfmpegCommandBuilder.build("in.mp4", "out.mp4", settings, source, hardwareCaps)
        assertTrue(plan.args.contains("format=yuv420p"))
        assertEquals("bt709", plan.args[plan.args.indexOf("-colorspace") + 1])
    }
}
