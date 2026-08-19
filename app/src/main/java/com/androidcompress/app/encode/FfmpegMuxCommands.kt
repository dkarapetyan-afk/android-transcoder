package com.androidcompress.app.encode

object FfmpegMuxCommands {
    fun copyVideoAac(videoPath: String, audioPath: String, outputPath: String): List<String> = listOf(
        "-y", "-hide_banner",
        "-i", videoPath,
        "-i", audioPath,
        "-c:v", "copy",
        "-c:a", "aac",
        "-b:a", "128k",
        "-shortest",
        "-movflags", "+faststart",
        outputPath,
    )

    fun mixMicAndInternalAac(
        videoPath: String,
        internalWav: String,
        micWav: String,
        outputPath: String,
    ): List<String> = listOf(
        "-y", "-hide_banner",
        "-i", videoPath,
        "-i", internalWav,
        "-i", micWav,
        "-filter_complex", MIX_FILTER,
        "-map", "0:v",
        "-map", "[a]",
        "-c:v", "copy",
        "-c:a", "aac",
        "-b:a", "160k",
        "-ac", "2",
        "-ar", "44100",
        "-shortest",
        "-movflags", "+faststart",
        outputPath,
    )

    const val MIX_FILTER =
        "[1:a]aformat=sample_fmts=fltp:sample_rates=44100:channel_layouts=stereo[i];" +
            "[2:a]aformat=sample_fmts=fltp:sample_rates=44100:channel_layouts=stereo[m];" +
            "[i][m]amix=inputs=2:duration=longest:dropout_transition=0:normalize=0[a]"
}
