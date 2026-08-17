package com.androidcompress.app.media

import android.net.Uri

enum class MediaKind { IMAGE, VIDEO, AUDIO, OTHER }

data class CombinePair(
    val visual: Uri,
    val audio: Uri,
)

data class CombinePlan(
    val pairs: List<CombinePair>,
    val leftovers: List<Uri> = emptyList(),
)

object CombinePairing {
    fun kind(mime: String?, displayName: String? = null): MediaKind {
        val type = mime?.lowercase().orEmpty()
        if (type.startsWith("image/")) return MediaKind.IMAGE
        if (type.startsWith("video/")) return MediaKind.VIDEO
        if (type.startsWith("audio/") || type == "application/ogg") return MediaKind.AUDIO
        val name = displayName?.lowercase().orEmpty()
        val ext = name.substringAfterLast('.', "")
        return when (ext) {
            "jpg", "jpeg", "png", "webp", "heic", "heif", "gif", "bmp", "avif" -> MediaKind.IMAGE
            "mp4", "webm", "mkv", "mov", "m4v", "avi", "3gp" -> MediaKind.VIDEO
            "m4a", "aac", "mp3", "wav", "flac", "ogg", "opus", "wma" -> MediaKind.AUDIO
            else -> MediaKind.OTHER
        }
    }

    fun plan(uris: List<Uri>, mimeOf: (Uri) -> String?, nameOf: (Uri) -> String? = { null }): CombinePlan {
        val planned = planItems(uris) { uri -> kind(mimeOf(uri), nameOf(uri)) }
        return CombinePlan(
            pairs = planned.pairs.map { CombinePair(it.first, it.second) },
            leftovers = planned.leftovers,
        )
    }

    fun pair(uris: List<Uri>, mimeOf: (Uri) -> String?, nameOf: (Uri) -> String? = { null }): CombinePair? =
        plan(uris, mimeOf, nameOf).pairs.firstOrNull()

    fun <T> pairItems(items: List<T>, kindOf: (T) -> MediaKind): Pair<T, T>? =
        planItems(items, kindOf).pairs.firstOrNull()

    fun <T> planItems(items: List<T>, kindOf: (T) -> MediaKind): ItemPlan<T> {
        if (items.size < 2) return ItemPlan()
        val classified = items.map { item -> item to kindOf(item) }
        val images = classified.filter { it.second == MediaKind.IMAGE }.map { it.first }
        val videos = classified.filter { it.second == MediaKind.VIDEO }.map { it.first }
        val audios = classified.filter { it.second == MediaKind.AUDIO }.map { it.first }
        val others = classified.filter { it.second == MediaKind.OTHER }.map { it.first }
        if (audios.isNotEmpty()) {
            val visuals = images + videos
            if (visuals.isEmpty()) return ItemPlan(leftovers = items)
            return ItemPlan(
                pairs = visuals.flatMap { visual -> audios.map { visual to it } },
                leftovers = others,
            )
        }
        if (images.isNotEmpty() && videos.isNotEmpty()) {
            val visual = images.first()
            val soundtrack = videos.first()
            return ItemPlan(
                pairs = listOf(visual to soundtrack),
                leftovers = images.drop(1) + videos.drop(1) + others,
            )
        }
        return ItemPlan()
    }

    data class ItemPlan<T>(
        val pairs: List<Pair<T, T>> = emptyList(),
        val leftovers: List<T> = emptyList(),
    )

    fun outputDurationMs(visualDurationMs: Long, audioDurationMs: Long, stillImage: Boolean): Long {
        val audio = audioDurationMs.coerceAtLeast(0L)
        if (stillImage || visualDurationMs <= 0L) return audio
        if (audio <= 0L) return visualDurationMs
        return minOf(visualDurationMs, audio)
    }
}
