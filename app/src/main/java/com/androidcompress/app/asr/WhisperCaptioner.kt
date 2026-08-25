package com.androidcompress.app.asr

import java.io.File

interface WhisperCaptioner {
    suspend fun transcribe(
        pcm: File,
        sampleRate: Int = WhisperModels.SAMPLE_RATE,
        onProgress: (Float) -> Unit = {},
        isCancelled: () -> Boolean = { false },
    ): List<CaptionCue>
}
