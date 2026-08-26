package com.androidcompress.app.encode

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FfmpegMuxCommandsTest {

    @Test
    fun copyVideoAacKeepsVideoAndEncodesAac() {
        val args = FfmpegMuxCommands.copyVideoAac("/v.mp4", "/a.wav", "/out.mp4")
        assertEquals(
            listOf(
                "-y", "-hide_banner",
                "-i", "/v.mp4",
                "-i", "/a.wav",
                "-c:v", "copy",
                "-c:a", "aac",
                "-b:a", "128k",
                "-shortest",
                "-movflags", "+faststart",
                "/out.mp4",
            ),
            args,
        )
    }

    @Test
    fun mixMicAndInternalMapsVideoAndMixedAudio() {
        val args = FfmpegMuxCommands.mixMicAndInternalAac("/v.mp4", "/int.wav", "/mic.wav", "/out.mp4")
        assertTrue(args.containsAll(listOf("-i", "/v.mp4", "-i", "/int.wav", "-i", "/mic.wav")))
        assertEquals(FfmpegMuxCommands.MIX_FILTER, args[args.indexOf("-filter_complex") + 1])
        assertFalse(FfmpegMuxCommands.MIX_FILTER.contains(' '))
        assertEquals("0:v", args[args.indexOf("-map") + 1])
        assertEquals("[a]", args[args.lastIndexOf("-map") + 1])
        assertTrue(args.containsAll(listOf("-c:v", "copy", "-c:a", "aac", "-b:a", "160k")))
        assertEquals("/out.mp4", args.last())
    }

    @Test
    fun recordingPostProcessSkipsWhenNothingToDo() {
        assertEquals(
            null,
            FfmpegMuxCommands.recordingPostProcess(
                videoPath = "/v.mp4",
                outputPath = "/out.mp4",
            ),
        )
    }

    @Test
    fun recordingPostProcessDefaultMixMatchesLegacy() {
        val legacy = FfmpegMuxCommands.mixMicAndInternalAac("/v.mp4", "/int.wav", "/mic.wav", "/out.mp4")
        val next = FfmpegMuxCommands.recordingPostProcess(
            videoPath = "/v.mp4",
            outputPath = "/out.mp4",
            internalWav = "/int.wav",
            micWav = "/mic.wav",
        )
        assertEquals(legacy, next)
    }

    @Test
    fun mixFilterDuckHasNoSpacesAndUsesSidechain() {
        val filter = FfmpegMuxCommands.mixFilter(100, 80, duckAppAudio = true)
        assertFalse(filter.contains(' '))
        assertTrue(filter.contains("volume=0.80"))
        assertTrue(filter.contains("sidechaincompress"))
        assertTrue(filter.contains("[ducked][mic]amix"))
    }

    @Test
    fun recordingPostProcessIsolatesVoiceThenSystem() {
        val args = FfmpegMuxCommands.recordingPostProcess(
            videoPath = "/v.mp4",
            outputPath = "/out.mp4",
            internalWav = "/int.wav",
            micWav = "/mic.wav",
            isolateTracks = true,
            applyGain = false,
        )!!
        assertEquals("/mic.wav", args[args.indexOf("/v.mp4") + 2])
        assertEquals("/int.wav", args[args.indexOf("/mic.wav") + 2])
        assertFalse(args.contains("-filter_complex"))
        assertFalse(args.contains("amix"))
        val maps = args.mapIndexedNotNull { i, token ->
            if (token == "-map") args[i + 1] else null
        }
        assertEquals(listOf("0:v", "1:a", "2:a"), maps)
        assertEquals("copy", args[args.indexOf("-c:v") + 1])
        assertTrue(args.containsAll(listOf("-c:a", "aac")))
        assertEquals("title=${FfmpegMuxCommands.TRACK_TITLE_VOICE}", args[args.indexOf("-metadata:s:a:0") + 1])
        assertEquals("title=${FfmpegMuxCommands.TRACK_TITLE_SYSTEM}", args[args.indexOf("-metadata:s:a:1") + 1])
        assertEquals("default", args[args.indexOf("-disposition:a:0") + 1])
        assertEquals("0", args[args.indexOf("-disposition:a:1") + 1])
        assertEquals("/out.mp4", args.last())
    }

    @Test
    fun recordingPostProcessIsolateCropMapsLabeledVideo() {
        val args = FfmpegMuxCommands.recordingPostProcess(
            videoPath = "/v.mp4",
            outputPath = "/out.mp4",
            internalWav = "/int.wav",
            micWav = "/mic.wav",
            crop = RecordingCrop(10, 20, 640, 360),
            isolateTracks = true,
            applyGain = false,
            videoEncoder = "libopenh264",
            videoBitrateKbps = 2500,
        )!!
        assertEquals("[0:v]crop=640:360:10:20[v]", args[args.indexOf("-filter_complex") + 1])
        val maps = args.mapIndexedNotNull { i, token ->
            if (token == "-map") args[i + 1] else null
        }
        assertEquals(listOf("[v]", "1:a", "2:a"), maps)
        assertEquals("libopenh264", args[args.indexOf("-c:v") + 1])
        assertFalse(args.contains("amix"))
    }

    @Test
    fun recordingPostProcessCropsSoftwareH264() {
        val args = FfmpegMuxCommands.recordingPostProcess(
            videoPath = "/v.mp4",
            outputPath = "/out.mp4",
            crop = RecordingCrop(10, 20, 640, 360),
            videoEncoder = "libopenh264",
            videoBitrateKbps = 2500,
        )!!
        assertEquals("crop=640:360:10:20", args[args.indexOf("-vf") + 1])
        assertEquals("libopenh264", args[args.indexOf("-c:v") + 1])
        assertEquals("yuv420p", args[args.indexOf("-pix_fmt") + 1])
        assertFalse(args.contains("h264_mediacodec"))
    }

    @Test
    fun recordingPostProcessDrawsStatusCover() {
        val args = FfmpegMuxCommands.recordingPostProcess(
            videoPath = "/v.mp4",
            outputPath = "/out.mp4",
            coverTopPx = 80,
            videoEncoder = "libopenh264",
            videoBitrateKbps = 2500,
        )!!
        assertEquals(
            "drawbox=x=0:y=0:w=iw:h=80:color=black:t=fill",
            args[args.indexOf("-vf") + 1],
        )
        assertEquals("libopenh264", args[args.indexOf("-c:v") + 1])
        assertFalse(args[args.indexOf("-vf") + 1].contains(' '))
    }

    @Test
    fun recordingPostProcessCoverAfterCrop() {
        val args = FfmpegMuxCommands.recordingPostProcess(
            videoPath = "/v.mp4",
            outputPath = "/out.mp4",
            crop = RecordingCrop(10, 20, 640, 360),
            coverTopPx = 40,
            videoEncoder = "libopenh264",
            videoBitrateKbps = 2500,
        )!!
        assertEquals(
            "crop=640:360:10:20,drawbox=x=0:y=0:w=iw:h=40:color=black:t=fill",
            args[args.indexOf("-vf") + 1],
        )
    }

    @Test
    fun buildVideoFilterSkipsEmptyCover() {
        assertEquals(
            "crop=640:360:10:20",
            FfmpegMuxCommands.buildVideoFilter(RecordingCrop(10, 20, 640, 360), 0),
        )
        assertEquals(null, FfmpegMuxCommands.buildVideoFilter(null, 0))
        assertEquals(
            FfmpegMuxCommands.GRAYSCALE_FILTER,
            FfmpegMuxCommands.buildVideoFilter(null, 0, grayscale = true),
        )
        assertEquals(
            "crop=640:360:10:20,drawbox=x=0:y=0:w=iw:h=40:color=black:t=fill,${FfmpegMuxCommands.GRAYSCALE_FILTER}",
            FfmpegMuxCommands.buildVideoFilter(RecordingCrop(10, 20, 640, 360), 40, grayscale = true),
        )
    }

    @Test
    fun recordingPostProcessGrayscaleReencodes() {
        val args = FfmpegMuxCommands.recordingPostProcess(
            videoPath = "/v.mp4",
            outputPath = "/out.mp4",
            grayscale = true,
            videoEncoder = "libopenh264",
            videoBitrateKbps = 2500,
        )!!
        assertEquals(FfmpegMuxCommands.GRAYSCALE_FILTER, args[args.indexOf("-vf") + 1])
        assertEquals("libopenh264", args[args.indexOf("-c:v") + 1])
        assertFalse(args[args.indexOf("-vf") + 1].contains(' '))
    }

    @Test
    fun ensureGrayscaleMergesIntoLastVf() {
        val withVf = FfmpegMuxCommands.ensureGrayscale(
            listOf("-y", "-i", "in.mp4", "-vf", "hflip", "-c:v", "mpeg4", "out.mp4"),
            enabled = true,
        )
        assertEquals("hflip,${FfmpegMuxCommands.GRAYSCALE_FILTER}", withVf[withVf.indexOf("-vf") + 1])
        val already = FfmpegMuxCommands.ensureGrayscale(withVf, enabled = true)
        assertEquals(withVf, already)
        val laterVfWins = FfmpegMuxCommands.ensureGrayscale(
            listOf(
                "-y", "-i", "in.mp4",
                "-vf", "scale=1280:720,${FfmpegMuxCommands.GRAYSCALE_FILTER}",
                "-c:v", "mpeg4",
                "-vf", "hflip",
                "out.mp4",
            ),
            enabled = true,
        )
        assertEquals("hflip,${FfmpegMuxCommands.GRAYSCALE_FILTER}", laterVfWins[laterVfWins.lastIndexOf("-vf") + 1])
        val missing = FfmpegMuxCommands.ensureGrayscale(
            listOf("-y", "-i", "in.mp4", "-c:v", "mpeg4", "out.mp4"),
            enabled = true,
        )
        assertEquals(FfmpegMuxCommands.GRAYSCALE_FILTER, missing[missing.indexOf("-vf") + 1])
        assertEquals("out.mp4", missing.last())
        val audio = FfmpegMuxCommands.ensureGrayscale(
            listOf("-y", "-i", "in.mp4", "-vn", "-c:a", "aac", "out.m4a"),
            enabled = true,
        )
        assertFalse(audio.contains("-vf"))
        assertFalse(FfmpegMuxCommands.ensureGrayscale(audio, enabled = false).contains("-vf"))
        val complex = FfmpegMuxCommands.ensureGrayscale(
            listOf(
                "-y", "-i", "cover.jpg", "-i", "song.m4a",
                "-filter_complex", "[0:v]scale=1280:720:in_color_matrix=bt709:out_color_matrix=bt709:in_range=tv:out_range=tv,format=yuv420p[v]",
                "-map", "[v]", "-map", "1:a:0",
                "out.mp4",
            ),
            enabled = true,
        )
        val fc = complex[complex.indexOf("-filter_complex") + 1]
        assertTrue(fc.contains(FfmpegMuxCommands.GRAYSCALE_FILTER))
        assertTrue(fc.startsWith("[0:v]"))
        assertTrue(fc.endsWith("[v]"))
        assertFalse(fc.contains(' '))
        assertEquals(complex, FfmpegMuxCommands.ensureGrayscale(complex, enabled = true))
    }

    @Test
    fun extractPcmIsMono16k() {
        val args = FfmpegMuxCommands.extractPcmS16le("/in.mp4", "/out.pcm")
        assertEquals("pcm_s16le", args[args.indexOf("-acodec") + 1])
        assertEquals("1", args[args.indexOf("-ac") + 1])
        assertEquals("16000", args[args.indexOf("-ar") + 1])
        assertEquals("s16le", args[args.indexOf("-f") + 1])
        assertEquals("/out.pcm", args.last())
    }

    @Test
    fun applySubtitlesUsesMovTextOnMp4AndWebvttOnWebm() {
        val mp4 = FfmpegMuxCommands.applySubtitles("/v.mp4", "/c.srt", "/out.mp4")
        assertEquals("mov_text", mp4[mp4.indexOf("-c:s") + 1])
        assertTrue(mp4.contains("+faststart"))
        val webm = FfmpegMuxCommands.applySubtitles("/v.webm", "/c.srt", "/out.webm", containerWebm = true)
        assertEquals("webvtt", webm[webm.indexOf("-c:s") + 1])
        assertFalse(webm.contains("+faststart"))
        assertEquals("0:v?", webm[webm.indexOf("-map") + 1])
    }

    @Test
    fun burnCaptionsReencodesWithSubtitleFilterAndCopiesAudio() {
        val vf = FfmpegMuxCommands.subtitleBurnFilter("/data/job.srt")
        assertTrue(vf.startsWith("subtitles=filename="))
        assertTrue(vf.contains("charenc=UTF-8"))
        assertTrue(vf.contains("Alignment=2"))
        val args = FfmpegMuxCommands.burnCaptions(
            videoPath = "/v.mp4",
            outputPath = "/out.mp4",
            videoFilter = vf,
            videoEncoder = "libopenh264",
            videoBitrateKbps = 2500,
        )
        assertEquals(vf, args[args.indexOf("-vf") + 1])
        assertEquals("libopenh264", args[args.indexOf("-c:v") + 1])
        assertEquals("copy", args[args.indexOf("-c:a") + 1])
        assertEquals("0:v:0", args[args.indexOf("-map") + 1])
        assertTrue(args.contains("+faststart"))
        assertEquals("/out.mp4", args.last())
    }

    @Test
    fun burnCaptionsWebmSkipsFaststartAndTunesLibvpx() {
        val args = FfmpegMuxCommands.burnCaptions(
            videoPath = "/v.webm",
            outputPath = "/out.webm",
            videoFilter = "subtitles=file.srt",
            videoEncoder = "libvpx-vp9",
            videoBitrateKbps = 1800,
            containerWebm = true,
        )
        assertEquals("libvpx-vp9", args[args.indexOf("-c:v") + 1])
        assertEquals("good", args[args.indexOf("-deadline") + 1])
        assertFalse(args.contains("+faststart"))
        assertEquals("/out.webm", args.last())
    }

    @Test
    fun escapeFilterPathQuotesAndEscapesSpecials() {
        assertEquals("/data/job.srt", FfmpegMuxCommands.escapeFilterPath("/data/job.srt"))
        assertEquals("/tmp/a\\:b.srt", FfmpegMuxCommands.escapeFilterPath("/tmp/a:b.srt"))
        assertEquals("/tmp/it\\'s.srt", FfmpegMuxCommands.escapeFilterPath("/tmp/it's.srt"))
    }

    @Test
    fun drawTextBurnFilterUsesCueTimes() {
        val vf = FfmpegMuxCommands.drawTextBurnFilter(
            listOf(
                BurnCaptionCue(1.0, 2.5, "Hello there"),
                BurnCaptionCue(3.0, 4.0, "It's: fine"),
            ),
            "/system/fonts/Roboto-Regular.ttf",
        )
        assertNotNull(vf)
        assertTrue(vf!!.contains("enable='between(t,1.000,2.500)'"))
        assertTrue(vf.contains("enable='between(t,3.000,4.000)'"))
        assertTrue(vf.contains("text='Hello there'"))
        assertTrue(vf.contains("It\\'s\\: fine"))
        assertTrue(vf.startsWith("drawtext="))
        assertTrue(vf.contains(",drawtext="))
    }
}
