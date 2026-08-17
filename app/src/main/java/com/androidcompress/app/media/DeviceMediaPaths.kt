package com.androidcompress.app.media

object DeviceMediaPaths {
    fun normalize(raw: String): String = raw.trim().trim('"')

    fun looksLikeContent(raw: String): Boolean = raw.startsWith("content:", ignoreCase = true)

    fun looksLikeFileUri(raw: String): Boolean = raw.startsWith("file:", ignoreCase = true)

    fun looksLikeAbsolutePath(raw: String): Boolean = raw.startsWith("/")

    fun filePath(raw: String): String? {
        val value = normalize(raw)
        return when {
            looksLikeFileUri(value) -> {
                val after = value.substringAfter(":", "").removePrefix("//")
                when {
                    after.isBlank() -> null
                    after.startsWith("/") -> after
                    else -> "/$after"
                }
            }
            looksLikeAbsolutePath(value) -> value
            else -> null
        }
    }

    fun displayNameOf(path: String): String =
        path.replace('\\', '/').substringAfterLast('/').ifBlank { path }

    fun relativeHint(path: String): String? {
        val unified = path.replace('\\', '/')
        val folders = listOf("Download", "Movies", "DCIM", "Pictures", "Music", "Recordings", "Documents", "Podcasts")
        for (folder in folders) {
            val marker = "/$folder/"
            val index = unified.indexOf(marker, ignoreCase = true)
            if (index >= 0) {
                val fromFolder = unified.substring(index + 1)
                val dir = fromFolder.substringBeforeLast('/', missingDelimiterValue = "")
                if (dir.isNotBlank()) return dir.trimEnd('/') + "/"
            }
        }
        return null
    }
}
