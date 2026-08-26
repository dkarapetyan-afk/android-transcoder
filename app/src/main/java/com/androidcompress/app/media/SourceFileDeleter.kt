package com.androidcompress.app.media

import android.content.Context
import android.net.Uri
import com.androidcompress.app.R
import com.androidcompress.app.util.runCatchingLog
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
    private companion object {
        const val TAG = "SourceDeleter"
    }

    suspend fun deleteSources(uris: List<String>, outputUri: String?): DeleteSourceResult {
        val targets = uris.filter { it.isNotBlank() }
        if (targets.isEmpty()) return DeleteSourceResult(false, context.getString(R.string.error_delete_nothing))
        var deletedAll = true
        var lastError: String? = null
        for (uri in targets) {
            val result = delete(uri, outputUri)
            if (!result.deleted) {
                deletedAll = false
                lastError = result.error
            }
        }
        return DeleteSourceResult(deletedAll, lastError)
    }

    suspend fun delete(sourceUri: String, outputUri: String?): DeleteSourceResult = withContext(Dispatchers.IO) {
        if (SourceDeletePolicy.shouldSkip(sourceUri, outputUri)) {
            return@withContext DeleteSourceResult(false, context.getString(R.string.error_delete_output))
        }
        val uri = runCatchingLog(TAG, "parse source uri") { Uri.parse(sourceUri) }.getOrNull()
            ?: return@withContext DeleteSourceResult(false, context.getString(R.string.error_invalid_source))
        when (uri.scheme) {
            null, "file" -> {
                val path = uri.path ?: sourceUri
                val file = File(path)
                if (!file.exists()) return@withContext DeleteSourceResult(true)
                if (file.delete()) {
                    DeleteSourceResult(true)
                } else {
                    DeleteSourceResult(false, context.getString(R.string.error_delete_named, file.name))
                }
            }
            else -> {
                val deleted = runCatchingLog(TAG, "content delete") {
                    context.contentResolver.delete(uri, null, null) > 0
                }.getOrDefault(false) ||
                    runCatchingLog(TAG, "document delete") {
                        DocumentsContract.deleteDocument(context.contentResolver, uri)
                    }.getOrDefault(false)
                if (deleted) {
                    DeleteSourceResult(true)
                } else {
                    DeleteSourceResult(false, context.getString(R.string.error_delete_gallery))
                }
            }
        }
    }
}
