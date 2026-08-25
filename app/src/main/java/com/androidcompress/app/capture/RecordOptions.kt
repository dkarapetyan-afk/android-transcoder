package com.androidcompress.app.capture

import android.media.MediaRecorder
import android.os.Build
import com.androidcompress.app.data.RecordAudioMode
import com.androidcompress.app.data.RecordResolution
import com.androidcompress.app.encode.RecordingCrop
import com.androidcompress.app.util.even
import org.json.JSONObject
import kotlin.math.roundToInt

enum class RecordVideoCodec {
    H264, HEVC, AV1;

    fun mediaRecorderValue(): Int = when (this) {
        H264 -> MediaRecorder.VideoEncoder.H264
        HEVC -> MediaRecorder.VideoEncoder.HEVC
        AV1 -> if (Build.VERSION.SDK_INT >= 33) MediaRecorder.VideoEncoder.AV1 else MediaRecorder.VideoEncoder.HEVC
    }
}

enum class RecordContainer { MP4, WEBM }

enum class RecordMicDevice { AUTO, BUILTIN, BLUETOOTH }

enum class FacecamLens { FRONT, BACK }

enum class FacecamShape { RECT, ROUND }

enum class FacecamSize { SMALL, MEDIUM, LARGE }

enum class BookmarkMode { OFF, CHAPTERS, SPLIT }

enum class RecordPhase {
    IDLE, REGION, COUNTDOWN, RECORDING, SAVING
}

data class RecordRegion(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val width: Float get() = (right - left).coerceAtLeast(0f)
    val height: Float get() = (bottom - top).coerceAtLeast(0f)

    val isFullScreen: Boolean
        get() = left <= FULL_EPS && top <= FULL_EPS && right >= 1f - FULL_EPS && bottom >= 1f - FULL_EPS

    fun sanitized(): RecordRegion {
        val l = left.coerceIn(0f, 1f)
        val t = top.coerceIn(0f, 1f)
        val r = right.coerceIn(0f, 1f).coerceAtLeast(l)
        val b = bottom.coerceIn(0f, 1f).coerceAtLeast(t)
        return RecordRegion(l, t, r, b)
    }

    fun encoderCrop(encodeWidth: Int, encodeHeight: Int): RecordingCrop? {
        if (isFullScreen) return null
        val w0 = even(encodeWidth.coerceAtLeast(2))
        val h0 = even(encodeHeight.coerceAtLeast(2))
        var x = even((left * w0).roundToInt().coerceIn(0, w0 - 2))
        var y = even((top * h0).roundToInt().coerceIn(0, h0 - 2))
        var w = even((width * w0).roundToInt().coerceAtLeast(2))
        var h = even((height * h0).roundToInt().coerceAtLeast(2))
        if (x + w > w0) w = even(w0 - x)
        if (y + h > h0) h = even(h0 - y)
        if (w < 2 || h < 2) return null
        if (w >= w0 - 2 && h >= h0 - 2) return null
        return RecordingCrop(x, y, w, h)
    }

    /** Crop aligned to 16px so MediaRecorder can encode the region live. */
    fun liveEncoderCrop(encodeWidth: Int, encodeHeight: Int): RecordingCrop? {
        val crop = encoderCrop(encodeWidth, encodeHeight) ?: return null
        var w = (crop.width and 15.inv()).coerceAtLeast(16)
        var h = (crop.height and 15.inv()).coerceAtLeast(16)
        if (w > encodeWidth) w = even(encodeWidth)
        if (h > encodeHeight) h = even(encodeHeight)
        var x = crop.x.coerceAtMost((encodeWidth - w).coerceAtLeast(0))
        var y = crop.y.coerceAtMost((encodeHeight - h).coerceAtLeast(0))
        x = even(x.coerceAtLeast(0))
        y = even(y.coerceAtLeast(0))
        if (x + w > encodeWidth) w = even(encodeWidth - x).coerceAtLeast(16)
        if (y + h > encodeHeight) h = even(encodeHeight - y).coerceAtLeast(16)
        if (w < 16 || h < 16) return crop
        if (w >= encodeWidth - 2 && h >= encodeHeight - 2) return null
        return RecordingCrop(x, y, w, h)
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("l", left.toDouble())
        put("t", top.toDouble())
        put("r", right.toDouble())
        put("b", bottom.toDouble())
    }

    companion object {
        private const val FULL_EPS = 0.01f
        val FULL = RecordRegion(0f, 0f, 1f, 1f)

        fun fromJson(obj: JSONObject?): RecordRegion? {
            if (obj == null) return null
            return runCatching {
                RecordRegion(
                    left = obj.getDouble("l").toFloat(),
                    top = obj.getDouble("t").toFloat(),
                    right = obj.getDouble("r").toFloat(),
                    bottom = obj.getDouble("b").toFloat(),
                ).sanitized()
            }.getOrNull()
        }
    }
}

