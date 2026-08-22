package com.androidcompress.app.capture

import com.androidcompress.app.encode.FfmpegMuxCommands
import com.androidcompress.app.encode.RecordingCrop
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordBookmarksTest {

    @Test
    fun segmentsSkipWholeFileWhenNoMarks() {
        assertTrue(RecordBookmarks.segments(emptyList(), 10_000L).isEmpty())
    }

    @Test
    fun segmentsSplitOnMarks() {
        val segs = RecordBookmarks.segments(listOf(3_000L, 7_000L), 10_000L)
        assertEquals(3, segs.size)
        assertEquals(0L, segs[0].startMs)
        assertEquals(3_000L, segs[0].endMs)
        assertEquals(7_000L, segs[1].endMs)
        assertEquals(10_000L, segs[2].endMs)
        assertEquals(1, segs[0].index)
        assertEquals(3, segs[0].total)
    }

    @Test
    fun chaptersMetadataHasTimebase() {
        val meta = RecordBookmarks.ffmetadata(listOf(5_000L), 12_000L)!!
        assertTrue(meta.startsWith(";FFMETADATA1"))
        assertTrue(meta.contains("TIMEBASE=1/1000"))
        assertTrue(meta.contains("START=0"))
        assertTrue(meta.contains("END=5000"))
        assertTrue(meta.contains("START=5000"))
        assertTrue(meta.contains("END=12000"))
    }

    @Test
    fun chaptersNeedAMark() {
        assertNull(RecordBookmarks.ffmetadata(emptyList(), 12_000L))
    }
}

class CropDisplayPipeTest {
    @Test
    fun cropUvMapsDisplayTopLeftToGlBottomLeft() {
        val uv = CropDisplayPipe.cropUv(RecordingCrop(480, 270, 960, 540), 1920, 1080)
        assertEquals(8, uv.size)
        // Clip BL / BR sample the visual bottom of the crop (GL t = 1 - androidBottom).
        assertEquals(0.25f, uv[0], 0.001f)
        assertEquals(0.25f, uv[1], 0.001f)
        assertEquals(0.75f, uv[2], 0.001f)
        assertEquals(0.25f, uv[3], 0.001f)
        // Clip TL / TR sample the visual top of the crop (GL t = 1 - androidTop).
        assertEquals(0.25f, uv[4], 0.001f)
        assertEquals(0.75f, uv[5], 0.001f)
        assertEquals(0.75f, uv[6], 0.001f)
        assertEquals(0.75f, uv[7], 0.001f)
    }

    @Test
    fun identityCropMatchesGrafikaQuad() {
        val uv = CropDisplayPipe.cropUv(RecordingCrop(0, 0, 1080, 1920), 1080, 1920)
        assertEquals(0f, uv[0], 0.001f)
        assertEquals(0f, uv[1], 0.001f)
        assertEquals(1f, uv[2], 0.001f)
        assertEquals(0f, uv[3], 0.001f)
        assertEquals(0f, uv[4], 0.001f)
        assertEquals(1f, uv[5], 0.001f)
        assertEquals(1f, uv[6], 0.001f)
        assertEquals(1f, uv[7], 0.001f)
    }
}

class StatusBarCoverTest {
    @Test
    fun destPixelsClipsToCrop() {
        assertEquals(80, StatusBarCover.destPixels(80, cropY = 0, destHeight = 1920))
        assertEquals(30, StatusBarCover.destPixels(80, cropY = 50, destHeight = 400))
        assertEquals(0, StatusBarCover.destPixels(80, cropY = 80, destHeight = 400))
        assertEquals(0, StatusBarCover.destPixels(80, cropY = 120, destHeight = 400))
        assertEquals(0, StatusBarCover.destPixels(0, destHeight = 1080))
    }

    @Test
    fun destPixelsUsesCropRect() {
        assertEquals(60, StatusBarCover.destPixels(80, RecordingCrop(0, 20, 1080, 1920)))
        assertEquals(80, StatusBarCover.destPixels(80, null))
    }

    @Test
    fun scalePxMapsDisplayToCapture() {
        assertEquals(40, StatusBarCover.scalePx(80, 1920, 960))
        assertEquals(80, StatusBarCover.scalePx(80, 1920, 1920))
        assertEquals(0, StatusBarCover.scalePx(0, 1920, 1080))
    }

    @Test
    fun glScissorIsBottomLeft() {
        val s = StatusBarCover.glScissor(1080, 1920, 80)!!
        assertEquals(0, s[0])
        assertEquals(1840, s[1])
        assertEquals(1080, s[2])
        assertEquals(80, s[3])
        assertEquals(null, StatusBarCover.glScissor(1080, 1920, 0))
    }

    @Test
    fun drawboxHasNoSpaces() {
        val filter = StatusBarCover.drawboxFilter(72)!!
        assertFalse(filter.contains(' '))
        assertEquals("drawbox=x=0:y=0:w=iw:h=72:color=black:t=fill", filter)
    }
}

class RecordCaptureLogTest {
    @Test
    fun regionNoneWhenMissing() {
        val text = RecordCaptureLog.build(
            captureWidth = 480,
            captureHeight = 1080,
            encodeWidth = 480,
            encodeHeight = 1080,
        )
        assertTrue(text.startsWith(RecordCaptureLog.HEADER))
        assertTrue(text.contains("capture=480x1080"))
        assertTrue(text.contains("region=none"))
        assertTrue(text.contains("liveCrop=none"))
        assertTrue(text.contains("softwareCrop=none"))
        assertTrue(text.contains("liveApplied=false"))
        assertFalse(text.contains("overlay="))
        assertFalse(text.contains("liveError="))
        assertTrue(text.endsWith(RecordCaptureLog.FOOTER))
    }

