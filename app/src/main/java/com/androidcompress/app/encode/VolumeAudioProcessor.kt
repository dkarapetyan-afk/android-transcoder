package com.androidcompress.app.encode

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import kotlin.math.max
import kotlin.math.min

@OptIn(UnstableApi::class)
class VolumeAudioProcessor : BaseAudioProcessor() {

    private var volume = 1f

    fun setVolume(volume: Float) {
        this.volume = volume
    }

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT &&
            inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT
        ) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        if (volume == 1f) return AudioProcessor.AudioFormat.NOT_SET
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return
        val outputBuffer = replaceOutputBuffer(remaining)
        if (inputAudioFormat.encoding == C.ENCODING_PCM_16BIT) {
            while (inputBuffer.hasRemaining()) {
                val scaled = (inputBuffer.short * volume).toInt()
                outputBuffer.putShort(max(Short.MIN_VALUE.toInt(), min(Short.MAX_VALUE.toInt(), scaled)).toShort())
            }
        } else {
            while (inputBuffer.hasRemaining()) {
                outputBuffer.putFloat(inputBuffer.float * volume)
            }
        }
        outputBuffer.flip()
    }
}
