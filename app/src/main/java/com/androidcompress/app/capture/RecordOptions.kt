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
) {
    val needsOverlay: Boolean get() = showBubble || facecam || captureRegion
    val needsCamera: Boolean get() = facecam
    val maxDurationMs: Long? get() = maxDurationMinutes.takeIf { it > 0 }?.let { it * 60_000L }

    fun resolvedForSdk(sdk: Int): RecordOptions = copy(
        audioMode = audioMode.resolvedForSdk(sdk),
        countdownSeconds = countdownSeconds.coerceIn(0, 15),
        maxDurationMinutes = maxDurationMinutes.coerceIn(0, 180),
        micGainPercent = micGainPercent.coerceIn(0, 200),
        internalGainPercent = internalGainPercent.coerceIn(0, 200),
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

fun RecordVideoCodec.videoBitrate(width: Int, height: Int, direct: Boolean): Int {
    val pixels = width.coerceAtLeast(2) * height.coerceAtLeast(2)
    val factor = when {
        !direct -> 4
        this == RecordVideoCodec.H264 -> 4
        else -> 2
    }
    val raw = pixels * factor
    val min = if (direct) 2_000_000 else 2_000_000
    val max = if (direct) 20_000_000 else 16_000_000
    return raw.coerceIn(min, max)
}
