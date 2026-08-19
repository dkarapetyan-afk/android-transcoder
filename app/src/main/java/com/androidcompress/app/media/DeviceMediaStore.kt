package com.androidcompress.app.media

import android.content.ContentUris
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import java.io.File

data class DeviceMediaRow(
    val contentUri: Uri,
    val displayName: String,
    val kind: String,
    val mimeType: String,
    val bytes: Long,
    val durationMs: Long,
    val relativePath: String,
    val dateAddedEpochMs: Long = 0L,
    val dateModifiedEpochMs: Long = 0L,
)

object DeviceMediaStore {
    fun isLibraryUri(uri: Uri): Boolean {
        if (uri.scheme != "content") return false
        val authority = uri.authority.orEmpty()
        return authority == MediaStore.AUTHORITY ||
            authority == "media" ||
            authority.startsWith("media.")
    }

    fun canKeepWithoutCopy(context: Context, uri: Uri): Boolean {
        if (uri.scheme == "file") return true
        if (context.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission }) {
            return true
        }
        return isLibraryUri(uri) && MediaLibraryAccess.granted(context)
    }

    fun resolve(context: Context, raw: String): Uri {
        val value = DeviceMediaPaths.normalize(raw)
        if (value.isBlank()) error("A content URI, file path, or display name is required.")
        if (DeviceMediaPaths.looksLikeContent(value)) {
            val uri = Uri.parse(value)
            if (isLibraryUri(uri)) MediaLibraryAccess.require(context)
            return uri
        }
        MediaLibraryAccess.require(context)
        val path = DeviceMediaPaths.filePath(value)
        if (path != null) {
            lookupPath(context, path)?.let { return it }
            val file = File(path)
            if (file.isFile && file.canRead()) return Uri.fromFile(file)
            error("No media library entry for $path. Grant Allow all library access, or pass the content:// URI from listDeviceMedia.")
        }
        return lookupName(context, value)
            ?: error("No media named \"$value\". Use listDeviceMedia to see files this app can read.")
    }

    fun list(
        context: Context,
        kind: String?,
        query: String?,
        limit: Int,
        relativePath: String? = null,
        addedAfterEpochMs: Long = 0L,
        minDurationMs: Long = 0L,
        maxDurationMs: Long = 0L,
    ): List<DeviceMediaRow> {
        MediaLibraryAccess.require(context)
        val cap = limit.coerceIn(1, 40)
        val kinds = requestedKinds(kind)
        val rows = ArrayList<DeviceMediaRow>(cap)
        for (target in kinds) {
            if (rows.size >= cap) break
            if (!kindAllowed(context, target)) continue
            val spec = DeviceMediaQuerySpec(
                displayNameQuery = query.orEmpty(),
                relativePath = relativePath.orEmpty(),
                addedAfterEpochMs = addedAfterEpochMs,
                minDurationMs = minDurationMs,
                maxDurationMs = maxDurationMs,
                includeDuration = target != "IMAGE" && Build.VERSION.SDK_INT >= 29,
                includeRelativePath = Build.VERSION.SDK_INT >= 29,
            )
            rows += queryCollection(context, target, spec, cap - rows.size)
        }
        return rows
    }

    private fun requestedKinds(kind: String?): List<String> {
        val normalized = kind?.trim()?.uppercase().orEmpty()
        return when (normalized) {
            "", "ANY", "ALL" -> listOf("VIDEO", "AUDIO", "IMAGE")
            "VIDEO", "AUDIO", "IMAGE" -> listOf(normalized)
            else -> error("kind must be VIDEO, AUDIO, IMAGE, or ANY.")
        }
    }

    private fun kindAllowed(context: Context, kind: String): Boolean = when (kind) {
        "VIDEO" -> MediaLibraryAccess.hasVideo(context)
        "AUDIO" -> MediaLibraryAccess.hasAudio(context)
        "IMAGE" -> MediaLibraryAccess.hasImages(context)
        else -> false
    }

    private fun queryCollection(
        context: Context,
        kind: String,
        spec: DeviceMediaQuerySpec,
        limit: Int,
    ): List<DeviceMediaRow> {
        if (limit <= 0) return emptyList()
        val collection = collectionFor(kind)
        val projection = buildList {
            add(MediaStore.MediaColumns._ID)
            add(MediaStore.MediaColumns.DISPLAY_NAME)
            add(MediaStore.MediaColumns.MIME_TYPE)
            add(MediaStore.MediaColumns.SIZE)
            add(MediaStore.MediaColumns.DATE_ADDED)
            add(MediaStore.MediaColumns.DATE_MODIFIED)
            if (Build.VERSION.SDK_INT >= 29) {
                add(MediaStore.MediaColumns.DURATION)
                add(MediaStore.MediaColumns.RELATIVE_PATH)
            }
        }.toTypedArray()
        val (selection, args) = DeviceMediaQueries.selection(spec)
        val sort = "${MediaStore.MediaColumns.DATE_MODIFIED} DESC"
        val out = ArrayList<DeviceMediaRow>(limit)
        context.contentResolver.query(collection, projection, selection, args, sort)?.use { cursor ->
            val idAt = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val nameAt = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val mimeAt = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
            val sizeAt = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
            val durationAt = cursor.getColumnIndex(MediaStore.MediaColumns.DURATION)
            val pathAt = cursor.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH)
            val addedAt = cursor.getColumnIndex(MediaStore.MediaColumns.DATE_ADDED)
            val modifiedAt = cursor.getColumnIndex(MediaStore.MediaColumns.DATE_MODIFIED)
            while (cursor.moveToNext() && out.size < limit) {
                val id = cursor.getLong(idAt)
                out += DeviceMediaRow(
                    contentUri = ContentUris.withAppendedId(collection, id),
                    displayName = cursor.getString(nameAt).orEmpty().ifBlank { "media-$id" },
                    kind = kind,
                    mimeType = cursor.getString(mimeAt).orEmpty(),
                    bytes = if (sizeAt >= 0) cursor.getLong(sizeAt) else 0L,
                    durationMs = if (durationAt >= 0) cursor.getLong(durationAt) else 0L,
                    relativePath = if (pathAt >= 0) cursor.getString(pathAt).orEmpty() else "",
                    dateAddedEpochMs = epochMs(cursor, addedAt),
                    dateModifiedEpochMs = epochMs(cursor, modifiedAt),
                )
            }
        }
        return out
    }

    private fun lookupPath(context: Context, path: String): Uri? {
        val name = DeviceMediaPaths.displayNameOf(path)
        val relative = DeviceMediaPaths.relativeHint(path)
        val matches = searchByName(context, name)
        if (matches.isEmpty()) return null
        if (relative != null) {
            val hinted = matches.firstOrNull { row ->
                row.relativePath.replace('\\', '/').contains(relative.trimEnd('/'), ignoreCase = true)
            }
            if (hinted != null) return hinted.contentUri
        }
        return matches.first().contentUri
    }

    private fun lookupName(context: Context, name: String): Uri? =
        searchByName(context, name).firstOrNull()?.contentUri

    private fun searchByName(context: Context, name: String): List<DeviceMediaRow> {
        if (name.isBlank()) return emptyList()
        return listOf("VIDEO", "AUDIO", "IMAGE").flatMap { kind ->
            if (!kindAllowed(context, kind)) emptyList()
            else queryCollection(
                context,
                kind,
                DeviceMediaQuerySpec(displayNameQuery = name, includeDuration = false, includeRelativePath = false),
                10,
            ).filter { it.displayName.equals(name, ignoreCase = true) }
        }
    }

    private fun epochMs(cursor: Cursor, column: Int): Long {
        if (column < 0) return 0L
        val seconds = cursor.getLong(column)
        return if (seconds <= 0L) 0L else seconds * 1000L
    }

    private fun collectionFor(kind: String): Uri = when (kind) {
        "AUDIO" -> audioCollection()
        "IMAGE" -> imageCollection()
        else -> videoCollection()
    }

    private fun videoCollection(): Uri =
        if (Build.VERSION.SDK_INT >= 29) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        }

    private fun audioCollection(): Uri =
        if (Build.VERSION.SDK_INT >= 29) {
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        }

    private fun imageCollection(): Uri =
        if (Build.VERSION.SDK_INT >= 29) {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }
}
