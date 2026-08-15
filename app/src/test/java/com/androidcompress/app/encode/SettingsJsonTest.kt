package com.androidcompress.app.encode

import com.androidcompress.app.data.AudioOption
import com.androidcompress.app.data.BFrameSetting
import com.androidcompress.app.data.BitrateMode
import com.androidcompress.app.data.EncodeEngine
import com.androidcompress.app.data.EncodeSettings
import com.androidcompress.app.data.H264Profile
import com.androidcompress.app.data.HdrMode
import com.androidcompress.app.data.KeyframeInterval
import com.androidcompress.app.data.Preset
import com.androidcompress.app.data.SettingsJson
import com.androidcompress.app.data.VideoCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsJsonTest {
    @Test
    fun roundTrip() {
        val original = EncodeSettings(
            preset = Preset.SMALLER,
            maxHeight = 720,
            fpsCap = 24,
            codec = VideoCodec.H264,
            preferHardware = false,
            videoBitrateKbps = 900,
            audio = AudioOption.MUTE,
            engine = EncodeEngine.MEDIA3,
            bitrateMode = BitrateMode.VBR,
            keyframeInterval = KeyframeInterval.SEC_2,
            h264Profile = H264Profile.HIGH,
            hdrMode = HdrMode.TONE_MAP,
            audioVolumePercent = 150,
            fastStart = false,
            bFrames = BFrameSetting.TWO,
            ffmpegExtraArgs = "-vf hflip",
            ffmpegCommandOverride = "-y -hide_banner -i INPUT -c:v h264_mediacodec -b:v 900k OUTPUT",
        )
        val decoded = SettingsJson.decode(SettingsJson.encode(original))
        assertEquals(original, decoded)
    }

    @Test
    fun nullHeightSurvives() {
        val original = EncodeSettings.forPreset(Preset.HIGHER).copy(maxHeight = null, fpsCap = null)
        val decoded = SettingsJson.decode(SettingsJson.encode(original))
        assertNull(decoded.maxHeight)
        assertNull(decoded.fpsCap)
        assertEquals(EncodeEngine.FFMPEG, decoded.engine)
    }

    @Test
    fun missingEngineDefaultsToFfmpeg() {
        val raw = """
            {"preset":"BALANCED","maxHeight":1080,"fpsCap":30,"codec":"H264",
             "preferHardware":true,"videoBitrateKbps":2500,"audio":"AAC_128"}
        """.trimIndent()
        val decoded = SettingsJson.decode(raw)
        assertEquals(EncodeEngine.FFMPEG, decoded.engine)
        assertEquals(BitrateMode.CBR, decoded.bitrateMode)
        assertEquals(KeyframeInterval.AUTO, decoded.keyframeInterval)
        assertEquals(100, decoded.audioVolumePercent)
        assertTrue(decoded.fastStart)
    }
}
