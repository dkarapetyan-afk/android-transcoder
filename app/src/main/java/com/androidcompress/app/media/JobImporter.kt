package com.androidcompress.app.media

import android.content.Context
import android.net.Uri
import com.androidcompress.app.R
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
import com.androidcompress.app.util.runCatchingLog
import java.util.UUID

class JobImporter(
    private val context: Context,
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
            runCatchingLog(TAG, "import") { import(uri) }
                .onSuccess(ids::add)
                .onFailure { errors.add(it.message ?: context.getString(R.string.error_read_file)) }
        }
        return Batch(ids, errors)
    }

    suspend fun import(uri: Uri): String {
        val id = UUID.randomUUID().toString()
        inputs.takePersistableAccess(uri)
        val canKeepUri = inputs.canKeepWithoutCopy(uri)
        val originalName = runCatchingLog(TAG, "display name") { probe.displayName(uri) }.getOrNull()
        val stored = if (canKeepUri) {
            uri
        } else {
            val hintedBytes = runCatchingLog(TAG, "size bytes") { probe.sizeBytes(uri) }.getOrDefault(0L)
            if (hintedBytes > 0 && !inputs.hasSpaceFor(InputResolver.bytesNeededForCopy(hintedBytes))) {
                error(context.getString(R.string.error_not_enough_storage_import))
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
            error(context.getString(R.string.error_pictures_need_soundtrack))
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
        runCatchingLog(TAG, "prune history") { history.prune() }
        return id
    }

    suspend fun importShared(uris: List<Uri>, mimeOf: (Uri) -> String?): Batch {
        val plan = planCombine(uris, mimeOf)
        if (plan.pairs.isEmpty()) return importAll(uris)
        val combined = importPairs(plan.pairs)
        if (plan.leftovers.isEmpty()) return combined
        val extra = importAll(plan.leftovers)
        return Batch(combined.jobIds + extra.jobIds, combined.errors + extra.errors)
    }

    /** Home combine: same pairing as Share, but unpaired files are an error, not compress jobs. */
    suspend fun importCombinePicks(uris: List<Uri>, mimeOf: (Uri) -> String?): Batch {
        val plan = planCombine(uris, mimeOf)
        if (plan.pairs.isEmpty()) {
            return Batch(emptyList(), listOf(context.getString(R.string.error_combine_need_pair)))
        }
        return importPairs(plan.pairs)
    }

    private fun planCombine(uris: List<Uri>, mimeOf: (Uri) -> String?): CombinePlan =
        CombinePairing.plan(uris, mimeOf) { uri ->
            runCatchingLog(TAG, "display name") { probe.displayName(uri) }.getOrNull()
        }

    private suspend fun importPairs(pairs: List<CombinePair>): Batch {
        val ids = ArrayList<String>(pairs.size)
        val errors = ArrayList<String>()
        for (pair in pairs) {
            runCatchingLog(TAG, "import combine") { importCombine(pair.visual, pair.audio) }
                .onSuccess(ids::add)
                .onFailure { errors.add(it.message ?: context.getString(R.string.error_combine)) }
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
            error(context.getString(R.string.error_combine_no_audio))
        }
        if (!visual.hasVideo && !visual.stillImage) {
            inputs.deleteImportCopy(id)
            error(context.getString(R.string.error_combine_need_visual))
        }
        val stillImage = visual.stillImage || (visual.durationMs <= 0L && visual.width > 0 && visual.height > 0)
        val duration = CombinePairing.outputDurationMs(visual.durationMs, audio.durationMs, stillImage)
        if (duration <= 0L) {
            inputs.deleteImportCopy(id)
            error(context.getString(R.string.error_combine_no_duration))
        }
        val visualName = runCatchingLog(TAG, "visual name") { probe.displayName(visualUri) }.getOrNull()
            ?.takeIf { it.isNotBlank() } ?: visual.displayName
        val audioName = runCatchingLog(TAG, "audio name") { probe.displayName(audioUri) }.getOrNull()
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
                displayName = context.getString(R.string.combine_display_name, visualName, audioName),
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
        runCatchingLog(TAG, "prune history") { history.prune() }
        return id
    }

    private suspend fun store(uri: Uri, jobId: String, role: String): Uri {
        inputs.takePersistableAccess(uri)
        val canKeepUri = inputs.canKeepWithoutCopy(uri)
        return if (canKeepUri) {
            uri
        } else {
            val hintedBytes = runCatchingLog(TAG, "size bytes") { probe.sizeBytes(uri) }.getOrDefault(0L)
            if (hintedBytes > 0 && !inputs.hasSpaceFor(InputResolver.bytesNeededForCopy(hintedBytes))) {
                error(context.getString(R.string.error_not_enough_storage_import))
            }
            Uri.fromFile(inputs.copyToCache(uri, jobId, role))
        }
    }

    private companion object {
        const val TAG = "JobImporter"
    }
}
