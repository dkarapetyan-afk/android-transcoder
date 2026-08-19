package com.androidcompress.app.encode

import com.androidcompress.app.data.EncoderCapabilities
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HardwareProfileTest {

    @Test
    fun targetsIncludeDetectedFfmpegEncodersAndMedia3() {
        val targets = HardwareProfilePlan.targets(
            EncoderCapabilities(
                hasH264MediaCodec = true,
                hasHevcMediaCodec = true,
                hasVp9MediaCodec = true,
                hasAv1MediaCodec = true,
            ),
        )
        assertEquals(
            listOf("h264_mediacodec", "hevc_mediacodec", "vp9_mediacodec", "av1_mediacodec", "media3", "media3_av1"),
            targets.map { it.id },
        )
        assertEquals(HardwareProfiles.MIME_AV1, targets.first { it.id == "av1_mediacodec" }.mime)
        assertEquals("mp4", targets.first { it.id == "av1_mediacodec" }.extension)
        assertEquals(HardwareProfiles.MIME_AV1, targets.first { it.id == "media3_av1" }.mime)
        assertEquals(HardwareProfiles.MIME_AVC, targets.first { it.id == "media3" }.mime)
        assertEquals("webm", targets.first { it.id == "vp9_mediacodec" }.extension)
    }

    @Test
    fun targetsAlwaysIncludeMedia3() {
        val targets = HardwareProfilePlan.targets(EncoderCapabilities())
        assertEquals(listOf("media3"), targets.map { it.id })
    }

    @Test
    fun media3UsesHevcWhenH264IsMissing() {
        val targets = HardwareProfilePlan.targets(EncoderCapabilities(hasHevcMediaCodec = true))
        assertEquals(HardwareProfiles.MIME_HEVC, targets.single { it.id == "media3" }.mime)
    }

    @Test
    fun av1OnlyDoesNotDuplicateMedia3Target() {
        val targets = HardwareProfilePlan.targets(EncoderCapabilities(hasAv1MediaCodec = true))
        assertEquals(listOf("av1_mediacodec", "media3"), targets.map { it.id })
        assertEquals(HardwareProfiles.MIME_AV1, targets.single { it.id == "media3" }.mime)
        assertEquals("mp4", targets.single { it.id == "media3" }.extension)
    }

    @Test
    fun sizesStayAtOrBelowAdvertisedMax() {
        val sizes = HardwareProfilePlan.sizesFor(1920, 1080)
        assertEquals(
            listOf(HardwareSize(1920, 1080), HardwareSize(1280, 720), HardwareSize(854, 480)),
            sizes,
        )
    }

    @Test
    fun sizesFallBackWhenAdvertisedIsTiny() {
        val sizes = HardwareProfilePlan.sizesFor(640, 360)
        assertEquals(listOf(HardwareSize(640, 360)), sizes)
    }

    @Test
    fun bitrateScalesWithResolution() {
        assertEquals(25_000, HardwareProfilePlan.bitrateKbps(3840, 2160))
        assertEquals(8_000, HardwareProfilePlan.bitrateKbps(1920, 1080))
        assertEquals(2_000, HardwareProfilePlan.bitrateKbps(640, 360))
    }

    @Test
    fun speedIsSourceOverWallClock() {
        assertEquals(4.0f, HardwareProfilePlan.speedX(1_000, 250), 0.001f)
        assertEquals(0.5f, HardwareProfilePlan.speedX(1_000, 2_000), 0.001f)
        assertEquals(0f, HardwareProfilePlan.speedX(1_000, 0), 0.001f)
    }

    @Test
    fun tenBitAndHdrProfiles() {
        assertTrue(HardwareProfiles.isTenBit(HardwareProfiles.MIME_AVC, HardwareProfiles.AVC_HIGH10))
        assertFalse(HardwareProfiles.isTenBit(HardwareProfiles.MIME_AVC, 8))
        assertTrue(HardwareProfiles.isTenBit(HardwareProfiles.MIME_HEVC, HardwareProfiles.HEVC_MAIN10_HDR10))
        assertTrue(HardwareProfiles.isHdr(HardwareProfiles.MIME_HEVC, HardwareProfiles.HEVC_MAIN10_HDR10))
        assertFalse(HardwareProfiles.isHdr(HardwareProfiles.MIME_HEVC, HardwareProfiles.HEVC_MAIN10))
        assertTrue(HardwareProfiles.isTenBit(HardwareProfiles.MIME_VP9, HardwareProfiles.VP9_PROFILE2))
        assertTrue(HardwareProfiles.isHdr(HardwareProfiles.MIME_VP9, HardwareProfiles.VP9_PROFILE2_HDR))
        assertTrue(HardwareProfiles.isTenBit(HardwareProfiles.MIME_AV1, HardwareProfiles.AV1_MAIN10))
        assertTrue(HardwareProfiles.isHdr(HardwareProfiles.MIME_AV1, HardwareProfiles.AV1_MAIN10_HDR10))
        assertFalse(HardwareProfiles.isHdr(HardwareProfiles.MIME_AV1, HardwareProfiles.AV1_MAIN10))
        assertFalse(HardwareProfiles.isTenBit(HardwareProfiles.MIME_AV1, HardwareProfiles.AV1_MAIN8))
        assertTrue(HardwareProfiles.isTenBitColor(HardwareProfiles.COLOR_YUV_P010))
        assertFalse(HardwareProfiles.isTenBitColor(21))
    }

    @Test
    fun advertisedCapsKeepLargestAndAnyTenBit() {
        val small = HardwareAdvertisedCaps("small", 1280, 720, tenBit = true, hdr = false)
        val large = HardwareAdvertisedCaps("large", 3840, 2160, tenBit = false, hdr = true)
        val merged = HardwareCodecProbe.merge(small, large)
        assertEquals("large", merged.encoderName)
        assertEquals(3840, merged.maxWidth)
        assertTrue(merged.tenBit)
        assertTrue(merged.hdr)
    }

    @Test
    fun reportRoundTripsThroughJson() {
        val report = HardwareProfileReport(
            startedAt = 10L,
            finishedAt = 20L,
            cancelled = false,
            results = listOf(
                HardwareEncoderResult(
                    targetId = "h264_mediacodec",
                    displayName = "h264_mediacodec",
                    available = true,
                    codecName = "c2.qti.avc.encoder",
                    advertisedMaxWidth = 3840,
                    advertisedMaxHeight = 2160,
                    verifiedMaxWidth = 1920,
                    verifiedMaxHeight = 1080,
                    advertisedTenBit = false,
                    advertisedHdr = false,
                    tenBitVerified = null,
                    speedX = 3.5f,
                    encodeMs = 286L,
                    error = null,
                ),
            ),
        )
        val decoded = HardwareProfileJson.decode(HardwareProfileJson.encode(report))
        assertEquals(report.startedAt, decoded?.startedAt)
        assertEquals(1, decoded?.results?.size)
        assertEquals(1920, decoded?.results?.single()?.verifiedMaxWidth)
        assertEquals(3.5f, decoded?.results?.single()?.speedX)
        assertNull(decoded?.results?.single()?.tenBitVerified)
    }
}
