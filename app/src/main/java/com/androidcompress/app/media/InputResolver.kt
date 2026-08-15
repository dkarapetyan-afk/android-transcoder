package com.androidcompress.app.media

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.arthenica.ffmpegkit.FFmpegKitConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class InputResolver(private val context: Context) {

    fun takePersistableAccess(uri: Uri) {
        val resolver = context.contentResolver
        val readWrite = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        runCatching { resolver.takePersistableUriPermission(uri, readWrite) }
            .recoverCatching { resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
    }

    fun hasSpaceFor(sourceBytes: Long): Boolean {
        val free = context.cacheDir.usableSpace
        val needed = (sourceBytes.coerceAtLeast(0) * 2) + 50_000_000
        return free >= needed
    }

    suspend fun resolveForFfmpeg(uri: Uri, jobId: String): String = withContext(Dispatchers.IO) {
        when (uri.scheme) {
            "file" -> uri.path ?: error("Missing file path")
            else -> {
                val saf = runCatching { FFmpegKitConfig.getSafParameterForRead(context, uri) }.getOrNull()
                if (!saf.isNullOrBlank()) return@withContext saf
                copyToCache(uri, jobId).absolutePath
            }
        }
    }

    suspend fun copyToCache(uri: Uri, jobId: String): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "imports")
        if (!dir.exists()) dir.mkdirs()
        val out = File(dir, "$jobId.src")
        context.contentResolver.openInputStream(uri)?.use { input ->
            out.outputStream().use { input.copyTo(it) }
        } ?: error("Unable to open the selected video")
        out
    }

    fun encodeOutputFile(jobId: String): File {
        val dir = File(context.cacheDir, "encode")
        if (!dir.exists()) dir.mkdirs()
        return File(dir, "$jobId.mp4")
    }

    fun recordOutputFile(jobId: String): File {
        val dir = File(context.cacheDir, "record")
        if (!dir.exists()) dir.mkdirs()
        return File(dir, "$jobId.mp4")
    }

    fun deleteImportCopy(jobId: String) {
        File(context.cacheDir, "imports/$jobId.src").delete()
    }

    fun recordAudioFile(jobId: String): File {
        val dir = File(context.cacheDir, "record")
        if (!dir.exists()) dir.mkdirs()
        return File(dir, "$jobId.wav")
    }
}
