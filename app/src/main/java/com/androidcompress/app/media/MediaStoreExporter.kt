package com.androidcompress.app.media

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class MediaStoreExporter(private val context: Context) {

    suspend fun publish(file: File, displayName: String, audioOnly: Boolean = false): Uri = withContext(Dispatchers.IO) {
        val ext = if (audioOnly) ".m4a" else ".mp4"
        val mime = if (audioOnly) "audio/mp4" else "video/mp4"
        val folder = if (audioOnly) "Music/RecordingCompressor" else "Movies/RecordingCompressor"
        val safeName = if (displayName.endsWith(ext, ignoreCase = true)) displayName else "$displayName$ext"
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                if (audioOnly) {
                    put(MediaStore.Audio.Media.DISPLAY_NAME, safeName)
                    put(MediaStore.Audio.Media.MIME_TYPE, mime)
                    put(MediaStore.Audio.Media.RELATIVE_PATH, folder)
                    put(MediaStore.Audio.Media.IS_PENDING, 1)
                } else {
                    put(MediaStore.Video.Media.DISPLAY_NAME, safeName)
                    put(MediaStore.Video.Media.MIME_TYPE, mime)
                    put(MediaStore.Video.Media.RELATIVE_PATH, folder)
                    put(MediaStore.Video.Media.IS_PENDING, 1)
                }
            }
            val resolver = context.contentResolver
            val collection = if (audioOnly) {
                MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            } else {
                MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            }
            val uri = resolver.insert(collection, values)
                ?: error("Unable to create MediaStore entry")
            resolver.openOutputStream(uri)?.use { out ->
                file.inputStream().use { it.copyTo(out) }
            } ?: error("Unable to write MediaStore entry")
            values.clear()
            if (audioOnly) {
                values.put(MediaStore.Audio.Media.IS_PENDING, 0)
            } else {
                values.put(MediaStore.Video.Media.IS_PENDING, 0)
            }
            resolver.update(uri, values, null, null)
            uri
        } else {
            val publicDir = if (audioOnly) Environment.DIRECTORY_MUSIC else Environment.DIRECTORY_MOVIES
            val dir = File(Environment.getExternalStoragePublicDirectory(publicDir), "RecordingCompressor")
            if (!dir.exists()) dir.mkdirs()
            val dest = File(dir, safeName)
            file.copyTo(dest, overwrite = true)
            MediaScannerConnection.scanFile(context, arrayOf(dest.absolutePath), arrayOf(mime), null)
            Uri.fromFile(dest)
        }
    }
}
