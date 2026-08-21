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
import com.androidcompress.app.media.InputResolver
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
    fun balancedUses1080AndSoftwareH264() {
        val plan = FfmpegCommandBuilder.build(
            input = "in.mp4",
            output = "out.mp4",
            settings = EncodeSettings.forPreset(Preset.BALANCED),
            source = source,
            capabilities = hardwareCaps,
        )
        assertEquals("mpeg4", plan.videoEncoder)
        assertEquals("yuv420p", plan.pixFmt)
        assertTrue(plan.args.contains("-c:v"))
        assertEquals("mpeg4", plan.args[plan.args.indexOf("-c:v") + 1])
        assertTrue(plan.args.contains("-movflags"))
        assertEquals("out.mp4", plan.args.last())
        assertEquals(2500, plan.videoBitrateKbps)
    }

    @Test
    fun ffmpegH264PrefersOpenH264OverMediaCodec() {
        val caps = hardwareCaps.copy(hasOpenH264 = true)
        val settings = EncodeSettings.forPreset(Preset.BALANCED)
        assertEquals("libopenh264", FfmpegCommandBuilder.selectVideoEncoder(settings, caps))
        assertEquals(
            "libopenh264",
            FfmpegCommandBuilder.selectVideoEncoder(settings.copy(preferHardware = true), caps),
        )
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
        val vf = plan.args[plan.args.indexOf("-vf") + 1]
        assertTrue(vf.contains("scale=1280:720"))
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
        assertEquals("mpeg4", encoder)
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
    fun fallbackFromMediaCodecGoesToMpeg4() {
        val settings = EncodeSettings.forPreset(Preset.BALANCED)
        val hardware = FfmpegCommandBuilder.build(
            "in.mp4", "out.mp4", settings, source, hardwareCaps,
            encoderOverride = "h264_mediacodec",
        )
        assertEquals("h264_mediacodec", hardware.videoEncoder)
        val software = FfmpegCommandBuilder.fallbackPlan(
            hardware, "in.mp4", "out.mp4", settings, source, hardwareCaps,
        )
        assertEquals("mpeg4", software?.videoEncoder)
        assertEquals("yuv420p", software!!.pixFmt)
        assertNull(FfmpegCommandBuilder.fallbackPlan(software, "in.mp4", "out.mp4", settings, source, hardwareCaps))
    }

    @Test
    fun screenRecordingUsesCfrSoftwareH264() {
        val recorded = source.copy(width = 480, height = 1080, frameRate = 21.45f)
        val plan = FfmpegCommandBuilder.build(
            "in.mp4",
            "out.mp4",
            EncodeSettings.forPreset(Preset.BALANCED),
            recorded,
            hardwareCaps.copy(hasOpenH264 = true),
        )
        assertEquals("libopenh264", plan.videoEncoder)
        assertEquals("yuv420p", plan.pixFmt)
        assertFalse(plan.args.contains("nv12"))
        assertFalse(plan.args.contains("h264_mediacodec"))
        val vf = plan.args[plan.args.indexOf("-vf") + 1]
        assertTrue(vf.contains("format=yuv420p"))
        assertEquals("21", plan.args[plan.args.indexOf("-r") + 1])
        assertEquals("bt709", plan.args[plan.args.indexOf("-colorspace") + 1])
        assertEquals("tv", plan.args[plan.args.indexOf("-color_range") + 1])
    }

    @Test
    fun estimateIsPositiveAndUsesDuration() {
        val bytes = FfmpegCommandBuilder.estimateOutputBytes(source, EncodeSettings.forPreset(Preset.BALANCED))
        assertTrue(bytes > 1_000_000)
        val shortClip = FfmpegCommandBuilder.estimateOutputBytes(source.copy(durationMs = 1_000), EncodeSettings.forPreset(Preset.BALANCED))
        assertTrue(shortClip < bytes)
    }

    @Test
    fun estimateUsesClipWindow() {
        val settings = EncodeSettings.forPreset(Preset.BALANCED).copy(
            clipStartMs = 10_000,
            clipEndMs = 20_000,
        )
        val clipped = FfmpegCommandBuilder.estimateOutputBytes(source, settings)
        val full = FfmpegCommandBuilder.estimateOutputBytes(source, EncodeSettings.forPreset(Preset.BALANCED))
        assertTrue(clipped < full / 2)
        assertTrue(clipped > 100_000)
    }

    @Test
    fun largeSourceDoesNotNeedTwiceSourceOnDisk() {
        val fourK = source.copy(
            width = 3840,
            height = 2160,
            durationMs = 3_600_000,
            bytes = 10L * 1024 * 1024 * 1024,
            frameRate = 60f,
        )
        val estimated = FfmpegCommandBuilder.estimateOutputBytes(fourK, EncodeSettings.forPreset(Preset.BALANCED))
        val needed = InputResolver.bytesNeededForEncode(estimated, fourK.bytes, fourK.durationMs)
        val oldHeuristic = fourK.bytes * 2 + InputResolver.STORAGE_OVERHEAD_BYTES
        assertTrue(estimated < 2L * 1024 * 1024 * 1024)
        assertTrue(needed < 4L * 1024 * 1024 * 1024)
        assertTrue(needed < oldHeuristic / 4)
        assertTrue(needed > InputResolver.STORAGE_OVERHEAD_BYTES)
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
    fun videoEncodeMapsEveryAudioStream() {
        val plan = FfmpegCommandBuilder.build(
            "in.mp4",
            "out.mp4",
            EncodeSettings.forPreset(Preset.BALANCED),
            source,
            hardwareCaps,
        )
        val maps = plan.args.mapIndexedNotNull { i, token ->
            if (token == "-map") plan.args[i + 1] else null
        }
        assertEquals(listOf("0:v:0", "0:a"), maps)
    }

    @Test
    fun muteMapsVideoOnly() {
        val plan = FfmpegCommandBuilder.build(
            "in.mp4",
            "out.mp4",
            EncodeSettings.forPreset(Preset.BALANCED).copy(audio = AudioOption.MUTE),
            source,
            hardwareCaps,
        )
        val maps = plan.args.mapIndexedNotNull { i, token ->
            if (token == "-map") plan.args[i + 1] else null
        }
        assertEquals(listOf("0:v:0"), maps)
        assertTrue(plan.args.contains("-an"))
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
    fun av1UsesHardwareAndAv01Tag() {
        val caps = hardwareCaps.copy(hasAv1MediaCodec = true)
        val settings = EncodeSettings.forPreset(Preset.BALANCED).copy(codec = VideoCodec.AV1)
        val plan = FfmpegCommandBuilder.build("in.mp4", "out.mp4", settings, source, caps)
        assertEquals("av1_mediacodec", plan.videoEncoder)
        assertEquals("yuv420p", plan.pixFmt)
        assertEquals("av01", plan.args[plan.args.indexOf("-tag:v") + 1])
        assertFalse(plan.args.contains("-bf"))
    }

    @Test
    fun av1UsesLibaomWhenHardwareOff() {
        val caps = hardwareCaps.copy(hasAv1MediaCodec = true, hasLibaomAv1 = true)
        val settings = EncodeSettings.forPreset(Preset.BALANCED).copy(
            codec = VideoCodec.AV1,
            preferHardware = false,
        )
        val plan = FfmpegCommandBuilder.build("in.mp4", "out.mp4", settings, source, caps)
        assertEquals("libaom-av1", plan.videoEncoder)
        assertEquals("yuv420p", plan.pixFmt)
        assertEquals("realtime", plan.args[plan.args.indexOf("-usage") + 1])
        assertEquals("8", plan.args[plan.args.indexOf("-cpu-used") + 1])
        assertEquals("2x2", plan.args[plan.args.indexOf("-tiles") + 1])
    }

    @Test
    fun av1FallsBackToH264WithoutAv1Encoders() {
        val settings = EncodeSettings.forPreset(Preset.BALANCED).copy(codec = VideoCodec.AV1)
        val encoder = FfmpegCommandBuilder.selectVideoEncoder(settings, hardwareCaps)
        assertEquals("mpeg4", encoder)
    }

    @Test
    fun webmAv1UsesHardwareThenOpus() {
        val caps = hardwareCaps.copy(hasAv1MediaCodec = true, hasLibOpus = true)
        val settings = EncodeSettings.forPreset(Preset.BALANCED).copy(
            container = ContainerFormat.WEBM,
            codec = VideoCodec.AV1,
        )
        val plan = FfmpegCommandBuilder.build("in.mp4", "out.webm", settings, source, caps)
        assertEquals("av1_mediacodec", plan.videoEncoder)
        assertEquals("yuv420p", plan.pixFmt)
        assertEquals("libopus", plan.args[plan.args.indexOf("-c:a") + 1])
        assertFalse(plan.args.contains("-tag:v"))
        assertFalse(plan.args.contains("-movflags"))
    }

    @Test
    fun webmAv1HardwareFallsBackToLibaomThenVp9() {
        val caps = hardwareCaps.copy(
            hasAv1MediaCodec = true,
            hasLibaomAv1 = true,
            hasLibvpx = true,
            hasLibvpxVp9 = true,
        )
        val settings = EncodeSettings.forPreset(Preset.BALANCED).copy(
            container = ContainerFormat.WEBM,
            codec = VideoCodec.AV1,
        )
        val first = FfmpegCommandBuilder.build("in.mp4", "out.webm", settings, source, caps)
        assertEquals("av1_mediacodec", first.videoEncoder)
        val second = FfmpegCommandBuilder.fallbackPlan(first, "in.mp4", "out.webm", settings, source, caps)
        assertEquals("libaom-av1", second?.videoEncoder)
        val third = FfmpegCommandBuilder.fallbackPlan(second!!, "in.mp4", "out.webm", settings, source, caps)
        assertEquals("libvpx-vp9", third?.videoEncoder)
    }

    @Test
    fun mp4Av1HardwareFallsBackToLibaom() {
        val caps = hardwareCaps.copy(hasAv1MediaCodec = true, hasLibaomAv1 = true)
        val settings = EncodeSettings.forPreset(Preset.BALANCED).copy(codec = VideoCodec.AV1)
        val first = FfmpegCommandBuilder.build("in.mp4", "out.mp4", settings, source, caps)
        assertEquals("av1_mediacodec", first.videoEncoder)
        assertEquals("yuv420p", first.pixFmt)
        val software = FfmpegCommandBuilder.fallbackPlan(first, "in.mp4", "out.mp4", settings, source, caps)
        assertEquals("libaom-av1", software?.videoEncoder)
    }

    @Test
    fun encoderListingParserReadsAv1() {
        val listing = """
            Encoders:
             V..... av1_mediacodec
             V..... libaom-av1
             V..... libsvtav1
        """.trimIndent()
        val caps = EncoderListing.parse(listing)
        assertTrue(caps.hasAv1MediaCodec)
        assertTrue(caps.hasLibaomAv1)
        assertTrue(caps.hasLibSvtAv1)
        assertFalse(caps.hasH264MediaCodec)
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
        val vf = plan.args[plan.args.indexOf("-vf") + 1]
        assertTrue(vf.contains("format=yuv420p"))
        assertEquals("bt709", plan.args[plan.args.indexOf("-colorspace") + 1])
    }

    @Test
    fun stillImageLoopsAndUsesCompanionAudio() {
        val still = source.copy(
            displayName = "cover.jpg",
            durationMs = 45_000,
            frameRate = 30f,
            stillImage = true,
            audioUri = "content://audio",
            hasAudio = true,
        )
        val plan = FfmpegCommandBuilder.build(
            "cover.jpg",
            "out.mp4",
            EncodeSettings.forPreset(Preset.BALANCED),
            still,
            hardwareCaps,
            audioInput = "song.m4a",
        )
        assertEquals("1", plan.args[plan.args.indexOf("-loop") + 1])
        assertEquals("30", plan.args[plan.args.indexOf("-framerate") + 1])
        val inputs = plan.args.withIndex().filter { it.value == "-i" }.map { plan.args[it.index + 1] }
        assertEquals(listOf("cover.jpg", "song.m4a"), inputs)
        assertEquals("0:v:0", plan.args[plan.args.indexOf("-map") + 1])
        assertTrue(plan.args.contains("1:a:0"))
        assertTrue(plan.args.contains("-shortest"))
        assertEquals("45.000", plan.args[plan.args.lastIndexOf("-t") + 1])
    }

    @Test
    fun videoPlusAudioMapsCompanionSoundtrack() {
        val mixed = source.copy(audioUri = "content://audio", hasAudio = true)
        val plan = FfmpegCommandBuilder.build(
            "clip.mp4",
            "out.mp4",
            EncodeSettings.forPreset(Preset.BALANCED),
            mixed,
            hardwareCaps,
            audioInput = "song.m4a",
        )
        val inputs = plan.args.withIndex().filter { it.value == "-i" }.map { plan.args[it.index + 1] }
        assertEquals(listOf("clip.mp4", "song.m4a"), inputs)
        assertFalse(plan.args.contains("-loop"))
        assertEquals("0:v:0", plan.args[plan.args.indexOf("-map") + 1])
        assertTrue(plan.args.contains("1:a:0"))
        assertTrue(plan.args.contains("-shortest"))
    }

    @Test
    fun twoPassMpeg4WritesPassOneAndPassTwo() {
        val settings = EncodeSettings.forPreset(Preset.BALANCED).copy(twoPass = true)
        val plan = FfmpegCommandBuilder.build(
            "in.mp4",
            "out.mp4",
            settings,
            source,
            hardwareCaps,
            passLogPrefix = "/cache/job.2pass",
        )
        assertEquals("mpeg4", plan.videoEncoder)
        val pass1 = plan.firstPassArgs
        assertNotNull(pass1)
        assertEquals("1", pass1!![pass1.indexOf("-pass") + 1])
        assertEquals("/cache/job.2pass", pass1[pass1.indexOf("-passlogfile") + 1])
        assertTrue(pass1.contains("-an"))
        assertEquals("null", pass1[pass1.indexOf("-f") + 1])
        assertEquals(FfmpegCommandBuilder.TWO_PASS_NULL_OUTPUT, pass1.last())
        assertFalse(pass1.contains("-c:a"))
        assertEquals("0.25", pass1[pass1.indexOf("-stats_period") + 1])
        val vf = pass1[pass1.indexOf("-vf") + 1]
        assertTrue(vf.contains("fps=30"))
        assertEquals("2", plan.args[plan.args.indexOf("-pass") + 1])
        assertEquals("/cache/job.2pass", plan.args[plan.args.indexOf("-passlogfile") + 1])
        assertTrue(plan.args.contains("-c:a"))
        assertFalse(plan.args.contains("-maxrate"))
        val template = FfmpegCommandTemplate.fromArgs(plan.args)
        assertTrue(template.contains("PASSLOG"))
        assertFalse(template.contains("/cache/job.2pass"))
    }

    @Test
    fun twoPassVp9UsesLibvpxAndSkipsCbr() {
        val settings = EncodeSettings.forPreset(Preset.BALANCED).copy(
            twoPass = true,
            container = ContainerFormat.WEBM,
            codec = VideoCodec.VP9,
            bitrateMode = BitrateMode.CBR,
        )
        val caps = hardwareCaps.copy(hasLibvpx = true, hasLibvpxVp9 = true)
        val plan = FfmpegCommandBuilder.build("in.mp4", "out.webm", settings, source, caps)
        assertEquals("libvpx-vp9", plan.videoEncoder)
        val pass1 = plan.firstPassArgs
        assertNotNull(pass1)
        assertEquals("2", plan.args[plan.args.indexOf("-cpu-used") + 1])
        assertFalse(plan.args.contains("-minrate"))
        assertFalse(plan.args.contains("-maxrate"))
        val vf = pass1!![pass1.indexOf("-vf") + 1]
        assertTrue(vf.contains("fps=30"))
        assertEquals(FfmpegCommandBuilder.TWO_PASS_NULL_OUTPUT, pass1.last())
    }

    @Test
    fun twoPassSkippedForOpenh264AndHardware() {
        val settings = EncodeSettings.forPreset(Preset.BALANCED).copy(twoPass = true)
        val openh264 = FfmpegCommandBuilder.build(
            "in.mp4",
            "out.mp4",
            settings,
            source,
            hardwareCaps.copy(hasOpenH264 = true),
        )
        assertEquals("libopenh264", openh264.videoEncoder)
        assertNull(openh264.firstPassArgs)
        assertFalse(openh264.args.contains("-pass"))
        val hevc = FfmpegCommandBuilder.build(
            "in.mp4",
            "out.mp4",
            settings.copy(codec = VideoCodec.HEVC),
            source,
            hardwareCaps,
        )
        assertEquals("hevc_mediacodec", hevc.videoEncoder)
        assertNull(hevc.firstPassArgs)
    }

    @Test
    fun twoPassSkippedForAudioOnlyAndStills() {
        val settings = EncodeSettings.forPreset(Preset.BALANCED).copy(
            twoPass = true,
            output = OutputMode.AUDIO,
        )
        val audio = FfmpegCommandBuilder.build("in.mp4", "out.m4a", settings, source, hardwareCaps)
        assertNull(audio.firstPassArgs)
        val still = source.copy(stillImage = true, audioUri = "content://a", durationMs = 8_000)
        val stillPlan = FfmpegCommandBuilder.build(
            "cover.jpg",
            "out.mp4",
            EncodeSettings.forPreset(Preset.BALANCED).copy(twoPass = true),
            still,
            hardwareCaps,
            audioInput = "song.m4a",
        )
        assertNull(stillPlan.firstPassArgs)
    }

    @Test
    fun twoPassAv1DropsRealtimeUsage() {
        val settings = EncodeSettings.forPreset(Preset.BALANCED).copy(
            twoPass = true,
            codec = VideoCodec.AV1,
            preferHardware = false,
        )
        val caps = hardwareCaps.copy(hasLibaomAv1 = true)
        val plan = FfmpegCommandBuilder.build("in.mp4", "out.mp4", settings, source, caps)
        assertEquals("libaom-av1", plan.videoEncoder)
        assertNotNull(plan.firstPassArgs)
        assertFalse(plan.args.contains("-usage"))
        assertFalse(plan.firstPassArgs!!.contains("-usage"))
    }
}
