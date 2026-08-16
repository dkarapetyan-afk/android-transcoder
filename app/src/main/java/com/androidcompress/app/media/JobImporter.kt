package com.androidcompress.app.media

import android.net.Uri
import com.androidcompress.app.data.AudioOption
import com.androidcompress.app.data.CompressJob
import com.androidcompress.app.data.EncodeSettings
import com.androidcompress.app.data.HistoryJanitor
import com.androidcompress.app.data.JobRepository
import com.androidcompress.app.data.JobStatus
import com.androidcompress.app.data.JobType
import com.androidcompress.app.data.OutputMode
import com.androidcompress.app.data.PreferencesRepository
import com.androidcompress.app.data.SettingsJson
import java.util.UUID

class JobImporter(
    private val jobs: JobRepository,
    private val prefs: PreferencesRepository,
    private val probe: MediaProbe,
    private val inputs: InputResolver,
    private val history: HistoryJanitor,
) {
    data class Batch(val jobIds: List<String>, val errors: List<String>)

    suspend fun importAll(uris: List<Uri>): Batch {
        val ids = ArrayList<String>(uris.size)
        val errors = ArrayList<String>()
        for (uri in uris) {
            runCatching { import(uri) }
                .onSuccess(ids::add)
                .onFailure { errors.add(it.message ?: "Unable to read that file") }
        }
        return Batch(ids, errors)
    }

    suspend fun import(uri: Uri): String {
        val id = UUID.randomUUID().toString()
        inputs.takePersistableAccess(uri)
        val canKeepUri = uri.scheme == "file" || inputs.hasPersistableRead(uri)
        val originalName = runCatching { probe.displayName(uri) }.getOrNull()
        val stored = if (canKeepUri) {
            uri
        } else {
            val hintedBytes = runCatching { probe.sizeBytes(uri) }.getOrDefault(0L)
            if (hintedBytes > 0 && !inputs.hasSpaceFor(hintedBytes)) {
                error("Not enough free storage to open this shared file.")
            }
            Uri.fromFile(inputs.copyToCache(uri, id))
        }
        val info = try {
            probe.probe(stored)
        } catch (error: Throwable) {
            if (!canKeepUri) inputs.deleteImportCopy(id)
            throw error
        }
        val displayName = originalName?.takeIf { it.isNotBlank() } ?: info.displayName
        val currentPrefs = prefs.current()
        val base = EncodeSettings.forPreset(currentPrefs.defaultPreset, currentPrefs.defaultEngine)
        val audioOut = !info.hasVideo
        val prepared = base.copy(
            output = if (audioOut) OutputMode.AUDIO else OutputMode.VIDEO,
            audio = if (audioOut && base.audio == AudioOption.MUTE) AudioOption.AAC_128 else base.audio,
        )
        jobs.upsert(
            CompressJob(
                id = id,
                type = JobType.IMPORT,
                status = JobStatus.READY,
                sourceUri = stored.toString(),
                outputUri = null,
                displayName = displayName,
                sourceBytes = info.bytes,
                outputBytes = null,
                durationMs = info.durationMs,
                width = info.width,
                height = info.height,
                settingsJson = SettingsJson.encode(prepared),
                error = null,
                createdAt = System.currentTimeMillis(),
                finishedAt = null,
            ),
        )
        runCatching { history.prune() }
        return id
    }
}
