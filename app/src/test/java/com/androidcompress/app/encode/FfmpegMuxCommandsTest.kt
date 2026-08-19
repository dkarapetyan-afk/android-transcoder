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
}