data class RecordOptions(
    val audioMode: RecordAudioMode = RecordAudioMode.NONE,
    val resolution: RecordResolution = RecordResolution.P1080,
    val countdownSeconds: Int = 0,
    val maxDurationMinutes: Int = 0,
    val autoStopLowStorage: Boolean = true,
    val showBubble: Boolean = false,
    val facecam: Boolean = false,
    val showTaps: Boolean = false,
    val captureRegion: Boolean = false,
    val region: RecordRegion? = null,
    val directEncode: Boolean = false,
    val videoCodec: RecordVideoCodec = RecordVideoCodec.H264,
    val internalAudioPackage: String = "",
    val internalAudioLabel: String = "",
    val micGainPercent: Int = 100,
    val internalGainPercent: Int = 100,
    val duckAppAudio: Boolean = false,
    val isolateAudioTracks: Boolean = false,
    val frameRate: Int = 30,
    val videoBitrateKbps: Int = 0,
    val container: RecordContainer = RecordContainer.MP4,
    val echoCancel: Boolean = true,
    val noiseSuppress: Boolean = true,
    val micDevice: RecordMicDevice = RecordMicDevice.AUTO,
    val facecamLens: FacecamLens = FacecamLens.FRONT,
    val facecamShape: FacecamShape = FacecamShape.RECT,
    val facecamSize: FacecamSize = FacecamSize.MEDIUM,
    val facecamHideOnPause: Boolean = true,
    val showLaser: Boolean = false,
    val showAnnotation: Boolean = false,
    val pipControls: Boolean = false,
    val coverStatusBar: Boolean = false,
    val grayscale: Boolean = false,
    val captions: Boolean = false,
    val quietNotification: Boolean = false,
    val bookmarkMode: BookmarkMode = BookmarkMode.CHAPTERS,
) {
    val needsOverlay: Boolean get() = showBubble || facecam || captureRegion
    val needsCamera: Boolean get() = facecam
    val needsPointerOverlay: Boolean get() = showTaps || showLaser || showAnnotation
    val usesWebm: Boolean get() = container == RecordContainer.WEBM
    val maxDurationMs: Long? get() = maxDurationMinutes.takeIf { it > 0 }?.let { it * 60_000L }
    val outputExtension: String get() = if (usesWebm) "webm" else "mp4"
    val outputMime: String get() = if (usesWebm) "video/webm" else "video/mp4"

    fun resolvedForSdk(sdk: Int): RecordOptions = copy(
        audioMode = audioMode.resolvedForSdk(sdk),
        countdownSeconds = countdownSeconds.coerceIn(0, 15),
        maxDurationMinutes = maxDurationMinutes.coerceIn(0, 180),
        micGainPercent = micGainPercent.coerceIn(0, 200),
        internalGainPercent = internalGainPercent.coerceIn(0, 200),
        frameRate = if (frameRate >= 45) 60 else 30,
        videoBitrateKbps = if (videoBitrateKbps <= 0) 0 else videoBitrateKbps.coerceIn(1_000, 50_000),
        micDevice = if (sdk < 23) RecordMicDevice.AUTO else micDevice,
    )

    fun toJson(): String = JSONObject().apply {
        put("audio", audioMode.name)
        put("res", resolution.name)
        put("countdown", countdownSeconds)
        put("maxMin", maxDurationMinutes)
        put("lowStorage", autoStopLowStorage)
        put("bubble", showBubble)
        put("facecam", facecam)
        put("taps", showTaps)
        put("regionOn", captureRegion)
        put("region", region?.toJson() ?: JSONObject.NULL)
        put("direct", directEncode)
        put("codec", videoCodec.name)
        put("intPkg", internalAudioPackage)
        put("intLabel", internalAudioLabel)
        put("micGain", micGainPercent)
        put("intGain", internalGainPercent)
        put("duck", duckAppAudio)
        put("isoTracks", isolateAudioTracks)
        put("fps", frameRate)
        put("vbps", videoBitrateKbps)
        put("container", container.name)
        put("aec", echoCancel)
        put("ns", noiseSuppress)
        put("micDev", micDevice.name)
        put("camLens", facecamLens.name)
        put("camShape", facecamShape.name)
        put("camSize", facecamSize.name)
        put("camHide", facecamHideOnPause)
        put("laser", showLaser)
        put("draw", showAnnotation)
        put("pip", pipControls)
        put("coverBar", coverStatusBar)
        put("gray", grayscale)
        put("caps", captions)
        put("quietNotif", quietNotification)
        put("marks", bookmarkMode.name)
    }.toString()

    companion object {
        fun fromJson(raw: String?): RecordOptions {
            if (raw.isNullOrBlank()) return RecordOptions()
            return runCatching {
                val obj = JSONObject(raw)
                RecordOptions(
                    audioMode = enumOr(obj.optString("audio"), RecordAudioMode.NONE),
                    resolution = enumOr(obj.optString("res"), RecordResolution.P1080),
                    countdownSeconds = obj.optInt("countdown", 0).coerceIn(0, 15),
                    maxDurationMinutes = obj.optInt("maxMin", 0).coerceIn(0, 180),
                    autoStopLowStorage = obj.optBoolean("lowStorage", true),
                    showBubble = obj.optBoolean("bubble", false),
                    facecam = obj.optBoolean("facecam", false),
                    showTaps = obj.optBoolean("taps", false),
                    captureRegion = obj.optBoolean("regionOn", false),
                    region = if (obj.has("region") && !obj.isNull("region")) {
                        RecordRegion.fromJson(obj.optJSONObject("region"))
                    } else {
                        null
                    },
                    directEncode = obj.optBoolean("direct", false),
                    videoCodec = enumOr(obj.optString("codec"), RecordVideoCodec.H264),
                    internalAudioPackage = obj.optString("intPkg", ""),
                    internalAudioLabel = obj.optString("intLabel", ""),
                    micGainPercent = obj.optInt("micGain", 100).coerceIn(0, 200),
                    internalGainPercent = obj.optInt("intGain", 100).coerceIn(0, 200),
                    duckAppAudio = obj.optBoolean("duck", false),
                    isolateAudioTracks = obj.optBoolean("isoTracks", false),
                    frameRate = if (obj.optInt("fps", 30) >= 45) 60 else 30,
                    videoBitrateKbps = obj.optInt("vbps", 0).let { if (it <= 0) 0 else it.coerceIn(1_000, 50_000) },
                    container = enumOr(obj.optString("container"), RecordContainer.MP4),
                    echoCancel = obj.optBoolean("aec", true),
                    noiseSuppress = obj.optBoolean("ns", true),
                    micDevice = enumOr(obj.optString("micDev"), RecordMicDevice.AUTO),
                    facecamLens = enumOr(obj.optString("camLens"), FacecamLens.FRONT),
                    facecamShape = enumOr(obj.optString("camShape"), FacecamShape.RECT),
                    facecamSize = enumOr(obj.optString("camSize"), FacecamSize.MEDIUM),
                    facecamHideOnPause = obj.optBoolean("camHide", true),
                    showLaser = obj.optBoolean("laser", false),
                    showAnnotation = obj.optBoolean("draw", false),
                    pipControls = obj.optBoolean("pip", false),
                    coverStatusBar = obj.optBoolean("coverBar", false),
                    grayscale = obj.optBoolean("gray", false),
                    captions = obj.optBoolean("caps", false),
                    quietNotification = obj.optBoolean("quietNotif", false),
                    bookmarkMode = enumOr(obj.optString("marks"), BookmarkMode.CHAPTERS),
                )
            }.getOrElse { RecordOptions() }
        }

        private inline fun <reified T : Enum<T>> enumOr(raw: String?, fallback: T): T {
            if (raw.isNullOrBlank()) return fallback
            return runCatching { java.lang.Enum.valueOf(T::class.java, raw) }.getOrDefault(fallback)
        }
    }
}

