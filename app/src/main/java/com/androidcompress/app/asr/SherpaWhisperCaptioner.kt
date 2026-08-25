package com.androidcompress.app.asr

import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

class SherpaWhisperCaptioner(
    private val models: WhisperModelStore,
) : WhisperCaptioner {

    @Volatile private var recognizer: OfflineRecognizer? = null

    override suspend fun transcribe(
        pcm: File,
        sampleRate: Int,
        onProgress: (Float) -> Unit,
        isCancelled: () -> Boolean,
    ): List<CaptionCue> = withContext(Dispatchers.IO) {
        if (!pcm.isFile || pcm.length() < sampleRate) return@withContext emptyList()
        val asr = recognizer()
        val vad = Vad(
            config = VadModelConfig(
                sileroVadModelConfig = SileroVadModelConfig(
                    model = models.vad.absolutePath,
                    threshold = 0.2f,
                    minSilenceDuration = 0.25f,
                    minSpeechDuration = 0.25f,
                    windowSize = WhisperModels.WINDOW_SIZE,
                    maxSpeechDuration = 5.0f,
                ),
                sampleRate = sampleRate,
                numThreads = threadCount(),
                provider = "cpu",
            ),
        )
        val cues = ArrayList<CaptionCue>()
        try {
            val totalSamples = pcm.length() / 2L
            if (totalSamples <= 0L) return@withContext emptyList()
            var processed = 0L
            RandomAccessFile(pcm, "r").use { raf ->
                val windowBytes = WhisperModels.WINDOW_SIZE * 2
                val raw = ByteArray(windowBytes)
                val le = ByteBuffer.allocate(windowBytes).order(ByteOrder.LITTLE_ENDIAN)
                while (true) {
                    if (isCancelled()) error("cancelled")
                    val n = raf.read(raw)
                    if (n < windowBytes) break
                    le.clear()
                    le.put(raw, 0, n)
                    le.flip()
                    val samples = FloatArray(WhisperModels.WINDOW_SIZE)
                    for (i in samples.indices) {
                        samples[i] = le.short / 32768f
                    }
                    vad.acceptWaveform(samples)
                    processed += WhisperModels.WINDOW_SIZE
                    drainVad(asr, vad, sampleRate, cues)
                    onProgress((processed.toFloat() / totalSamples.toFloat()).coerceIn(0f, 0.99f))
                    yield()
                }
            }
            vad.flush()
            drainVad(asr, vad, sampleRate, cues)
            onProgress(1f)
        } finally {
            runCatching { vad.release() }
        }
        cues
    }

    private fun drainVad(
        asr: OfflineRecognizer,
        vad: Vad,
        sampleRate: Int,
        cues: MutableList<CaptionCue>,
    ) {
        while (!vad.empty()) {
            val segment = vad.front()
            vad.pop()
            val text = SrtWriter.usableText(decode(asr, segment.samples, sampleRate)) ?: continue
            val start = segment.start.toDouble() / sampleRate
            val duration = segment.samples.size.toDouble() / sampleRate
            val end = (start + duration).coerceAtLeast(start + 0.2)
            cues += CaptionCue(start, end, text)
        }
    }

    private fun decode(asr: OfflineRecognizer, samples: FloatArray, sampleRate: Int): String {
        val stream = asr.createStream()
        return try {
            stream.acceptWaveform(samples, sampleRate)
            asr.decode(stream)
            asr.getResult(stream).text.orEmpty()
        } finally {
            stream.release()
        }
    }

    @Synchronized
    private fun recognizer(): OfflineRecognizer {
        recognizer?.let { return it }
        val created = OfflineRecognizer(
            config = OfflineRecognizerConfig(
                featConfig = FeatureConfig(sampleRate = WhisperModels.SAMPLE_RATE, featureDim = 80),
                modelConfig = OfflineModelConfig(
                    whisper = OfflineWhisperModelConfig(
                        encoder = models.encoder.absolutePath,
                        decoder = models.decoder.absolutePath,
                        language = "",
                        task = "transcribe",
                    ),
                    tokens = models.tokens.absolutePath,
                    numThreads = threadCount(),
                    provider = "cpu",
                    modelType = "whisper",
                ),
                decodingMethod = "greedy_search",
            ),
        )
        recognizer = created
        return created
    }

    private fun threadCount(): Int =
        Runtime.getRuntime().availableProcessors().coerceIn(1, 4)
}
