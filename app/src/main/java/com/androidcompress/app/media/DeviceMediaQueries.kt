package com.androidcompress.app.media

import android.provider.MediaStore

data class DeviceMediaQuerySpec(
    val displayNameQuery: String = "",
    val relativePath: String = "",
    val addedAfterEpochMs: Long = 0L,
    val minDurationMs: Long = 0L,
    val maxDurationMs: Long = 0L,
    val includeDuration: Boolean = true,
    val includeRelativePath: Boolean = true,
)

object DeviceMediaQueries {
    fun normalizeRelativePath(raw: String?): String =
        raw.orEmpty().trim().replace('\\', '/').trim('/')

    fun selection(spec: DeviceMediaQuerySpec): Pair<String?, Array<String>?> {
        val clauses = ArrayList<String>()
        val args = ArrayList<String>()
        val needle = spec.displayNameQuery.trim()
        if (needle.isNotBlank()) {
            clauses += "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?"
            args += "%$needle%"
        }
        val relative = normalizeRelativePath(spec.relativePath)
        if (relative.isNotBlank() && spec.includeRelativePath) {
            clauses += "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?"
            args += "%$relative%"
        }
        if (spec.addedAfterEpochMs > 0L) {
            clauses += "${MediaStore.MediaColumns.DATE_ADDED} >= ?"
            args += (spec.addedAfterEpochMs / 1000L).toString()
        }
        if (spec.includeDuration && spec.minDurationMs > 0L) {
            clauses += "${MediaStore.MediaColumns.DURATION} >= ?"
            args += spec.minDurationMs.toString()
        }
        if (spec.includeDuration && spec.maxDurationMs > 0L) {
            clauses += "${MediaStore.MediaColumns.DURATION} <= ?"
            args += spec.maxDurationMs.toString()
        }
        if (clauses.isEmpty()) return null to null
        return clauses.joinToString(" AND ") to args.toTypedArray()
    }
}
