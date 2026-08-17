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
        val canKeepUri = inputs.canKeepWithoutCopy(uri)
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
        if (info.stillImage) {
            if (!canKeepUri) inputs.deleteImportCopy(id)
            error("Pictures need a soundtrack. Use Combine audio and video.")
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

    suspend fun importShared(uris: List<Uri>, mimeOf: (Uri) -> String?): Batch {
        val plan = CombinePairing.plan(uris, mimeOf) { uri ->
            runCatching { probe.displayName(uri) }.getOrNull()
        }
        if (plan.pairs.isEmpty()) return importAll(uris)
        val ids = ArrayList<String>(plan.pairs.size + plan.leftovers.size)
        val errors = ArrayList<String>()
        for (pair in plan.pairs) {
            runCatching { importCombine(pair.visual, pair.audio) }
                .onSuccess(ids::add)
                .onFailure { errors.add(it.message ?: "Unable to combine those files") }
        }
        if (plan.leftovers.isNotEmpty()) {
            val extra = importAll(plan.leftovers)
            ids.addAll(extra.jobIds)
            errors.addAll(extra.errors)
        }
        return Batch(ids, errors)
    }

    suspend fun importCombine(visualUri: Uri, audioUri: Uri): String {
        val id = UUID.randomUUID().toString()
        val visualStored = store(visualUri, id, "src")
        val audioStored = try {
            store(audioUri, id, "audio")
        } catch (error: Throwable) {
            inputs.deleteImportCopy(id)
            throw error
        }
        val visual = try {
            probe.probe(visualStored)
        } catch (error: Throwable) {
            inputs.deleteImportCopy(id)
            throw error
        }
        val audio = try {
            probe.probe(audioStored)
        } catch (error: Throwable) {
            inputs.deleteImportCopy(id)
            throw error
        }
        if (!audio.hasAudio && audio.durationMs <= 0L) {
            inputs.deleteImportCopy(id)
            error("The second file has no audio to use as a soundtrack.")
        }
        if (!visual.hasVideo && !visual.stillImage) {
            inputs.deleteImportCopy(id)
            error("The first file needs to be a picture or a video.")
        }
        val stillImage = visual.stillImage || (visual.durationMs <= 0L && visual.width > 0 && visual.height > 0)
        val duration = CombinePairing.outputDurationMs(visual.durationMs, audio.durationMs, stillImage)
        if (duration <= 0L) {
            inputs.deleteImportCopy(id)
            error("That soundtrack has no duration.")
        }
        val visualName = runCatching { probe.displayName(visualUri) }.getOrNull()
            ?.takeIf { it.isNotBlank() } ?: visual.displayName
        val audioName = runCatching { probe.displayName(audioUri) }.getOrNull()
            ?.takeIf { it.isNotBlank() } ?: audio.displayName
        val currentPrefs = prefs.current()
        val settings = EncodeSettings.forPreset(currentPrefs.defaultPreset, currentPrefs.defaultEngine).let { base ->
            base.copy(
                output = OutputMode.VIDEO,
                audio = if (base.audio == AudioOption.MUTE) AudioOption.AAC_128 else base.audio,
            )
        }
        jobs.upsert(
            CompressJob(
                id = id,
                type = JobType.COMBINE,
                status = JobStatus.READY,
                sourceUri = visualStored.toString(),
                outputUri = null,
                displayName = "$visualName + $audioName",
                sourceBytes = visual.bytes + audio.bytes,
                outputBytes = null,
                durationMs = duration,
                width = visual.width,
                height = visual.height,
                settingsJson = SettingsJson.encode(settings),
                error = null,
                createdAt = System.currentTimeMillis(),
                finishedAt = null,
                audioUri = audioStored.toString(),
                stillImage = stillImage,
            ),
        )
        runCatching { history.prune() }
        return id
    }

    private suspend fun store(uri: Uri, jobId: String, role: String): Uri {
        inputs.takePersistableAccess(uri)
        val canKeepUri = inputs.canKeepWithoutCopy(uri)
        return if (canKeepUri) {
            uri
        } else {
            val hintedBytes = runCatching { probe.sizeBytes(uri) }.getOrDefault(0L)
            if (hintedBytes > 0 && !inputs.hasSpaceFor(hintedBytes)) {
                error("Not enough free storage to open this shared file.")
            }
            Uri.fromFile(inputs.copyToCache(uri, jobId, role))
        }
    }
}
