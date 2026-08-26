package com.androidcompress.app.media

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.androidcompress.app.R
import com.androidcompress.app.util.onFailureLog
import com.androidcompress.app.util.runCatchingLog
import com.arthenica.ffmpegkit.FFmpegKitConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class InputResolver(private val context: Context) {

    fun takePersistableAccess(uri: Uri) {
        val resolver = context.contentResolver
        val readWrite = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        runCatchingLog(TAG, "persistable read/write") {
            resolver.takePersistableUriPermission(uri, readWrite)
        }.recoverCatching { resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            .onFailureLog(TAG, "persistable read")
    }

    fun hasPersistableRead(uri: Uri): Boolean =
        context.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission }

    fun canKeepWithoutCopy(uri: Uri): Boolean = DeviceMediaStore.canKeepWithoutCopy(context, uri)

    fun hasSpaceFor(neededBytes: Long): Boolean =
        context.cacheDir.usableSpace >= neededBytes.coerceAtLeast(0)

    suspend fun resolveForFfmpeg(uri: Uri, jobId: String, role: String = "src"): String = withContext(Dispatchers.IO) {
        when (uri.scheme) {
            "file" -> uri.path ?: error("Missing file path")
            else -> {
                val saf = runCatchingLog(TAG, "ffmpeg saf") {
                    FFmpegKitConfig.getSafParameterForRead(context, uri)
                }.getOrNull()
                if (!saf.isNullOrBlank()) return@withContext saf
                copyToCache(uri, jobId, role).absolutePath
            }
        }
    }

    /**
     * FFmpeg-Kit deletes the `saf:N` mapping in `safClose` when a session ends.
     * Reuse that path on a second pass / retry and the demuxer sees an empty pipe
     * (`EBML header parsing failed`). Copy once, or mint a new SAF id per session.
     */
    suspend fun refreshFfmpegInput(
        uri: Uri,
        jobId: String,
        previous: String,
        role: String = "src",
    ): String = withContext(Dispatchers.IO) {
        if (previous.isNotBlank() && !isSafParameter(previous)) {
            val file = File(previous)
            if (file.exists() && file.length() > 0L) return@withContext previous
        }
        resolveForFfmpeg(uri, jobId, role)
    }

    suspend fun copyToCache(uri: Uri, jobId: String, role: String = "src"): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "imports")
        if (!dir.exists()) dir.mkdirs()
        val suffix = role.ifBlank { "src" }
        val out = File(dir, "$jobId.$suffix")
        context.contentResolver.openInputStream(uri)?.use { input ->
            out.outputStream().use { input.copyTo(it, bufferSize = COPY_BUFFER_BYTES) }
        } ?: error(context.getString(R.string.error_open_file))
        out
    }

    fun encodeOutputFile(jobId: String, extension: String = "mp4"): File {
        val dir = File(context.cacheDir, "encode")
        if (!dir.exists()) dir.mkdirs()
        val ext = extension.removePrefix(".").ifBlank { "mp4" }
        return File(dir, "$jobId.$ext")
    }

    fun passLogPrefix(jobId: String): String {
        val dir = File(context.cacheDir, "encode")
        if (!dir.exists()) dir.mkdirs()
        return File(dir, "$jobId.2pass").absolutePath
    }

    fun ffmpegProgressFile(jobId: String): File {
        val dir = File(context.cacheDir, "encode")
        if (!dir.exists()) dir.mkdirs()
        return File(dir, "$jobId.ffprogress")
    }

    fun deletePassLogs(jobId: String) {
        val dir = File(context.cacheDir, "encode")
        dir.listFiles()?.filter {
            it.name.startsWith("$jobId.2pass") || it.name == "$jobId.ffprogress"
        }?.forEach { it.delete() }
    }

    fun recordOutputFile(jobId: String, extension: String = "mp4"): File {
        val dir = File(context.cacheDir, "record")
        if (!dir.exists()) dir.mkdirs()
        val ext = extension.removePrefix(".").ifBlank { "mp4" }
        return File(dir, "$jobId.$ext")
    }

    fun copyJobCache(fromJobId: String, toJobId: String) {
        if (fromJobId.isBlank() || toJobId.isBlank() || fromJobId == toJobId) return
        for (dirName in CACHE_DIRS) {
            val dir = File(context.cacheDir, dirName)
            val files = dir.listFiles() ?: continue
            for (file in files) {
                val remapped = remapCachedFileName(file.name, fromJobId, toJobId) ?: continue
                file.copyTo(File(dir, remapped), overwrite = true)
            }
        }
    }

    fun deleteImportCopy(jobId: String) {
        File(context.cacheDir, "imports/$jobId.src").delete()
        File(context.cacheDir, "imports/$jobId.audio").delete()
    }

    fun deleteJobCache(jobId: String) {
        deleteImportCopy(jobId)
        File(context.cacheDir, "record").listFiles()
            ?.filter { it.name.startsWith(jobId) }
            ?.forEach { it.delete() }
        File(context.cacheDir, "encode").listFiles()
            ?.filter { cacheJobId(it.name) == jobId || it.name.startsWith("$jobId.2pass") }
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

    fun recordMicAudioFile(jobId: String): File {
        val dir = File(context.cacheDir, "record")
        if (!dir.exists()) dir.mkdirs()
        return File(dir, "$jobId.mic.wav")
    }

    companion object {
        private const val TAG = "InputResolver"
        const val COPY_BUFFER_BYTES = 64 * 1024
        const val STORAGE_OVERHEAD_BYTES = 50_000_000L
        private val CACHE_DIRS = listOf("imports", "encode", "record")

        fun isSafParameter(path: String): Boolean =
            path.startsWith("saf:", ignoreCase = true)

        fun bytesNeededForCopy(sourceBytes: Long): Long =
            sourceBytes.coerceAtLeast(0) + STORAGE_OVERHEAD_BYTES

        fun bytesNeededForEncode(estimatedOutputBytes: Long, sourceBytes: Long, durationMs: Long): Long {
            val estimated = estimatedOutputBytes.coerceAtLeast(0)
            val output = if (durationMs > 0L) estimated else maxOf(estimated, sourceBytes.coerceAtLeast(0))
            return output * 2 + STORAGE_OVERHEAD_BYTES
        }

        fun cacheJobId(fileName: String): String {
            val twoPassAt = fileName.indexOf(".2pass")
            if (twoPassAt > 0) return fileName.substring(0, twoPassAt)
            return fileName.substringBeforeLast('.')
        }

        fun remapCachedFileName(fileName: String, fromJobId: String, toJobId: String): String? {
            if (fromJobId.isBlank() || toJobId.isBlank() || fromJobId == toJobId) return null
            if (cacheJobId(fileName) != fromJobId) return null
            if (!fileName.startsWith(fromJobId)) return null
            return toJobId + fileName.substring(fromJobId.length)
        }

        fun remapCachedUri(uriString: String, fromJobId: String, toJobId: String): String {
            if (uriString.isBlank()) return uriString
            val uri = Uri.parse(uriString)
            val path = uri.path ?: return uriString
            val file = File(path)
            val remapped = remapCachedFileName(file.name, fromJobId, toJobId) ?: return uriString
            val parent = file.parentFile ?: return uriString
            return Uri.fromFile(File(parent, remapped)).toString()
        }
    }
}
