package com.androidcompress.app.agent

import com.androidcompress.app.data.AudioOption
import com.androidcompress.app.data.BFrameSetting
import com.androidcompress.app.data.BitrateMode
import com.androidcompress.app.data.CompressJob
import com.androidcompress.app.data.ContainerFormat
import com.androidcompress.app.data.EncodeEngine
import com.androidcompress.app.data.EncodeSettings
import com.androidcompress.app.data.H264Profile
import com.androidcompress.app.data.HdrMode
import com.androidcompress.app.data.JobStatus
import com.androidcompress.app.data.JobType
import com.androidcompress.app.data.KeyframeInterval
import com.androidcompress.app.data.OutputMode
import com.androidcompress.app.data.Preset
import com.androidcompress.app.data.VideoCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JobSettingsCodecTest {

    @Test
    fun emptyPatchLeavesSettingsAlone() {
        val base = EncodeSettings.forPreset(Preset.BALANCED)
        assertEquals(base, JobSettingsCodec.apply(base, SettingsPatch()))
    }

    @Test
    fun presetResetsThenOverlayApplies() {
        val base = EncodeSettings.forPreset(Preset.BALANCED).copy(videoBitrateKbps = 9999)
        val next = JobSettingsCodec.apply(
            base,
            SettingsPatch(
                preset = Preset.SMALLER,
                engine = EncodeEngine.MEDIA3,
                videoBitrateKbps = 1800,
                container = ContainerFormat.WEBM,
            ),
        )
        assertEquals(Preset.SMALLER, next.preset)
        assertEquals(EncodeEngine.MEDIA3, next.engine)
        assertEquals(720, next.maxHeight)
        assertEquals(1800, next.videoBitrateKbps)
        assertEquals(ContainerFormat.WEBM, next.container)
        assertEquals(VideoCodec.VP9, next.codec)
    }

    @Test
    fun clearsHeightFpsClipAndOverride() {
        val base = EncodeSettings.forPreset(Preset.BALANCED).copy(
            ffmpegCommandOverride = "-y -i INPUT OUTPUT",
            clipStartMs = 1_000,
            clipEndMs = 5_000,
        )
        val next = JobSettingsCodec.apply(
            base,
            SettingsPatch(
                clearMaxHeight = true,
                clearFpsCap = true,
                clearCommandOverride = true,
                clearClip = true,
            ),
        )
        assertNull(next.maxHeight)
        assertNull(next.fpsCap)
        assertEquals("", next.ffmpegCommandOverride)
        assertEquals(0L, next.clipStartMs)
        assertNull(next.clipEndMs)
    }

    @Test
    fun sanitizesExtraArgs() {
        val next = JobSettingsCodec.apply(
            EncodeSettings.forPreset(Preset.BALANCED),
            SettingsPatch(ffmpegExtraArgs = "-vf hflip"),
        )
        assertEquals("-vf hflip", next.ffmpegExtraArgs)
    }

    @Test(expected = IllegalStateException::class)
    fun rejectsBlockedExtraArgs() {
        JobSettingsCodec.apply(
            EncodeSettings.forPreset(Preset.BALANCED),
            SettingsPatch(ffmpegExtraArgs = "-i other.mp4"),
        )
    }

    @Test(expected = IllegalStateException::class)
    fun rejectsBadBitrate() {
        JobSettingsCodec.apply(
            EncodeSettings.forPreset(Preset.BALANCED),
            SettingsPatch(videoBitrateKbps = 12),
        )
    }

    @Test
    fun parsesEnumAliases() {
        assertEquals(EncodeEngine.MEDIA3, JobSettingsCodec.parseEngine("media3"))
        assertEquals(Preset.SMALLER, JobSettingsCodec.parsePreset("smaller"))
        assertEquals(ContainerFormat.WEBM, JobSettingsCodec.parseContainer("webm"))
        assertNull(JobSettingsCodec.parseCodec("x264"))
    }

    @Test
    fun patchFromUpdateMapsStrings() {
        val patch = JobSettingsCodec.patchFromUpdate(
            JobSettingsUpdate(
                preset = "higher",
                engine = "ffmpeg",
                output = "AUDIO",
                audio = "aac_96",
                bitrateMode = "vbr",
                keyframeInterval = "sec_2",
                h264Profile = "high",
                hdrMode = "tone_map",
                bFrames = "none",
                preferHardware = false,
                fastStart = false,
                audioVolumePercent = 150,
            ),
        )
        val next = JobSettingsCodec.apply(EncodeSettings.forPreset(Preset.BALANCED), patch)
        assertEquals(Preset.HIGHER, next.preset)
        assertEquals(OutputMode.AUDIO, next.output)
        assertEquals(AudioOption.AAC_96, next.audio)
        assertEquals(BitrateMode.VBR, next.bitrateMode)
        assertEquals(KeyframeInterval.SEC_2, next.keyframeInterval)
        assertEquals(H264Profile.HIGH, next.h264Profile)
        assertEquals(HdrMode.TONE_MAP, next.hdrMode)
        assertEquals(BFrameSetting.NONE, next.bFrames)
        assertFalse(next.preferHardware)
        assertFalse(next.fastStart)
        assertEquals(150, next.audioVolumePercent)
    }

    @Test
    fun snapshotRoundTripNames() {
        val settings = EncodeSettings.forPreset(Preset.HIGHER, EncodeEngine.MEDIA3).copy(
            output = OutputMode.AUDIO,
            container = ContainerFormat.WEBM,
        )
        val snap = JobSettingsCodec.snapshot(settings)
        assertEquals("HIGHER", snap.preset)
        assertEquals("MEDIA3", snap.engine)
        assertEquals("AUDIO", snap.output)
        assertEquals("WEBM", snap.container)
        assertEquals(1440, snap.maxHeight)
    }

    @Test
    fun startAndEditGates() {
        val ready = job(JobStatus.READY)
        val running = job(JobStatus.RUNNING)
        assertTrue(JobSettingsCodec.canStart(ready))
        assertTrue(JobSettingsCodec.canEdit(ready.status))
        assertFalse(JobSettingsCodec.canStart(running))
        assertFalse(JobSettingsCodec.canEdit(running.status))
        assertFalse(JobSettingsCodec.canStart(job(JobStatus.READY, sourceUri = "")))
    }

    private fun job(status: JobStatus, sourceUri: String = "content://src") = CompressJob(
        id = "job",
        type = JobType.IMPORT,
        status = status,
        sourceUri = sourceUri,
        outputUri = null,
        displayName = "clip.mp4",
        sourceBytes = 1,
        outputBytes = null,
        durationMs = 1_000,
        width = 1920,
        height = 1080,
        settingsJson = "{}",
        error = null,
        createdAt = 1,
        finishedAt = null,
    )
}
