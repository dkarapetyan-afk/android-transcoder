package com.androidcompress.app.asr

object WhisperModels {
    const val SAMPLE_RATE = 16_000
    const val WINDOW_SIZE = 512

    const val ENCODER = "tiny-encoder.int8.onnx"
    const val DECODER = "tiny-decoder.int8.onnx"
    const val TOKENS = "tiny-tokens.txt"
    const val VAD = "silero_vad.onnx"
    const val READY = ".ready"

    const val DIR_NAME = "whisper-tiny"

    val files: List<ModelFile> = listOf(
        ModelFile(
            name = ENCODER,
            minBytes = 5_000_000L,
            urls = listOf(
                "https://huggingface.co/csukuangfj/sherpa-onnx-whisper-tiny/resolve/main/tiny-encoder.int8.onnx",
            ),
        ),
        ModelFile(
            name = DECODER,
            minBytes = 20_000_000L,
            urls = listOf(
                "https://huggingface.co/csukuangfj/sherpa-onnx-whisper-tiny/resolve/main/tiny-decoder.int8.onnx",
            ),
        ),
        ModelFile(
            name = TOKENS,
            minBytes = 1_000L,
            urls = listOf(
                "https://huggingface.co/csukuangfj/sherpa-onnx-whisper-tiny/resolve/main/tiny-tokens.txt",
            ),
        ),
        ModelFile(
            name = VAD,
            minBytes = 100_000L,
            urls = listOf(
                "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx",
            ),
        ),
    )
}

data class ModelFile(
    val name: String,
    val minBytes: Long,
    val urls: List<String>,
)
