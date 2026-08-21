package com.androidcompress.app.encode

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FfmpegEncodeProgressTest {

    @Test
    fun parsesNullMuxerProgressLine() {
        val line = "frame=  234 fps= 45 q=0.0 size=       0kB time=00:00:07.80 bitrate=N/A speed=1.5x"
        val snap = FfmpegEncodeProgress.parseLine(line)
        assertEquals(234, snap?.frame)
        assertEquals(7_800L, snap?.timeMs)
    }

    @Test
    fun ignoresNaTimeAndUsesFrames() {
        val line = "frame=    0 fps=0.0 q=0.0 size=       0kB time=N/A bitrate=N/A speed=N/A"
        val snap = FfmpegEncodeProgress.parseLine(line)
        assertEquals(0, snap?.frame)
        assertNull(snap?.timeMs)
        assertEquals(0L, FfmpegEncodeProgress.timeMs(0L, 0, 30f, 0L, 0))
        assertEquals(1_000L, FfmpegEncodeProgress.timeMs(0L, 30, 30f))
    }

    @Test
    fun prefersStatsTimeThenLogTimeThenFrames() {
        assertEquals(5_000L, FfmpegEncodeProgress.timeMs(5_000L, 1, 30f, 1_000L, 99))
        assertEquals(2_000L, FfmpegEncodeProgress.timeMs(0L, 1, 30f, 2_000L, 99))
        assertEquals(3_000L, FfmpegEncodeProgress.timeMs(0L, 90, 30f, 0L, 10))
    }

    @Test
    fun skipsNonProgressLogs() {
        assertNull(FfmpegEncodeProgress.parseLine("[libvpx-vp9 @ 0x1] v1.13.0"))
        assertNull(FfmpegEncodeProgress.parseLine("Stream mapping:"))
    }

    @Test
    fun usesLastProgressWhenCarriageReturnsStack() {
        val line = "frame=   10 time=00:00:00.30\rframe=   40 time=00:00:01.33"
        val snap = FfmpegEncodeProgress.parseLine(line)
        assertEquals(40, snap?.frame)
        assertEquals(1_330L, snap?.timeMs)
    }

    @Test
    fun parsesProgressFileDump() {
        val dump = """
            frame=234
            fps=45.0
            total_size=N/A
            out_time_us=7800000
            out_time_ms=7800000
            out_time=00:00:07.800000
            speed=1.5x
            progress=continue
        """.trimIndent()
        val snap = FfmpegEncodeProgress.parseDump(dump)
        assertEquals(234, snap?.frame)
        assertEquals(7_800L, snap?.timeMs)
        assertTrue(snap!!.continuing)
    }

    @Test
    fun progressContinueWithoutTimeStillCountsAsAlive() {
        val snap = FfmpegEncodeProgress.parseDump("frame=0\nout_time=N/A\nprogress=continue\n")
        assertEquals(0, snap?.frame)
        assertNull(snap?.timeMs)
        assertTrue(snap!!.continuing)
        assertFalse(FfmpegEncodeProgress.parseDump("progress=end\n")!!.continuing)
    }

    @Test
    fun injectsProgressBeforeOutput() {
        val args = FfmpegEncodeProgress.withProgressArg(
            listOf("-y", "-i", "in.webm", "-f", "null", "/dev/null"),
            "/cache/job.ffprogress",
        )
        assertEquals("/dev/null", args.last())
        assertEquals("/cache/job.ffprogress", args[args.indexOf("-progress") + 1])
    }

    @Test
    fun wallClockCapsAt95PercentOfDuration() {
        assertEquals(9_500L, FfmpegEncodeProgress.wallClockTimeMs(0L, 10_000L, 60_000L))
        assertEquals(4_000L, FfmpegEncodeProgress.wallClockTimeMs(1_000L, 60_000L, 5_000L))
    }
}
