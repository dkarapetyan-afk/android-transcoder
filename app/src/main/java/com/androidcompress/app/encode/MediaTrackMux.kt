package com.androidcompress.app.encode

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.File
import java.nio.ByteBuffer

object MediaTrackMux {
    private const val BUFFER_BYTES = 2 * 1024 * 1024

    fun audioTrackCount(context: Context, uri: Uri): Int {
        val (extractor, pfd) = openExtractor(context, uri)
        try {
            return audioIndexes(extractor).size
        } finally {
            extractor.release()
            pfd?.close()
        }
    }

    fun extractAudioTrack(
        context: Context,
        uri: Uri,
        audioOrdinal: Int,
        output: File,
        startMs: Long,
        endMs: Long?,
        webmOutput: Boolean,
    ) {
        val (extractor, pfd) = openExtractor(context, uri)
        var muxer: MediaMuxer? = null
        try {
            val indexes = audioIndexes(extractor)
            val track = indexes.getOrNull(audioOrdinal)
                ?: error("Audio track ${audioOrdinal + 1} is missing")
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
            val webm = usesWebmContainer(mime, webmOutput)
            if (output.exists()) output.delete()
            output.parentFile?.mkdirs()
            val writer = MediaMuxer(
                output.absolutePath,
                if (webm) MediaMuxer.OutputFormat.MUXER_OUTPUT_WEBM
                else MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4,
            )
            muxer = writer
            val dst = writer.addTrack(format)
            writer.start()
            copySamples(
                extractor = extractor,
                muxer = writer,
                muxerTrack = dst,
                startUs = startMs.coerceAtLeast(0L) * 1_000L,
                endUs = endMs?.takeIf { it > startMs }?.times(1_000L),
            )
            writer.stop()
            if (!output.exists() || output.length() < 32) {
                error("Audio track ${audioOrdinal + 1} was empty")
            }
        } finally {
            runCatching { muxer?.release() }
            extractor.release()
            pfd?.close()
        }
    }

    fun mux(
        videoPath: String?,
        audioPaths: List<String>,
        outputPath: String,
        webm: Boolean,
    ) {
        require(audioPaths.isNotEmpty() || !videoPath.isNullOrBlank()) { "Nothing to mux" }
        val out = File(outputPath)
        if (out.exists()) out.delete()
        out.parentFile?.mkdirs()
        val extractors = mutableListOf<MediaExtractor>()
        var muxer: MediaMuxer? = null
        try {
            val writer = MediaMuxer(
                outputPath,
                if (webm) MediaMuxer.OutputFormat.MUXER_OUTPUT_WEBM
                else MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4,
            )
            muxer = writer
            val sources = mutableListOf<MuxSource>()
            if (!videoPath.isNullOrBlank()) {
                val extractor = MediaExtractor()
                extractor.setDataSource(videoPath)
                extractors += extractor
                val videoTrack = firstTrack(extractor, "video/")
                    ?: error("Device encoder wrote no video track")
                extractor.selectTrack(videoTrack)
                val format = extractor.getTrackFormat(videoTrack)
                if (format.containsKey(MediaFormat.KEY_ROTATION)) {
                    writer.setOrientationHint(format.getInteger(MediaFormat.KEY_ROTATION))
                }
                sources += MuxSource(extractor, writer.addTrack(format))
            }
            for (path in audioPaths) {
                val extractor = MediaExtractor()
                extractor.setDataSource(path)
                extractors += extractor
                val audioTrack = firstTrack(extractor, "audio/")
                    ?: error("Encoded audio track was empty")
                extractor.selectTrack(audioTrack)
                sources += MuxSource(extractor, writer.addTrack(extractor.getTrackFormat(audioTrack)))
            }
            writer.start()
            val buffer = ByteBuffer.allocateDirect(BUFFER_BYTES)
            val info = MediaCodec.BufferInfo()
            while (true) {
                var best: MuxSource? = null
                var bestTime = Long.MAX_VALUE
                for (source in sources) {
                    if (source.done) continue
                    val time = source.extractor.sampleTime
                    if (time < 0L) {
                        source.done = true
                        continue
                    }
                    if (time < bestTime) {
                        bestTime = time
                        best = source
                    }
                }
                val chosen = best ?: break
                buffer.clear()
                val size = chosen.extractor.readSampleData(buffer, 0)
                if (size < 0) {
                    chosen.done = true
                    continue
                }
                info.offset = 0
                info.size = size
                info.presentationTimeUs = chosen.extractor.sampleTime.coerceAtLeast(0L)
                info.flags = muxerFlags(chosen.extractor.sampleFlags)
                writer.writeSampleData(chosen.muxerTrack, buffer, info)
                chosen.extractor.advance()
            }
            writer.stop()
            if (!out.exists() || out.length() < 1024) error("Muxed output was empty")
        } finally {
            runCatching { muxer?.release() }
            extractors.forEach { runCatching { it.release() } }
        }
    }

