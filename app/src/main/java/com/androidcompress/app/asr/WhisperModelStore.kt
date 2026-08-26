package com.androidcompress.app.asr

import android.content.Context
import com.androidcompress.app.util.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

class WhisperModelStore(context: Context) {
    private val root = File(context.applicationContext.filesDir, "models/${WhisperModels.DIR_NAME}")
    private val mutex = Mutex()

    val encoder: File get() = File(root, WhisperModels.ENCODER)
    val decoder: File get() = File(root, WhisperModels.DECODER)
    val tokens: File get() = File(root, WhisperModels.TOKENS)
    val vad: File get() = File(root, WhisperModels.VAD)

    fun isReady(): Boolean {
        val marker = File(root, WhisperModels.READY)
        return marker.isFile && WhisperModels.files.all { file ->
            val onDisk = File(root, file.name)
            onDisk.isFile && onDisk.length() >= file.minBytes
        }
    }

    suspend fun ensureReady(
        onProgress: (Float) -> Unit = {},
        isCancelled: () -> Boolean = { false },
    ) = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (isReady()) {
                onProgress(1f)
                return@withLock
            }
            if (!root.exists() && !root.mkdirs()) {
                error("Could not create model directory")
            }
            val pending = WhisperModels.files.filter { file ->
                val onDisk = File(root, file.name)
                !(onDisk.isFile && onDisk.length() >= file.minBytes)
            }
            if (pending.isEmpty()) {
                File(root, WhisperModels.READY).writeText("ok")
                onProgress(1f)
                return@withLock
            }
            val weights = pending.map { it.minBytes.coerceAtLeast(1L) }
            val totalWeight = weights.sum().toFloat()
            var doneWeight = 0f
            pending.forEachIndexed { index, file ->
                if (isCancelled()) error("cancelled")
                val dest = File(root, file.name)
                download(file, dest, isCancelled) { part ->
                    val local = doneWeight + weights[index] * part.coerceIn(0f, 1f)
                    onProgress((local / totalWeight).coerceIn(0f, 0.99f))
                }
                doneWeight += weights[index]
                onProgress((doneWeight / totalWeight).coerceIn(0f, 0.99f))
            }
            File(root, WhisperModels.READY).writeText("ok")
            onProgress(1f)
        }
    }

    private fun download(
        file: ModelFile,
        dest: File,
        isCancelled: () -> Boolean,
        onProgress: (Float) -> Unit,
    ) {
        var lastError: Exception? = null
        for (url in file.urls) {
            val part = File(dest.parentFile, "${dest.name}.part")
            part.delete()
            try {
                val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = true
                    connectTimeout = 30_000
                    readTimeout = 60_000
                    requestMethod = "GET"
                    setRequestProperty("User-Agent", "RecordingCompressor/1.0")
                    connect()
                }
                try {
                    val code = connection.responseCode
                    if (code !in 200..299) {
                        error("HTTP $code for ${file.name}")
                    }
                    val expected = connection.contentLengthLong.takeIf { it > 0L } ?: file.minBytes
                    var written = 0L
                    connection.inputStream.use { input ->
                        part.outputStream().use { output ->
                            val buf = ByteArray(64 * 1024)
                            while (true) {
                                if (isCancelled()) error("cancelled")
                                val n = input.read(buf)
                                if (n <= 0) break
                                output.write(buf, 0, n)
                                written += n
                                onProgress((written.toFloat() / expected.toFloat()).coerceIn(0f, 1f))
                            }
                        }
                    }
                    if (written < file.minBytes) {
                        error("${file.name} is too small ($written bytes)")
                    }
                    dest.delete()
                    if (!part.renameTo(dest)) {
                        part.copyTo(dest, overwrite = true)
                        part.delete()
                    }
                    return
                } finally {
                    connection.disconnect()
                }
            } catch (t: Throwable) {
                part.delete()
                dest.delete()
                if (isCancelled() || t.message == "cancelled") throw t
                AppLog.e(TAG, "download ${file.name} from $url", t)
                lastError = t as? Exception ?: RuntimeException(t)
            }
        }
        throw lastError ?: IllegalStateException("Could not download ${file.name}")
    }

    private companion object {
        const val TAG = "WhisperModels"
    }
}
