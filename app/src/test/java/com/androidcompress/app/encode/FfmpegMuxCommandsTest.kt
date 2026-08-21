package com.androidcompress.app.encode

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
}
