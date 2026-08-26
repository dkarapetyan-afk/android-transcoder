package com.androidcompress.app.media

import android.content.Context
import android.net.Uri
import com.androidcompress.app.R
import com.androidcompress.app.agent.JobSettingsCodec
import com.androidcompress.app.data.AudioOption
import com.androidcompress.app.data.JobRepository
import com.androidcompress.app.data.OutputMode
import com.androidcompress.app.data.SettingsJson
import com.androidcompress.app.util.runCatchingLog

data class ShortcutOpenResult(
    val jobId: String? = null,
    val needLibrary: Boolean = false,
    val message: String = "",
    val latestName: String? = null,
    val latestUri: String? = null,
)

class LatestShortcutOpener(
    private val context: Context,
    private val jobs: JobRepository,
    private val importer: JobImporter,
) {
    suspend fun openLatest(audioOnly: Boolean, uriOverride: String? = null): ShortcutOpenResult {
        val override = uriOverride?.trim().orEmpty()
        if (override.isNotBlank()) {
            return importUri(Uri.parse(override), audioOnly)
        }
        val job = LatestVideo.fromJobs(jobs.listAll())
        if (job != null) {
            if (JobSettingsCodec.canEdit(job.status)) {
                if (audioOnly) applyAudioOnly(job.id)
                return ShortcutOpenResult(
                    jobId = job.id,
                    latestName = job.displayName,
                    latestUri = job.sourceUri,
                )
            }
            return importUri(Uri.parse(job.sourceUri), audioOnly).copy(
                latestName = job.displayName,
                latestUri = job.sourceUri,
            )
        }
        if (!MediaLibraryAccess.hasVideo(context)) {
            return ShortcutOpenResult(
                needLibrary = true,
                message = context.getString(R.string.shortcut_need_library),
            )
        }
        val row = runCatchingLog(TAG, "list latest video") {
            DeviceMediaStore.list(context, kind = "VIDEO", query = null, limit = 1).firstOrNull()
        }.getOrNull()
        if (row == null) {
            return ShortcutOpenResult(message = context.getString(R.string.shortcut_no_video))
        }
        return importUri(row.contentUri, audioOnly).copy(
            latestName = row.displayName,
            latestUri = row.contentUri.toString(),
        )
    }

    suspend fun latestLabel(): Pair<String, String>? {
        val job = LatestVideo.fromJobs(jobs.listAll())
        if (job != null) return job.displayName to job.sourceUri
        if (!MediaLibraryAccess.hasVideo(context)) return null
        val row = runCatchingLog(TAG, "latest label") {
            DeviceMediaStore.list(context, kind = "VIDEO", query = null, limit = 1).firstOrNull()
        }.getOrNull() ?: return null
        return row.displayName to row.contentUri.toString()
    }

    private suspend fun importUri(uri: Uri, audioOnly: Boolean): ShortcutOpenResult {
        val id = runCatchingLog(TAG, "import latest") { importer.import(uri) }.getOrElse { error ->
            return ShortcutOpenResult(
                message = error.message ?: context.getString(R.string.error_read_file),
            )
        }
        if (audioOnly) applyAudioOnly(id)
        return ShortcutOpenResult(jobId = id)
    }

    private suspend fun applyAudioOnly(jobId: String) {
        val job = jobs.get(jobId) ?: return
        if (!JobSettingsCodec.canEdit(job.status)) return
        val settings = SettingsJson.decode(job.settingsJson)
        val next = settings.copy(
            output = OutputMode.AUDIO,
            audio = if (settings.audio == AudioOption.MUTE) AudioOption.AAC_128 else settings.audio,
        )
        jobs.upsert(job.copy(settingsJson = SettingsJson.encode(next)))
    }

    private companion object {
        const val TAG = "LatestShortcut"
    }
}
