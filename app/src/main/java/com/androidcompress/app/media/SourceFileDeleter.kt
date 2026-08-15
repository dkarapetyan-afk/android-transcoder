package com.androidcompress.app.media

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

data class DeleteSourceResult(
    val deleted: Boolean,
    val error: String? = null,
)

object SourceDeletePolicy {
    fun shouldSkip(sourceUri: String, outputUri: String?): Boolean {
        if (sourceUri.isBlank()) return true
        if (outputUri.isNullOrBlank()) return false
        return normalize(sourceUri) == normalize(outputUri)
    }

    fun normalize(value: String): String = value.trim().removePrefix("file://")
}

class SourceFileDeleter(private val context: Context) {

    suspend fun delete(sourceUri: String, outputUri: String?): DeleteSourceResult = withContext(Dispatchers.IO) {
        if (SourceDeletePolicy.shouldSkip(sourceUri, outputUri)) {
            return@withContext DeleteSourceResult(false, "Refusing to delete the compressed output.")
        }
        val uri = runCatching { Uri.parse(sourceUri) }.getOrNull()
            ?: return@withContext DeleteSourceResult(false, "Invalid source")
        when (uri.scheme) {
            null, "file" -> {
                val path = uri.path ?: sourceUri
                val file = File(path)
                if (!file.exists()) return@withContext DeleteSourceResult(true)
                if (file.delete()) DeleteSourceResult(true) else DeleteSourceResult(false, "Could not delete ${file.name}")
            }
            else -> {
                val deleted = runCatching { context.contentResolver.delete(uri, null, null) > 0 }.getOrDefault(false) ||
                    runCatching { DocumentsContract.deleteDocument(context.contentResolver, uri) }.getOrDefault(false)
                if (deleted) {
                    DeleteSourceResult(true)
                } else {
                    DeleteSourceResult(
                        false,
                        "Android did not allow deleting this original. Gallery picks often cannot be removed by other apps.",
                    )
                }
            }
        }
    }
}
