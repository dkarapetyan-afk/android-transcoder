package com.androidcompress.app.encode

import com.androidcompress.app.data.EncodeSettings
import com.androidcompress.app.data.EncoderCapabilities
import com.androidcompress.app.data.Preset
import com.androidcompress.app.data.SourceVideo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FfmpegCommandTemplateTest {

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

    @Test
    fun fromArgsUsesPlaceholders() {
        val plan = FfmpegCommandBuilder.build(
            "/tmp/in.mp4",
            "/tmp/out.mp4",
            EncodeSettings.forPreset(Preset.BALANCED),
            source,
            EncoderCapabilities(hasH264MediaCodec = true, hasMpeg4 = true),
        )
        val template = FfmpegCommandTemplate.fromArgs(plan.args)
        assertTrue(template.contains("-i INPUT"))
        assertTrue(template.endsWith("OUTPUT"))
        assertFalse(template.contains("/tmp/in.mp4"))
        assertFalse(template.contains("/tmp/out.mp4"))
    }

    @Test
    fun materializeRestoresAppPaths() {
        val args = FfmpegCommandTemplate.materialize(
            "-y -hide_banner -i INPUT -c:v h264_mediacodec -b:v 1800k OUTPUT",
            "/data/in.mp4",
            "/data/out.mp4",
        ).getOrThrow()
        assertEquals("/data/in.mp4", args[args.indexOf("-i") + 1])
        assertEquals("/data/out.mp4", args.last())
        assertEquals("1800k", args[args.indexOf("-b:v") + 1])
    }

    @Test
    fun editedBitrateIsKept() {
        val generated = FfmpegCommandTemplate.fromArgs(
            listOf("-y", "-i", "in.mp4", "-c:v", "h264_mediacodec", "-b:v", "2500k", "out.mp4"),
        )
        val edited = generated.replace("2500k", "900k")
        val args = FfmpegCommandTemplate.materialize(edited, "in.mp4", "out.mp4").getOrThrow()
        assertEquals("900k", args[args.indexOf("-b:v") + 1])
    }

    @Test
    fun rejectsSecondInput() {
        val parsed = FfmpegCommandTemplate.parse(
            "-i INPUT -i other.mp4 -c:v h264_mediacodec OUTPUT",
        )
        assertFalse(parsed.isValid)
        val error = requireNotNull(parsed.error)
        assertTrue(error.contains("AUDIO") || error.contains("INPUT"))
    }

    @Test
    fun allowsAudioPlaceholder() {
        val parsed = FfmpegCommandTemplate.parse(
            "-loop 1 -framerate 30 -i INPUT -i AUDIO -map 0:v:0 -map 1:a:0 -c:v h264_mediacodec OUTPUT",
        )
        assertTrue(parsed.error ?: "", parsed.isValid)
        val args = FfmpegCommandTemplate.materialize(
            parsed.canonical,
            "cover.jpg",
            "out.mp4",
            "song.m4a",
        ).getOrThrow()
        assertEquals("cover.jpg", args[args.indexOf("-i") + 1])
        assertEquals("song.m4a", args[args.lastIndexOf("-i") + 1])
    }

    @Test
    fun rejectsExternalFileFilter() {
        val parsed = FfmpegCommandTemplate.parse("-i INPUT -vf movie=/sdcard/x.mp4 OUTPUT")
        assertFalse(parsed.isValid)
    }

    @Test
    fun roundTripGeneratedCommand() {
        val plan = FfmpegCommandBuilder.build(
            "in.mp4",
            "out.mp4",
            EncodeSettings.forPreset(Preset.SMALLER),
            source,
            EncoderCapabilities(hasH264MediaCodec = true, hasMpeg4 = true),
        )
        val template = FfmpegCommandTemplate.fromArgs(plan.args)
        val restored = FfmpegCommandTemplate.materialize(template, "in.mp4", "out.mp4").getOrThrow()
        assertEquals(plan.args, restored)
    }
}