    @Test
    fun fullRegionSkipsPixelCrop() {
        val text = RecordCaptureLog.build(
            captureWidth = 480,
            captureHeight = 1080,
            encodeWidth = 480,
            encodeHeight = 1080,
            overlayWidth = 1080,
            overlayHeight = 2424,
            region = RecordRegion.FULL,
        )
        assertTrue(text.contains("region=full"))
        assertTrue(text.contains("overlay=1080x2424"))
        assertFalse(text.contains("overlayCrop="))
        assertNull(RecordCaptureLog.overlayCrop(RecordRegion.FULL, 1080, 2424))
    }

    @Test
    fun logsNormalizedRegionAndPixelCrops() {
        val region = RecordRegion(0.1f, 0.2f, 0.8f, 0.9f)
        val overlay = RecordCaptureLog.overlayCrop(region, 1080, 2424)!!
        assertEquals(108, overlay.x)
        assertEquals(485, overlay.y)
        assertEquals(756, overlay.width)
        assertEquals(1697, overlay.height)
        val text = RecordCaptureLog.build(
            captureWidth = 480,
            captureHeight = 1080,
            encodeWidth = 336,
            encodeHeight = 752,
            overlayWidth = 1080,
            overlayHeight = 2424,
            region = region,
            liveCrop = RecordingCrop(48, 216, 336, 752),
            softwareCrop = null,
            liveApplied = true,
            outputWidth = 336,
            outputHeight = 752,
        )
        assertTrue(text.contains("region=0.100,0.200,0.800,0.900"))
        assertTrue(text.contains("overlayCrop=108,485 756x1697"))
        assertTrue(text.contains("liveCrop=48,216 336x752"))
        assertTrue(text.contains("softwareCrop=none"))
        assertTrue(text.contains("liveApplied=true"))
        assertTrue(text.contains("output=336x752"))
    }

    @Test
    fun liveFailureIncludesErrorAndSoftwareCrop() {
        val text = RecordCaptureLog.build(
            captureWidth = 480,
            captureHeight = 1080,
            encodeWidth = 480,
            encodeHeight = 1080,
            overlayWidth = 1080,
            overlayHeight = 2424,
            region = RecordRegion(0.25f, 0.25f, 0.75f, 0.75f),
            liveCrop = RecordingCrop(112, 256, 256, 528),
            softwareCrop = RecordingCrop(120, 270, 240, 540),
            liveApplied = false,
            liveError = "Timed out starting live crop",
            ffmpegCommand = "-vf crop=240:540:120:270",
            muxSuccess = true,
        )
        assertTrue(text.contains("liveApplied=false"))
        assertTrue(text.contains("liveError=Timed out starting live crop"))
        assertTrue(text.contains("softwareCrop=120,270 240x540"))
        assertTrue(text.contains("ffmpeg=-vf crop=240:540:120:270"))
        assertTrue(text.contains("ffmpegSuccess=true"))
        assertTrue(text.endsWith(RecordCaptureLog.FOOTER))
        val wrapped = "jobId=abc\n$text\nengine=FFMPEG\n"
        assertEquals(text, RecordCaptureLog.extract(wrapped))
    }
}

class RecordingPostProcessExtrasTest {
    @Test
    fun liveMixedWavSkipsVolume() {
        val args = FfmpegMuxCommands.recordingPostProcess(
            videoPath = "/v.mp4",
            outputPath = "/out.mp4",
            internalWav = "/mix.wav",
            applyGain = false,
            internalGainPercent = 50,
        )!!
        assertFalse(args.contains("volume=0.50"))
        assertEquals("copy", args[args.indexOf("-c:v") + 1])
        assertTrue(args.containsAll(listOf("-c:a", "aac")))
    }

    @Test
    fun webmUsesOpusAndSkipsFaststart() {
        val args = FfmpegMuxCommands.recordingPostProcess(
            videoPath = "/v.webm",
            outputPath = "/out.webm",
            internalWav = "/mix.wav",
            applyGain = false,
            containerWebm = true,
        )!!
        assertTrue(args.containsAll(listOf("-c:a", "libopus")))
        assertFalse(args.contains("-movflags"))
        assertEquals("/out.webm", args.last())
    }

    @Test
    fun isolateWebmUsesOpusAndKeepsTwoMaps() {
        val args = FfmpegMuxCommands.recordingPostProcess(
            videoPath = "/v.webm",
            outputPath = "/out.webm",
            internalWav = "/int.wav",
            micWav = "/mic.wav",
            isolateTracks = true,
            applyGain = false,
            containerWebm = true,
        )!!
        assertTrue(args.containsAll(listOf("-c:a", "libopus")))
        assertFalse(args.contains("-movflags"))
        assertFalse(args.contains("amix"))
        val maps = args.mapIndexedNotNull { i, token ->
            if (token == "-map") args[i + 1] else null
        }
        assertEquals(listOf("0:v", "1:a", "2:a"), maps)
    }

    @Test
    fun copySegmentUsesCopyCodec() {
        val args = FfmpegMuxCommands.copySegment("/v.mp4", "/p.mp4", 1_000, 4_000)
        assertEquals("1.000", args[args.indexOf("-ss") + 1])
        assertEquals("4.000", args[args.indexOf("-to") + 1])
        assertEquals("copy", args[args.indexOf("-c") + 1])
    }
}
