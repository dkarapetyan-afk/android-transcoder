package com.androidcompress.app.media

import android.content.Context
import android.graphics.BitmapFactory
import android.media.ExifInterface
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import com.androidcompress.app.R
import com.androidcompress.app.data.SourceVideo
import com.androidcompress.app.util.runCatchingLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MediaProbe(private val context: Context) {

    fun displayName(uri: Uri): String = queryNameAndSize(uri).first

    fun sizeBytes(uri: Uri): Long = queryNameAndSize(uri).second

    suspend fun probe(uri: Uri): SourceVideo = withContext(Dispatchers.IO) {
        val nameAndSize = queryNameAndSize(uri)
        val resolverMime = context.contentResolver.getType(uri)
        if (CombinePairing.kind(resolverMime, nameAndSize.first) == MediaKind.IMAGE) {
            return@withContext probeStillImage(uri, nameAndSize)
        }
        val retriever = MediaMetadataRetriever()
        try {
            openRetriever(retriever, uri)
            val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            val mime = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE)
            val hasAudio = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) == "yes"
            val flaggedVideo = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) == "yes"
            val frameRate = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)
                ?.toFloatOrNull()
                ?: inferFps(retriever, duration)
            val (w, h) = if (rotation == 90 || rotation == 270) height to width else width to height
            val hasVideo = flaggedVideo || (w > 0 && h > 0)
            if (!hasVideo && !hasAudio) {
                return@withContext probeStillImage(uri, nameAndSize)
            }
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
                hasVideo = hasVideo,
            )
        } finally {
            runCatchingLog(TAG, "release retriever") { retriever.release() }
        }
    }

    private fun probeStillImage(uri: Uri, nameAndSize: Pair<String, Long>): SourceVideo {
        var width = 0
        var height = 0
        runCatchingLog(TAG, "probe still image") {
            val retriever = MediaMetadataRetriever()
            try {
                openRetriever(retriever, uri)
                width = imageWidth(retriever)
                height = imageHeight(retriever)
                val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
                if (rotation == 90 || rotation == 270) {
                    val swapped = width
                    width = height
                    height = swapped
                }
            } finally {
                retriever.release()
            }
        }
        if (width <= 0 || height <= 0) {
            context.contentResolver.openInputStream(uri)?.use { input ->
                val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeStream(input, null, opts)
                width = opts.outWidth
                height = opts.outHeight
            }
        }
        if (width <= 0 || height <= 0) error(context.getString(R.string.error_read_image))
        val oriented = applyExifOrientation(uri, width, height)
        return SourceVideo(
            uri = uri.toString(),
            displayName = nameAndSize.first,
            width = oriented.first,
            height = oriented.second,
            durationMs = 0L,
            bytes = nameAndSize.second,
            frameRate = 30f,
            audioCodec = null,
            hasAudio = false,
            hasVideo = true,
            stillImage = true,
        )
    }

    private fun imageWidth(retriever: MediaMetadataRetriever): Int {
        if (Build.VERSION.SDK_INT >= 28) {
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_IMAGE_WIDTH)?.toIntOrNull()?.let {
                if (it > 0) return it
            }
        }
        return retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
    }

    private fun imageHeight(retriever: MediaMetadataRetriever): Int {
        if (Build.VERSION.SDK_INT >= 28) {
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_IMAGE_HEIGHT)?.toIntOrNull()?.let {
                if (it > 0) return it
            }
        }
        return retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
    }

    private fun applyExifOrientation(uri: Uri, width: Int, height: Int): Pair<Int, Int> {
        val orientation = runCatchingLog(TAG, "exif orientation") {
            context.contentResolver.openInputStream(uri)?.use { input ->
                ExifInterface(input).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            }
        }.getOrNull() ?: return width to height
        return if (
            orientation == ExifInterface.ORIENTATION_ROTATE_90 ||
            orientation == ExifInterface.ORIENTATION_ROTATE_270
        ) {
            height to width
        } else {
            width to height
        }
    }

    private fun openRetriever(retriever: MediaMetadataRetriever, uri: Uri) {
        if (uri.scheme == "file") {
            val path = uri.path ?: error("Missing file path")
            retriever.setDataSource(path)
        } else {
            retriever.setDataSource(context, uri)
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

    private companion object {
        const val TAG = "MediaProbe"
    }
}
