package com.androidcompress.app.media

import android.content.Intent
import android.net.Uri
import androidx.core.content.IntentCompat
import com.androidcompress.app.util.runCatchingLog

data class ShareRequest(
    val uris: List<Uri>,
    val mimeType: String?,
    val nonce: Long = System.nanoTime(),
)

object ShareIntents {
    private const val TAG = "ShareIntents"
    fun isIncomingAction(action: String?): Boolean = when (action) {
        Intent.ACTION_SEND,
        Intent.ACTION_SEND_MULTIPLE,
        Intent.ACTION_VIEW,
        -> true
        else -> false
    }

    fun isLikelyMedia(mime: String?): Boolean {
        if (mime.isNullOrBlank() || mime == "*/*") return true
        val type = mime.lowercase()
        return type.startsWith("video/") ||
            type.startsWith("audio/") ||
            type == "application/ogg" ||
            type == "application/mp4" ||
            type == "application/octet-stream"
    }

    fun isLikelyImage(mime: String?): Boolean {
        val type = mime?.lowercase().orEmpty()
        return type.startsWith("image/")
    }

    fun isLikelyShareItem(mime: String?): Boolean = isLikelyMedia(mime) || isLikelyImage(mime)

    fun collectUriStrings(
        action: String?,
        stream: String?,
        streams: List<String> = emptyList(),
        data: String? = null,
        clipUris: List<String> = emptyList(),
    ): List<String> {
        if (!isIncomingAction(action)) return emptyList()
        val out = LinkedHashSet<String>()
        when (action) {
            Intent.ACTION_SEND -> if (!stream.isNullOrBlank()) out.add(stream)
            Intent.ACTION_SEND_MULTIPLE -> streams.forEach { if (it.isNotBlank()) out.add(it) }
        }
        if (!data.isNullOrBlank()) out.add(data)
        clipUris.forEach { if (it.isNotBlank()) out.add(it) }
        return out.toList()
    }

    fun urisFrom(intent: Intent): List<Uri> {
        val strings = collectUriStrings(
            action = intent.action,
            stream = streamAsString(intent),
            streams = streamsAsStrings(intent),
            data = intent.dataString,
            clipUris = clipUriStrings(intent),
        )
        return strings.mapNotNull { raw ->
            runCatchingLog(TAG, "parse share uri") { Uri.parse(raw) }.getOrNull()
                ?.takeIf { !it.scheme.isNullOrBlank() }
        }
    }

    private fun streamAsString(intent: Intent): String? {
        if (intent.action != Intent.ACTION_SEND) return null
        IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
            ?.let { return it.toString() }
        return intent.getStringExtra(Intent.EXTRA_STREAM)
    }

    private fun streamsAsStrings(intent: Intent): List<String> {
        if (intent.action != Intent.ACTION_SEND_MULTIPLE) return emptyList()
        return IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
            ?.map { it.toString() }
            .orEmpty()
    }

    private fun clipUriStrings(intent: Intent): List<String> {
        val clip = intent.clipData ?: return emptyList()
        return buildList {
            for (index in 0 until clip.itemCount) {
                clip.getItemAt(index).uri?.toString()?.let(::add)
            }
        }
    }
}
