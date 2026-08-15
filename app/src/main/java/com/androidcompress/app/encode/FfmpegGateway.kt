package com.androidcompress.app.encode

import com.androidcompress.app.data.EncodeResult
import com.androidcompress.app.data.EncodeStats
import com.androidcompress.app.data.EncoderCapabilities

interface EncodeSession {
    val id: Long
    suspend fun await(): EncodeResult
    fun cancel()
}

interface FfmpegGateway {
    suspend fun detectEncoders(): EncoderCapabilities
    fun encode(
        args: List<String>,
        onLog: (String) -> Unit,
        onStats: (EncodeStats) -> Unit,
    ): EncodeSession

    fun muxCopyVideoAac(videoPath: String, audioPath: String, outputPath: String): EncodeSession
}

fun quoteArgs(args: List<String>): String = args.joinToString(" ") { token ->
    if (token.any { it.isWhitespace() || it == '\'' || it == '"' }) {
        "\"${token.replace("\"", "\\\"")}\""
    } else {
        token
    }
}
