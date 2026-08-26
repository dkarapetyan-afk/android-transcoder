package com.androidcompress.app.asr

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.BufferedOutputStream
import java.io.File
import com.androidcompress.app.util.runCatchingLog
import java.nio.ByteOrder

/**
 * Decodes the first audio track with the platform decoder (MediaCodec) into
 * 16 kHz mono s16le. Needed when FFmpeg cannot open the audio (older Media3
 * WebM files still carry Android's `AOPUSHDR` Opus CodecPrivate).
 */
object MediaCodecPcmExtractor {
    private const val TAG = "PcmExtractor"
    private const val TIMEOUT_US = 10_000L

    fun extractS16leMono16k(
        input: File,
        output: File,
        isCancelled: () -> Boolean = { false },
        onProgress: (Float) -> Unit = {},
    ) {
        if (!input.isFile || input.length() < 64) error("media missing")
        output.delete()
        output.parentFile?.mkdirs()
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        var started = false
        try {
            extractor.setDataSource(input.absolutePath)
            val track = (0 until extractor.trackCount).firstOrNull { index ->
                extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)
                    .orEmpty()
                    .startsWith("audio/")
            } ?: error("no audio track")
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
            if (mime.isBlank()) error("audio mime missing")
            val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) {
                format.getLong(MediaFormat.KEY_DURATION).coerceAtLeast(0L)
            } else {
                0L
            }
            val decoder = MediaCodec.createDecoderByType(mime)
            codec = decoder
            decoder.configure(format, null, null, 0)
            decoder.start()
            started = true
            val info = MediaCodec.BufferInfo()
            var inputEos = false
            var outputEos = false
            var resampler: PcmResampler? = null
            var sawPcm = false
            BufferedOutputStream(output.outputStream()).use { dest ->
                while (!outputEos) {
                    if (isCancelled()) error("cancelled")
                    if (!inputEos) {
                        val inIndex = decoder.dequeueInputBuffer(TIMEOUT_US)
                        if (inIndex >= 0) {
                            val inBuf = decoder.getInputBuffer(inIndex)
                            if (inBuf == null) {
                                decoder.queueInputBuffer(inIndex, 0, 0, 0, 0)
                            } else {
                                inBuf.clear()
                                val size = extractor.readSampleData(inBuf, 0)
                                if (size < 0) {
                                    decoder.queueInputBuffer(
                                        inIndex,
                                        0,
                                        0,
                                        0,
                                        MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                                    )
                                    inputEos = true
                                } else {
                                    val time = extractor.sampleTime.coerceAtLeast(0L)
                                    decoder.queueInputBuffer(inIndex, 0, size, time, 0)
                                    extractor.advance()
                                    if (durationUs > 0L) {
                                        onProgress((time.toFloat() / durationUs.toFloat()).coerceIn(0f, 0.99f))
                                    }
                                }
                            }
                        }
                    }
                    val outIndex = decoder.dequeueOutputBuffer(info, TIMEOUT_US)
                    when {
                        outIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                        outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            resampler = resamplerFrom(decoder.outputFormat, resampler)
                        }
                        outIndex >= 0 -> {
                            if (info.size > 0) {
                                val outBuf = decoder.getOutputBuffer(outIndex)
                                if (outBuf != null) {
                                    val writer = resampler ?: resamplerFrom(decoder.outputFormat, null).also {
                                        resampler = it
                                    }
                                    outBuf.position(info.offset)
                                    outBuf.limit(info.offset + info.size)
                                    outBuf.order(ByteOrder.LITTLE_ENDIAN)
                                    val bytes = ByteArray(info.size)
                                    outBuf.get(bytes)
                                    val encoding = pcmEncoding(decoder.outputFormat)
                                    if (encoding == AudioFormat.ENCODING_PCM_FLOAT) {
                                        writer.feedF32Bytes(bytes, 0, bytes.size, dest)
                                    } else {
                                        writer.feedS16Bytes(bytes, 0, bytes.size, dest)
                                    }
                                    sawPcm = true
                                }
                            }
                            decoder.releaseOutputBuffer(outIndex, false)
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                                outputEos = true
                            }
                        }
                    }
                }
                resampler?.finish(dest)
                dest.flush()
            }
            if (!sawPcm || !output.isFile || output.length() < WhisperModels.SAMPLE_RATE) {
                error("decoded pcm empty")
            }
            onProgress(1f)
        } finally {
            if (started) runCatchingLog(TAG, "stop codec") { codec?.stop() }
            runCatchingLog(TAG, "release codec") { codec?.release() }
            extractor.release()
            if (!output.isFile || output.length() < WhisperModels.SAMPLE_RATE) {
                output.delete()
            }
        }
    }

    private fun resamplerFrom(format: MediaFormat, existing: PcmResampler?): PcmResampler {
        val rate = if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
            format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        } else {
            0
        }.takeIf { it > 0 } ?: 48_000
        val channels = if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
            format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        } else {
            1
        }.coerceAtLeast(1)
        if (existing != null) return existing
        return PcmResampler(inRate = rate, channels = channels)
    }

    private fun pcmEncoding(format: MediaFormat): Int {
        if (!format.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
            return AudioFormat.ENCODING_PCM_16BIT
        }
        return format.getInteger(MediaFormat.KEY_PCM_ENCODING)
    }
}