enum class RecordAutoStop { DURATION, STORAGE }

object StorageGuard {
    const val MIN_FREE_BYTES: Long = 200L * 1024L * 1024L
    const val MIN_ELAPSED_FOR_STORAGE_MS: Long = 3_000L
    const val MIN_RECORDED_BYTES: Long = 1_024L

    fun shouldAutoStop(availableBytes: Long, minFreeBytes: Long = MIN_FREE_BYTES): Boolean =
        availableBytes > 0L && availableBytes < minFreeBytes

    fun reason(
        elapsedMs: Long,
        maxDurationMs: Long?,
        lowStorageEnabled: Boolean,
        availableBytes: Long,
        recordedBytes: Long,
    ): RecordAutoStop? {
        if (maxDurationMs != null && elapsedMs >= maxDurationMs) return RecordAutoStop.DURATION
        if (lowStorageEnabled &&
            elapsedMs >= MIN_ELAPSED_FOR_STORAGE_MS &&
            recordedBytes >= MIN_RECORDED_BYTES &&
            shouldAutoStop(availableBytes)
        ) {
            return RecordAutoStop.STORAGE
        }
        return null
    }
}

fun RecordVideoCodec.videoBitrate(
    width: Int,
    height: Int,
    direct: Boolean,
    overrideKbps: Int = 0,
    frameRate: Int = 30,
): Int {
    if (overrideKbps > 0) return overrideKbps.coerceIn(1_000, 50_000) * 1_000
    val pixels = width.coerceAtLeast(2) * height.coerceAtLeast(2)
    val factor = when {
        !direct -> 4
        this == RecordVideoCodec.H264 -> 4
        else -> 2
    }
    val fpsMul = if (frameRate >= 50) 2 else 1
    val raw = pixels * factor * fpsMul
    val min = 2_000_000
    val max = (if (direct) 20_000_000 else 16_000_000) * fpsMul
    return raw.coerceIn(min, max)
}

fun FacecamSize.pixelSize(density: Float, round: Boolean): Pair<Int, Int> {
    val (wDp, hDp) = when {
        round -> when (this) {
            FacecamSize.SMALL -> 112 to 112
            FacecamSize.MEDIUM -> 160 to 160
            FacecamSize.LARGE -> 220 to 220
        }
        else -> when (this) {
            FacecamSize.SMALL -> 96 to 128
            FacecamSize.MEDIUM -> 132 to 176
            FacecamSize.LARGE -> 200 to 266
        }
    }
    return (wDp * density).roundToInt() to (hDp * density).roundToInt()
}
