package com.androidcompress.app.capture

import com.androidcompress.app.data.RecordAudioMode
import com.androidcompress.app.data.RecordResolution
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordOptionsTest {

    @Test
    fun jsonRoundTrip() {
        val original = RecordOptions(
            audioMode = RecordAudioMode.BOTH,
            resolution = RecordResolution.P720,
            countdownSeconds = 5,
            maxDurationMinutes = 10,
            autoStopLowStorage = false,
            showBubble = true,
            facecam = true,
            showTaps = true,
            captureRegion = true,
            region = RecordRegion(0.1f, 0.2f, 0.8f, 0.9f),
            directEncode = true,
            videoCodec = RecordVideoCodec.HEVC,
            internalAudioPackage = "com.example.game",
            internalAudioLabel = "Game",
            micGainPercent = 80,
            internalGainPercent = 120,
            duckAppAudio = true,
            frameRate = 60,
            videoBitrateKbps = 8000,
            container = RecordContainer.WEBM,
            echoCancel = false,
            noiseSuppress = false,
            micDevice = RecordMicDevice.BLUETOOTH,
            facecamLens = FacecamLens.BACK,
            facecamShape = FacecamShape.ROUND,
            facecamSize = FacecamSize.LARGE,
            facecamHideOnPause = false,
            showLaser = true,
            showAnnotation = true,
            pipControls = true,
            coverStatusBar = true,
            quietNotification = true,
            bookmarkMode = BookmarkMode.SPLIT,
        )
        val restored = RecordOptions.fromJson(original.toJson())
        assertEquals(original.audioMode, restored.audioMode)
        assertEquals(original.resolution, restored.resolution)
        assertEquals(5, restored.countdownSeconds)
        assertEquals(10, restored.maxDurationMinutes)
        assertTrue(restored.showBubble)
        assertTrue(restored.directEncode)
        assertEquals(RecordVideoCodec.HEVC, restored.videoCodec)
        assertEquals("com.example.game", restored.internalAudioPackage)
        assertEquals(80, restored.micGainPercent)
        assertEquals(120, restored.internalGainPercent)
        assertTrue(restored.duckAppAudio)
        assertEquals(60, restored.frameRate)
        assertEquals(8000, restored.videoBitrateKbps)
        assertEquals(RecordContainer.WEBM, restored.container)
        assertFalse(restored.echoCancel)
        assertEquals(RecordMicDevice.BLUETOOTH, restored.micDevice)
        assertEquals(FacecamLens.BACK, restored.facecamLens)
        assertEquals(FacecamShape.ROUND, restored.facecamShape)
        assertTrue(restored.showLaser)
        assertTrue(restored.pipControls)
        assertEquals(BookmarkMode.SPLIT, restored.bookmarkMode)
        val region = restored.region
        assertEquals(0.1f, region?.left ?: -1f, 0.001f)
        assertEquals(0.9f, region?.bottom ?: -1f, 0.001f)
    }

    @Test
    fun blankJsonIsDefaults() {
        val options = RecordOptions.fromJson(" ")
        assertEquals(RecordAudioMode.NONE, options.audioMode)
        assertTrue(options.autoStopLowStorage)
        assertFalse(options.directEncode)
    }

    @Test
    fun fullScreenRegionHasNoCrop() {
        assertNull(RecordRegion.FULL.encoderCrop(1920, 1080))
    }

    @Test
    fun encoderCropIsEvenAndMapped() {
        val crop = RecordRegion(0.25f, 0.25f, 0.75f, 0.75f).encoderCrop(1920, 1080)!!
        assertEquals(0, crop.x % 2)
        assertEquals(0, crop.y % 2)
        assertEquals(0, crop.width % 2)
        assertEquals(0, crop.height % 2)
        assertEquals(480, crop.x)
        assertEquals(270, crop.y)
        assertEquals(960, crop.width)
        assertEquals(540, crop.height)
    }

    @Test
    fun liveEncoderCropAlignsTo16() {
        val crop = RecordRegion(0.25f, 0.25f, 0.75f, 0.75f).liveEncoderCrop(1920, 1080)!!
        assertEquals(0, crop.width % 16)
        assertEquals(0, crop.height % 16)
        assertTrue(crop.width >= 16)
        assertTrue(crop.height >= 16)
    }

    @Test
    fun overrideBitrateWins() {
        val bps = RecordVideoCodec.H264.videoBitrate(1920, 1080, true, overrideKbps = 8000, frameRate = 60)
        assertEquals(8_000_000, bps)
    }

    @Test
    fun maxDurationMs() {
        assertNull(RecordOptions().maxDurationMs)
        assertEquals(180_000L, RecordOptions(maxDurationMinutes = 3).maxDurationMs)
    }

    @Test
    fun storageGuard() {
        assertTrue(StorageGuard.shouldAutoStop(50L * 1024 * 1024))
        assertFalse(StorageGuard.shouldAutoStop(500L * 1024 * 1024))
        assertFalse(StorageGuard.shouldAutoStop(-1))
        assertFalse(StorageGuard.shouldAutoStop(0))
    }

    @Test
    fun autoStopWaitsForDataBeforeStorage() {
        assertEquals(
            null,
            StorageGuard.reason(
                elapsedMs = 1_000L,
                maxDurationMs = null,
                lowStorageEnabled = true,
                availableBytes = 10L * 1024 * 1024,
                recordedBytes = 0L,
            ),
        )
        assertEquals(
            RecordAutoStop.STORAGE,
            StorageGuard.reason(
                elapsedMs = 4_000L,
                maxDurationMs = null,
                lowStorageEnabled = true,
                availableBytes = 10L * 1024 * 1024,
                recordedBytes = 8_000L,
            ),
        )
        assertEquals(
            RecordAutoStop.DURATION,
            StorageGuard.reason(
                elapsedMs = 60_000L,
                maxDurationMs = 60_000L,
                lowStorageEnabled = false,
                availableBytes = Long.MAX_VALUE,
                recordedBytes = 0L,
            ),
        )
    }
}
