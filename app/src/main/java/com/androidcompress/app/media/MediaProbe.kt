package com.androidcompress.app.media

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import com.androidcompress.app.data.SourceVideo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MediaProbe(private val context: Context) {

    suspend fun probe(uri: Uri): SourceVideo = withContext(Dispatchers.IO) {
        val nameAndSize = queryNameAndSize(uri)
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            val mime = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE)
            val hasAudio = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) == "yes"
            val frameRate = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)
                ?.toFloatOrNull()
                ?: inferFps(retriever, duration)
            val (w, h) = if (rotation == 90 || rotation == 270) height to width else width to height
            SourceVideo(
                uri = uri.toString(),
                displayName = nameAndSize.first,
                width = w,
                height = h,
                durationMs = duration,
                bytes = nameAndSize.second,
                frameRate = frameRate,
                audioCodec = if (hasAudio) mime else null,
                hasAudio = hasAudio,
            )
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun inferFps(retriever: MediaMetadataRetriever, durationMs: Long): Float {
        if (durationMs <= 0) return 30f
        val count = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)
            ?.toFloatOrNull() ?: return 30f
        val fps = count / (durationMs / 1000f)
        return if (fps.isFinite() && fps > 1f) fps else 30f
    }

    private fun queryNameAndSize(uri: Uri): Pair<String, Long> {
        var name = "video.mp4"
        var size = 0L
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (cursor.moveToFirst()) {
                if (nameIdx >= 0) name = cursor.getString(nameIdx) ?: name
                if (sizeIdx >= 0) size = cursor.getLong(sizeIdx)
            }
        }
        if (size <= 0 && uri.scheme == "file") {
            size = uri.path?.let { java.io.File(it).length() } ?: 0L
        }
        return name to size
    }
}
