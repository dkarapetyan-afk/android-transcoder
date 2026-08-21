package com.androidcompress.app.capture

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AudioEffect
import android.media.audiofx.NoiseSuppressor
import android.media.projection.MediaProjection
import android.os.Build
import androidx.annotation.RequiresApi
import com.androidcompress.app.media.WavWriter
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Captures microphone and/or internal audio, applies gain/duck/AEC/NS, and
 * writes PCM WAVs. Mixed mode is one stereo file so stop only muxes
 * (`-c:v copy`). Isolated mode writes voice and system as two files that
 * become two audio streams in the container.
 */
class LiveAudioMixer(
    private val mixedOutput: File?,
    private val micOutput: File?,
    private val internalOutput: File?,
    private val mic: CapturedPcm?,
    private val internal: CapturedPcm?,
    private val micGain: Float,
    private val internalGain: Float,
    private val duck: DuckEnvelope?,
    private val audioManager: AudioManager?,
    private val startedSco: Boolean,
    private val isolateTracks: Boolean,
) {
    private var thread: Thread? = null
    @Volatile private var running = false
    @Volatile private var paused = false

    fun start() {
        running = true
        paused = false
        mic?.start()
        internal?.start()
        thread = Thread {
            val micBuf = ByteArray(mic?.bufferBytes ?: 0)
            val intBuf = ByteArray(internal?.bufferBytes ?: 0)
            val dest = ByteArray(max(micBuf.size, intBuf.size).coerceAtLeast(4096) * 2)
            val dest2 = if (isolateTracks) ByteArray(dest.size) else ByteArray(0)
            val mixWriter = mixedOutput?.let { WavWriter(it, SAMPLE_RATE, 2) }
            val micWriter = micOutput?.let { WavWriter(it, SAMPLE_RATE, 2) }
            val intWriter = internalOutput?.let { WavWriter(it, SAMPLE_RATE, 2) }
            try {
                while (running) {
                    val micRead = if (mic != null) mic.read(micBuf) else 0
                    val intRead = if (internal != null) internal.read(intBuf) else 0
                    if (paused) continue
                    val frames = mixFrameCount(mic, micRead, internal, intRead)
                    if (frames <= 0) {
                        Thread.sleep(4)
                        continue
                    }
                    if (isolateTracks) {
                        val micBytes = pcmToStereo(
                            src = micBuf,
                            srcRead = micRead.coerceAtLeast(0),
                            srcChannels = mic?.channels ?: 0,
                            frames = frames,
                            dest = dest,
                            gain = micGain,
                        )
                        val intBytes = pcmToStereo(
                            src = intBuf,
                            srcRead = intRead.coerceAtLeast(0),
                            srcChannels = internal?.channels ?: 0,
                            frames = frames,
                            dest = dest2,
                            gain = internalGain,
                        )
                        if (micBytes > 0) micWriter?.write(dest, micBytes)
                        if (intBytes > 0) intWriter?.write(dest2, intBytes)
                    } else {
                        val outBytes = mix(
                            micBytes = micBuf,
                            micRead = micRead.coerceAtLeast(0),
                            micChannels = mic?.channels ?: 0,
                            intBytes = intBuf,
                            intRead = intRead.coerceAtLeast(0),
                            intChannels = internal?.channels ?: 0,
                            frames = frames,
                            dest = dest,
                            micGain = micGain,
                            internalGain = internalGain,
                            duck = duck,
                        )
                        if (outBytes > 0) mixWriter?.write(dest, outBytes)
                    }
                }
            } finally {
                runCatching { mixWriter?.close() }
                runCatching { micWriter?.close() }
                runCatching { intWriter?.close() }
            }
        }.also {
            it.name = if (isolateTracks) "live-audio-tracks" else "live-audio-mix"
            it.priority = Thread.NORM_PRIORITY + 1
            it.start()
        }
    }

    fun pause() {
        paused = true
    }

    fun resume() {
        paused = false
    }

    fun stop() {
        running = false
        paused = false
        mic?.stop()
        internal?.stop()
        thread?.join(1_000)
        thread = null
        if (startedSco) {
            runCatching {
                @Suppress("DEPRECATION")
                audioManager?.isBluetoothScoOn = false
                @Suppress("DEPRECATION")
                audioManager?.stopBluetoothSco()
            }
        }
    }

    class CapturedPcm(
        private val recorder: AudioRecord,
        val channels: Int,
        val bufferBytes: Int,
        private val effects: List<AudioEffect>,
    ) {
        fun start() {
            recorder.startRecording()
        }

        fun read(buffer: ByteArray): Int {
            if (buffer.isEmpty()) return 0
            return recorder.read(buffer, 0, buffer.size)
        }

        fun stop() {
            runCatching { recorder.stop() }
            effects.forEach { runCatching { it.release() } }
            runCatching { recorder.release() }
        }
    }

    companion object {
        const val SAMPLE_RATE = 44_100

        @SuppressLint("MissingPermission")
        fun start(
            context: Context,
            projection: MediaProjection?,
            output: File,
            options: RecordOptions,
            appUid: Int?,
            micOutput: File? = null,
        ): LiveAudioMixer {
            val audioManager = context.getSystemService(AudioManager::class.java)
            var startedSco = false
            if (options.audioMode.usesMicrophone && options.micDevice == RecordMicDevice.BLUETOOTH) {
                startedSco = startSco(audioManager)
            }
            val mic = if (options.audioMode.usesMicrophone) {
                microphone(options, audioManager)
            } else {
                null
            }
            val internal = if (options.audioMode.usesInternalAudio &&
                projection != null &&
                Build.VERSION.SDK_INT >= 29
            ) {
                internal(projection, appUid)
            } else {
                null
            }
            require(mic != null || internal != null) { "No audio source" }
            val isolate = options.isolateAudioTracks &&
                mic != null &&
                internal != null &&
                micOutput != null
            val duck = if (!isolate && options.duckAppAudio && mic != null && internal != null) {
                DuckEnvelope()
            } else {
                null
            }
            return LiveAudioMixer(
                mixedOutput = if (isolate) null else output,
                micOutput = if (isolate) micOutput else null,
                internalOutput = if (isolate) output else null,
                mic = mic,
                internal = internal,
                micGain = options.micGainPercent.coerceIn(0, 200) / 100f,
                internalGain = options.internalGainPercent.coerceIn(0, 200) / 100f,
                duck = duck,
                audioManager = audioManager,
                startedSco = startedSco,
                isolateTracks = isolate,
            )
        }

        @SuppressLint("MissingPermission")
        fun microphone(options: RecordOptions, audioManager: AudioManager?): CapturedPcm {
            val stereoBuf = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_STEREO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
            val mask: Int
            val channels: Int
            if (stereoBuf > 0) {
                mask = AudioFormat.CHANNEL_IN_STEREO
                channels = 2
            } else {
                mask = AudioFormat.CHANNEL_IN_MONO
                channels = 1
            }
            val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, mask, AudioFormat.ENCODING_PCM_16BIT)
            require(minBuf > 0) { "Microphone is not available" }
            val format = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(mask)
                .build()
            val source = if (options.echoCancel) {
                MediaRecorder.AudioSource.VOICE_COMMUNICATION
            } else {
                MediaRecorder.AudioSource.MIC
            }
            val recorder = AudioRecord.Builder()
                .setAudioSource(source)
                .setAudioFormat(format)
                .setBufferSizeInBytes(minBuf * 2)
                .build()
            preferDevice(recorder, audioManager, options.micDevice)
            val effects = attachEffects(recorder.audioSessionId, options.echoCancel, options.noiseSuppress)
            return CapturedPcm(recorder, channels, minBuf, effects)
        }

        @RequiresApi(29)
        @SuppressLint("MissingPermission")
        fun internal(projection: MediaProjection, appUid: Int?): CapturedPcm {
            val builder = AudioPlaybackCaptureConfiguration.Builder(projection)
            if (appUid != null) {
                builder.addMatchingUid(appUid)
            } else {
                builder.addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                    .addMatchingUsage(AudioAttributes.USAGE_GAME)
            }
            val format = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                .build()
            val minBuf = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_STEREO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
            require(minBuf > 0) { "Internal audio is not available" }
            val recorder = AudioRecord.Builder()
                .setAudioFormat(format)
                .setBufferSizeInBytes(minBuf * 2)
                .setAudioPlaybackCaptureConfig(builder.build())
                .build()
            return CapturedPcm(recorder, 2, minBuf, emptyList())
        }

        fun mixFrameCount(
            mic: CapturedPcm?,
            micRead: Int,
            internal: CapturedPcm?,
            intRead: Int,
        ): Int {
            val micFrames = if (mic != null && micRead > 0) micRead / (2 * mic.channels.coerceAtLeast(1)) else 0
            val intFrames = if (internal != null && intRead > 0) intRead / (2 * internal.channels.coerceAtLeast(1)) else 0
            return when {
                mic != null && internal != null -> max(micFrames, intFrames)
                mic != null -> micFrames
                else -> intFrames
            }
        }

        fun mix(
            micBytes: ByteArray,
            micRead: Int,
            micChannels: Int,
            intBytes: ByteArray,
            intRead: Int,
            intChannels: Int,
            frames: Int,
            dest: ByteArray,
            micGain: Float,
            internalGain: Float,
            duck: DuckEnvelope?,
        ): Int {
            if (frames <= 0) return 0
            val needed = frames * 4
            if (dest.size < needed) return 0
            val duckGain = if (duck != null && micChannels > 0 && micRead > 0) {
                duck.processPeak(pcmPeak(micBytes, micRead))
            } else {
                1f
            }
            var o = 0
            for (f in 0 until frames) {
                val mL = sampleAt(micBytes, micRead, micChannels, f, 0) * micGain
                val mR = sampleAt(micBytes, micRead, micChannels, f, 1) * micGain
                val iL = sampleAt(intBytes, intRead, intChannels, f, 0) * internalGain * duckGain
                val iR = sampleAt(intBytes, intRead, intChannels, f, 1) * internalGain * duckGain
                writeSample(dest, o, clamp16(mL + iL))
                writeSample(dest, o + 2, clamp16(mR + iR))
                o += 4
            }
            return needed
        }

        fun pcmToStereo(
            src: ByteArray,
            srcRead: Int,
            srcChannels: Int,
            frames: Int,
            dest: ByteArray,
            gain: Float,
        ): Int {
            if (frames <= 0) return 0
            val needed = frames * 4
            if (dest.size < needed) return 0
            var o = 0
            for (f in 0 until frames) {
                val left = sampleAt(src, srcRead, srcChannels, f, 0) * gain
                val right = sampleAt(src, srcRead, srcChannels, f, 1) * gain
                writeSample(dest, o, clamp16(left))
                writeSample(dest, o + 2, clamp16(right))
                o += 4
            }
            return needed
        }

        private fun sampleAt(bytes: ByteArray, length: Int, channels: Int, frame: Int, ch: Int): Float {
            if (channels <= 0 || length < 2) return 0f
            val useCh = if (channels == 1) 0 else ch.coerceIn(0, channels - 1)
            val index = (frame * channels + useCh) * 2
            if (index + 1 >= length) return 0f
            val lo = bytes[index].toInt() and 0xFF
            val hi = bytes[index + 1].toInt()
            return ((hi shl 8) or lo).toShort().toInt().toFloat()
        }

        private fun writeSample(dest: ByteArray, offset: Int, value: Int) {
            dest[offset] = (value and 0xFF).toByte()
            dest[offset + 1] = ((value shr 8) and 0xFF).toByte()
        }

        fun pcmPeak(bytes: ByteArray, length: Int): Float {
            var peak = 0
            var i = 0
            while (i + 1 < length) {
                val lo = bytes[i].toInt() and 0xFF
                val hi = bytes[i + 1].toInt()
                peak = max(peak, abs(((hi shl 8) or lo).toShort().toInt()))
                i += 2
            }
            return peak / 32768f
        }

        fun clamp16(value: Float): Int = min(32767, max(-32768, value.toInt()))

        private fun attachEffects(sessionId: Int, echo: Boolean, ns: Boolean): List<AudioEffect> {
            val effects = mutableListOf<AudioEffect>()
            if (echo) {
                runCatching {
                    if (AcousticEchoCanceler.isAvailable()) {
                        AcousticEchoCanceler.create(sessionId)?.also {
                            it.enabled = true
                            effects += it
                        }
                    }
                }
            }
            if (ns) {
                runCatching {
                    if (NoiseSuppressor.isAvailable()) {
                        NoiseSuppressor.create(sessionId)?.also {
                            it.enabled = true
                            effects += it
                        }
                    }
                }
            }
            return effects
        }

        private fun preferDevice(record: AudioRecord, audioManager: AudioManager?, device: RecordMicDevice) {
            if (device == RecordMicDevice.AUTO || audioManager == null) return
            val devices = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
            val match = when (device) {
                RecordMicDevice.AUTO -> null
                RecordMicDevice.BUILTIN -> devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }
                RecordMicDevice.BLUETOOTH -> devices.firstOrNull { info ->
                    info.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                        (Build.VERSION.SDK_INT >= 31 && info.type == AudioDeviceInfo.TYPE_BLE_HEADSET) ||
                        info.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
                }
            }
            if (match != null) record.setPreferredDevice(match)
        }

        @Suppress("DEPRECATION")
        private fun startSco(audioManager: AudioManager?): Boolean {
            if (audioManager == null) return false
            return runCatching {
                audioManager.startBluetoothSco()
                audioManager.isBluetoothScoOn = true
                true
            }.getOrDefault(false)
        }
    }
}

class DuckEnvelope(
    private val threshold: Float = 0.05f,
    private val ducked: Float = 0.18f,
    private val attack: Float = 0.25f,
    private val release: Float = 0.04f,
) {
    var gain: Float = 1f
        private set

    fun processPeak(peak: Float): Float {
        val target = if (peak >= threshold) ducked else 1f
        gain += (target - gain) * if (target < gain) attack else release
        return gain
    }
}
