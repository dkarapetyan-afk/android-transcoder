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

    fun hasPersistableRead(uri: Uri): Boolean =
        context.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission }

    fun canKeepWithoutCopy(uri: Uri): Boolean = DeviceMediaStore.canKeepWithoutCopy(context, uri)

    fun hasSpaceFor(sourceBytes: Long): Boolean {
        val free = context.cacheDir.usableSpace
        val needed = (sourceBytes.coerceAtLeast(0) * 2) + 50_000_000
        return free >= needed
    }

    suspend fun resolveForFfmpeg(uri: Uri, jobId: String, role: String = "src"): String = withContext(Dispatchers.IO) {
        when (uri.scheme) {
            "file" -> uri.path ?: error("Missing file path")
            else -> {
                val saf = runCatching { FFmpegKitConfig.getSafParameterForRead(context, uri) }.getOrNull()
                if (!saf.isNullOrBlank()) return@withContext saf
                copyToCache(uri, jobId, role).absolutePath
            }
        }
    }

    suspend fun copyToCache(uri: Uri, jobId: String, role: String = "src"): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "imports")
        if (!dir.exists()) dir.mkdirs()
        val suffix = role.ifBlank { "src" }
        val out = File(dir, "$jobId.$suffix")
        context.contentResolver.openInputStream(uri)?.use { input ->
            out.outputStream().use { input.copyTo(it) }
        } ?: error("Unable to open the selected file")
        out
    }

    fun encodeOutputFile(jobId: String, extension: String = "mp4"): File {
        val dir = File(context.cacheDir, "encode")
        if (!dir.exists()) dir.mkdirs()
        val ext = extension.removePrefix(".").ifBlank { "mp4" }
        return File(dir, "$jobId.$ext")
    }

    fun recordOutputFile(jobId: String): File {
        val dir = File(context.cacheDir, "record")
        if (!dir.exists()) dir.mkdirs()
        return File(dir, "$jobId.mp4")
    }

    fun deleteImportCopy(jobId: String) {
        File(context.cacheDir, "imports/$jobId.src").delete()
        File(context.cacheDir, "imports/$jobId.audio").delete()
    }

    fun deleteJobCache(jobId: String) {
        deleteImportCopy(jobId)
        File(context.cacheDir, "record/$jobId.mp4").delete()
        File(context.cacheDir, "record/$jobId.wav").delete()
        File(context.cacheDir, "encode").listFiles()
            ?.filter { cacheJobId(it.name) == jobId }
            ?.forEach { it.delete() }
    }

    fun clearCacheExcept(keepJobIds: Set<String>) {
        for (dirName in CACHE_DIRS) {
            val dir = File(context.cacheDir, dirName)
            dir.listFiles()?.forEach { file ->
                if (cacheJobId(file.name) !in keepJobIds) file.delete()
            }
        }
    }

    fun recordAudioFile(jobId: String): File {
        val dir = File(context.cacheDir, "record")
        if (!dir.exists()) dir.mkdirs()
        return File(dir, "$jobId.wav")
    }

    companion object {
        private val CACHE_DIRS = listOf("imports", "encode", "record")

        fun cacheJobId(fileName: String): String = fileName.substringBeforeLast('.')
    }
}