    internal fun usesWebmContainer(mime: String, webmOutput: Boolean): Boolean {
        val lower = mime.lowercase()
        if (lower.contains("mp4a") || lower.contains("aac")) return false
        return webmOutput || lower.contains("opus") || lower.contains("vorbis") || lower.contains("webm")
    }

    /** MediaExtractor sample flags are not MediaCodec buffer flags (values overlap incorrectly). */
    private fun muxerFlags(sampleFlags: Int): Int {
        var flags = 0
        if (sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) {
            flags = flags or MediaCodec.BUFFER_FLAG_KEY_FRAME
        }
        if (sampleFlags and MediaExtractor.SAMPLE_FLAG_PARTIAL_FRAME != 0) {
            flags = flags or MediaCodec.BUFFER_FLAG_PARTIAL_FRAME
        }
        return flags
    }

    private fun audioIndexes(extractor: MediaExtractor): List<Int> =
        (0 until extractor.trackCount).filter { index ->
            extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)
                .orEmpty()
                .startsWith("audio/")
        }

    private fun firstTrack(extractor: MediaExtractor, mimePrefix: String): Int? =
        (0 until extractor.trackCount).firstOrNull { index ->
            extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)
                .orEmpty()
                .startsWith(mimePrefix)
        }

    private fun copySamples(
        extractor: MediaExtractor,
        muxer: MediaMuxer,
        muxerTrack: Int,
        startUs: Long,
        endUs: Long?,
    ) {
        if (startUs > 0L) {
            extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        }
        val buffer = ByteBuffer.allocateDirect(BUFFER_BYTES)
        val info = MediaCodec.BufferInfo()
        var wrote = 0
        while (true) {
            val time = extractor.sampleTime
            if (time < 0L) break
            if (endUs != null && time > endUs) break
            buffer.clear()
            val size = extractor.readSampleData(buffer, 0)
            if (size < 0) break
            if (time >= startUs) {
                info.offset = 0
                info.size = size
                info.presentationTimeUs = (time - startUs).coerceAtLeast(0L)
                info.flags = muxerFlags(extractor.sampleFlags)
                muxer.writeSampleData(muxerTrack, buffer, info)
                wrote++
            }
            extractor.advance()
        }
        if (wrote == 0) error("Audio track had no samples in range")
    }

    private fun openExtractor(context: Context, uri: Uri): Pair<MediaExtractor, ParcelFileDescriptor?> {
        val extractor = MediaExtractor()
        if (uri.scheme == "file" && !uri.path.isNullOrBlank()) {
            extractor.setDataSource(uri.path!!)
            return extractor to null
        }
        val pfd = context.contentResolver.openFileDescriptor(uri, "r")
        if (pfd != null) {
            extractor.setDataSource(pfd.fileDescriptor)
            return extractor to pfd
        }
        extractor.setDataSource(context, uri, null)
        return extractor to null
    }

    private class MuxSource(
        val extractor: MediaExtractor,
        val muxerTrack: Int,
        var done: Boolean = false,
    )
}
