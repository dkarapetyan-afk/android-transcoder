package com.androidcompress.app.media

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.androidcompress.app.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class MediaStoreExporter(private val context: Context) {

    suspend fun publish(
        file: File,
        displayName: String,
        mime: String,
        relativePath: String,
    ): Uri = withContext(Dispatchers.IO) {
        val audioOnly = mime.startsWith("audio/")
        val ext = when {
            mime.contains("webm") -> ".webm"
            audioOnly -> ".m4a"
            else -> ".mp4"
        }
        val safeName = if (displayName.endsWith(ext, ignoreCase = true)) displayName else "$displayName$ext"
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                if (audioOnly) {
                    put(MediaStore.Audio.Media.DISPLAY_NAME, safeName)
                    put(MediaStore.Audio.Media.MIME_TYPE, mime)
                    put(MediaStore.Audio.Media.RELATIVE_PATH, relativePath)
                    put(MediaStore.Audio.Media.IS_PENDING, 1)
                } else {
                    put(MediaStore.Video.Media.DISPLAY_NAME, safeName)
                    put(MediaStore.Video.Media.MIME_TYPE, mime)
                    put(MediaStore.Video.Media.RELATIVE_PATH, relativePath)
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
                ?: error(context.getString(R.string.error_mediastore_create))
            resolver.openOutputStream(uri)?.use { out ->
                file.inputStream().use { it.copyTo(out) }
            } ?: error(context.getString(R.string.error_mediastore_write))
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
            val folderName = relativePath.substringAfterLast('/').ifBlank { "RecordingCompressor" }
            val dir = File(Environment.getExternalStoragePublicDirectory(publicDir), folderName)
            if (!dir.exists()) dir.mkdirs()
            val dest = File(dir, safeName)
            file.copyTo(dest, overwrite = true)
            MediaScannerConnection.scanFile(context, arrayOf(dest.absolutePath), arrayOf(mime), null)
            Uri.fromFile(dest)
        }
    }

    suspend fun publishSidecar(
        file: File,
        displayName: String,
        relativePath: String,
    ): Uri? = withContext(Dispatchers.IO) {
        if (!file.isFile || file.length() < 8) return@withContext null
        val safeName = if (displayName.endsWith(".srt", ignoreCase = true)) {
            displayName
        } else {
            "$displayName.srt"
        }
        val mime = "application/x-subrip"
        runCatching {
            if (Build.VERSION.SDK_INT >= 29) {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, safeName)
                    put(MediaStore.MediaColumns.MIME_TYPE, mime)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                val resolver = context.contentResolver
                val uri = resolver.insert(MediaStore.Files.getContentUri("external"), values)
                    ?: return@runCatching null
                resolver.openOutputStream(uri)?.use { out ->
                    file.inputStream().use { it.copyTo(out) }
                } ?: return@runCatching null
                values.clear()
                values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                uri
            } else {
                val publicDir = if (relativePath.startsWith("Music")) {
                    Environment.DIRECTORY_MUSIC
                } else {
                    Environment.DIRECTORY_MOVIES
                }
                val folderName = relativePath.substringAfterLast('/').ifBlank { "RecordingCompressor" }
                val dir = File(Environment.getExternalStoragePublicDirectory(publicDir), folderName)
                if (!dir.exists()) dir.mkdirs()
                val dest = File(dir, safeName)
                file.copyTo(dest, overwrite = true)
                MediaScannerConnection.scanFile(context, arrayOf(dest.absolutePath), arrayOf(mime), null)
                Uri.fromFile(dest)
            }
        }.getOrNull()
    }
}
