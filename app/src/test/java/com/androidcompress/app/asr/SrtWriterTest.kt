package com.androidcompress.app.asr

import com.androidcompress.app.data.AudioOption
import com.androidcompress.app.data.EncodeSettings
import com.androidcompress.app.data.Preset
import com.androidcompress.app.data.OutputMode
import com.androidcompress.app.data.wantsBurnCaptions
import com.androidcompress.app.data.wantsCaptions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SrtWriterTest {
    @Test
    fun timestampUsesCommaMillis() {
        assertEquals("00:00:00,000", SrtWriter.timestamp(0.0))
        assertEquals("00:00:01,250", SrtWriter.timestamp(1.25))
        assertEquals("01:02:03,004", SrtWriter.timestamp(3723.004))
    }

    @Test
    fun renderNumbersCues() {
        val srt = SrtWriter.render(
            listOf(
                CaptionCue(1.0, 2.5, "Hello"),
                CaptionCue(3.0, 4.0, "World"),
            ),
        )
        assertTrue(srt.startsWith("1\n00:00:01,000 --> 00:00:02,500\nHello\n"))
        assertTrue(srt.contains("2\n00:00:03,000 --> 00:00:04,000\nWorld\n"))
    }

    @Test
    fun usableTextDropsWhisperJunk() {
        assertEquals("Hello there", SrtWriter.usableText("  Hello   there "))
        assertNull(SrtWriter.usableText(" "))
        assertNull(SrtWriter.usableText("."))
        assertNull(SrtWriter.usableText("The."))
    }

    @Test
    fun muteSkipsCaptions() {
        val on = EncodeSettings.forPreset(Preset.BALANCED).copy(captions = true)
        assertTrue(on.wantsCaptions())
        assertFalse(on.copy(audio = AudioOption.MUTE).wantsCaptions())
        assertFalse(EncodeSettings.forPreset(Preset.BALANCED).wantsCaptions())
    }

    @Test
    fun burnNeedsCaptionsAndVideo() {
        val on = EncodeSettings.forPreset(Preset.BALANCED).copy(captions = true, burnCaptions = true)
        assertTrue(on.wantsBurnCaptions())
        assertFalse(on.copy(captions = false).wantsBurnCaptions())
        assertFalse(on.copy(audio = AudioOption.MUTE).wantsBurnCaptions())
        assertFalse(on.copy(output = OutputMode.AUDIO).wantsBurnCaptions())
    }
}
