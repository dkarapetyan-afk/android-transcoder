package com.androidcompress.app.encode

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FfmpegSessionLogsTest {

    @Test
    fun emptyNullMuxerIsNoMedia() {
        val logs = """
            Stream mapping:
              Stream #0:0 -> #0:0 (vp8 (native) -> vp9 (libvpx-vp9))
            frame=    0 fps=0.0 q=0.0 Lsize=N/A time=N/A bitrate=N/A dup=604 drop=718
            video:0kB audio:0kB subtitle:0kB other streams:0kB global headers:0kB muxing overhead: unknown
            Output file is empty, nothing was encoded
        """.trimIndent()
        assertTrue(FfmpegSessionLogs.encodedNoMedia(logs))
    }

    @Test
    fun successfulPassHasMedia() {
        val logs = """
            Stream mapping:
              Stream #0:0 -> #0:0 (vp8 (native) -> vp9 (libvpx-vp9))
            frame= 1523 fps= 40 q=0.0 Lsize=N/A time=00:00:50.76 bitrate=N/A
            video:15432kB audio:0kB subtitle:0kB other streams:0kB global headers:0kB muxing overhead: unknown
        """.trimIndent()
        assertFalse(FfmpegSessionLogs.encodedNoMedia(logs))
    }

    @Test
    fun openFailureIsNotEmptyEncode() {
        val logs = """
            [matroska,webm @ 0x1] Format matroska,webm detected only with low score of 1, misdetection possible!
            [matroska,webm @ 0x1] EBML header parsing failed
            saf:1.webm: Invalid data found when processing input
        """.trimIndent()
        assertFalse(FfmpegSessionLogs.encodedNoMedia(logs))
    }
}
