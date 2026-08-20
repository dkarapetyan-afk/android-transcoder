package com.androidcompress.app.capture

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.projection.MediaProjection
import androidx.annotation.RequiresApi
import com.androidcompress.app.media.WavWriter
import java.io.File

class PcmWavCapture(
    private val output: File,
    private val recorder: AudioRecord,
    private val sampleRate: Int,
    private val channels: Int,
    private val bufferBytes: Int,
    private val echoCanceler: AcousticEchoCanceler? = null,
) {
    private var thread: Thread? = null
    @Volatile private var running = false
    @Volatile private var paused = false

    fun start() {
        val writer = WavWriter(output, sampleRate, channels)
        running = true
        paused = false
        recorder.startRecording()
        thread = Thread {
            val buf = ByteArray(bufferBytes.coerceAtLeast(4096))
            try {
                while (running) {
                    val read = recorder.read(buf, 0, buf.size)
                    if (read > 0 && !paused) writer.write(buf, read)
                }
            } finally {
                runCatching { writer.close() }
            }
        }.also {
            it.name = "pcm-wav"
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
        runCatching { recorder.stop() }
        runCatching { echoCanceler?.release() }
        runCatching { recorder.release() }
        thread?.join(1_000)
        thread = null
    }

    companion object {
        const val SAMPLE_RATE = 44_100

        @RequiresApi(29)
        @SuppressLint("MissingPermission")
        fun internal(
            mediaProjection: MediaProjection,
            output: File,
            appUid: Int? = null,
        ): PcmWavCapture {
            val builder = AudioPlaybackCaptureConfiguration.Builder(mediaProjection)
            if (appUid != null) {
                builder.addMatchingUid(appUid)
            } else {
                builder.addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                    .addMatchingUsage(AudioAttributes.USAGE_GAME)
            }
            val config = builder.build()
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
                .setAudioPlaybackCaptureConfig(config)
                .build()
            return PcmWavCapture(output, recorder, SAMPLE_RATE, 2, minBuf)
        }

        @SuppressLint("MissingPermission")
        fun microphone(output: File): PcmWavCapture {
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
            val recorder = AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.MIC)
                .setAudioFormat(format)
                .setBufferSizeInBytes(minBuf * 2)
                .build()
            val aec = runCatching {
                if (AcousticEchoCanceler.isAvailable()) {
                    AcousticEchoCanceler.create(recorder.audioSessionId)?.also { it.enabled = true }
                } else {
                    null
                }
            }.getOrNull()
            return PcmWavCapture(output, recorder, SAMPLE_RATE, channels, minBuf, aec)
        }
    }
}
