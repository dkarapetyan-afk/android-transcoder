package com.androidcompress.app.capture

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.os.Build
import androidx.annotation.RequiresApi
import com.androidcompress.app.media.WavWriter
import java.io.File

class InternalAudioCapture(
    private val mediaProjection: MediaProjection,
    private val output: File,
) {
    private var record: AudioRecord? = null
    private var thread: Thread? = null
    @Volatile private var running = false

    @RequiresApi(29)
    @SuppressLint("MissingPermission")
    fun start() {
        val config = AudioPlaybackCaptureConfiguration.Builder(mediaProjection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .build()
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
            .build()
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        val recorder = AudioRecord.Builder()
            .setAudioFormat(format)
            .setBufferSizeInBytes(minBuf * 2)
            .setAudioPlaybackCaptureConfig(config)
            .build()
        record = recorder
        val writer = WavWriter(output, SAMPLE_RATE, 2)
        running = true
        recorder.startRecording()
        thread = Thread {
            val buf = ByteArray(minBuf)
            try {
                while (running) {
                    val read = recorder.read(buf, 0, buf.size)
                    if (read > 0) writer.write(buf, read)
                }
            } finally {
                runCatching { writer.close() }
            }
        }.also {
            it.name = "internal-audio"
            it.start()
        }
    }

    fun stop() {
        running = false
        runCatching { record?.stop() }
        runCatching { record?.release() }
        record = null
        thread?.join(1_000)
        thread = null
    }

    companion object {
        private const val SAMPLE_RATE = 44_100
    }
}
