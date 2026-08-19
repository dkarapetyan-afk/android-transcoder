package com.androidcompress.app.agent

import android.net.Uri
import androidx.annotation.RequiresApi
import androidx.appfunctions.AppFunction
import androidx.appfunctions.AppFunctionElementNotFoundException
import androidx.appfunctions.AppFunctionInvalidArgumentException
import androidx.appfunctions.AppFunctionService
import androidx.appfunctions.AppFunctionServiceEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Recording Compressor tools for system agents. Use these to inspect jobs, fully
 * customize encode settings, start or wait for the queue, share a result, and
 * read progress.
 */
@RequiresApi(36)
@AppFunctionServiceEntryPoint(
    serviceName = "CompressAppFunctionService",
    appFunctionXmlFileName = "compress_app_function_service",
)
abstract class BaseCompressAppFunctionService : AppFunctionService() {

    private val agent by lazy { JobAgent(applicationContext) }

    /**
     * Describe Recording Compressor, the recommended agent workflow, and every
     * allowed enum value for settings. Call this before customizing a job.
     */
    @AppFunction(isDescribedByKDoc = true)
    suspend fun describeCapabilities(): AppCapabilities = io { agent.describeCapabilities() }

    /**
     * List the built-in encode presets (SMALLER, BALANCED, HIGHER) and the
     * settings each one applies.
     */
    @AppFunction(isDescribedByKDoc = true)
    suspend fun listPresets(): List<PresetInfo> = io { agent.listPresets() }

    /**
     * List recent compress jobs. Does not return file URIs.
     *
     * @param status Optional status filter such as READY, QUEUED, RUNNING, SUCCEEDED, FAILED, CANCELLED, or ALL.
     * @param limit Maximum jobs to return, from 1 to 40. Defaults to 20.
     */
    @AppFunction(isDescribedByKDoc = true)
    suspend fun listJobs(
        status: String? = null,
        limit: Int = 20,
    ): JobListResult = io { agent.listJobs(status, limit) }

    /**
     * Get one job, including its full encode settings. Use the returned jobId
     * with updateJobSettings, startJob, getProgress, or cancelJob.
     *
     * @param jobId The job id from listJobs or importFile.
     */
    @AppFunction(isDescribedByKDoc = true)
    suspend fun getJob(jobId: String): JobDetail = io { agent.getJob(jobId) }

    /**
     * Show the live encode queue: the running job first, then queued jobs in
     * FIFO order, plus how many READY jobs can still be started.
     */
    @AppFunction(isDescribedByKDoc = true)
    suspend fun getQueue(): QueueSnapshot = io { agent.getQueue() }

    /**
     * Check encode progress. Pass a jobId, or omit it to watch the job that is
     * currently running.
     *
     * @param jobId Job to inspect. Null means the current running job.
     */
    @AppFunction(isDescribedByKDoc = true)
    suspend fun getProgress(jobId: String? = null): ProgressSnapshot = io { agent.getProgress(jobId) }

    /**
     * Read the tail of an encode log. Useful after a failure. The log may include
     * local cache paths; do not upload it.
     *
     * @param jobId Job whose log to read. Null means the last encode.
     * @param maxChars Maximum characters to return, from 256 to 16000.
     */
    @AppFunction(isDescribedByKDoc = true)
    suspend fun getEncodeLog(
        jobId: String? = null,
        maxChars: Int = 4000,
    ): EncodeLogSnapshot = io { agent.getEncodeLog(jobId, maxChars) }

    /**
     * Reset a job to a built-in preset. Other advanced fields are replaced by
     * that preset's defaults. The job must not be queued, running, or recording.
     *
     * @param jobId Job to change.
     * @param preset SMALLER, BALANCED, or HIGHER.
     * @param engine Optional FFMPEG or MEDIA3. Null keeps the job's current engine.
     */
    @AppFunction(isDescribedByKDoc = true)
    suspend fun applyPreset(
        jobId: String,
        preset: String,
        engine: String? = null,
    ): JobDetail = io { agent.applyPreset(jobId, preset, engine) }

    /**
     * Fully customize one job. Null fields stay unchanged. Setting preset first
     * resets to that preset, then the other fields overlay it. Cannot edit a
     * queued, running, or recording job.
     *
     * @param jobId Job to change.
     * @param settings Fields to change. See describeCapabilities for allowed values.
     */
    @AppFunction(isDescribedByKDoc = true)
    suspend fun updateJobSettings(
        jobId: String,
        settings: JobSettingsUpdate,
    ): JobDetail = io { agent.updateJobSettings(jobId, settings) }

    /**
     * Preview the FFmpeg command (or Media3 encoder label) and estimated output
     * size for a job's current settings. Does not start encoding.
     *
     * @param jobId Job to preview.
     */
    @AppFunction(isDescribedByKDoc = true)
    suspend fun previewEncode(jobId: String): EncodePreview = io { agent.previewEncode(jobId) }

    /**
     * Queue a job and start the encode service. Optionally apply a last settings
     * patch first. deleteSourceAfter defaults to false even if the app setting is on.
     *
     * @param jobId Job to start. Must already have a source file.
     * @param settings Optional last-minute settings patch. Pass an empty object to keep current settings.
     * @param deleteSourceAfter If true, delete the source after a successful encode.
     */
    @AppFunction(isDescribedByKDoc = true)
    suspend fun startJob(
        jobId: String,
        settings: JobSettingsUpdate? = null,
        deleteSourceAfter: Boolean = false,
    ): JobActionResult = io { agent.startJob(jobId, settings, deleteSourceAfter) }

    /**
     * Start every READY job, oldest-created first, up to [limit]. The same
     * optional settings patch is applied to each job before it is queued.
     *
     * @param limit Maximum READY jobs to start, from 1 to 40.
     * @param settings Optional settings applied to each started job.
     */
    @AppFunction(isDescribedByKDoc = true)
    suspend fun startReadyJobs(
        limit: Int = 20,
        settings: JobSettingsUpdate? = null,
    ): JobActionResult = io { agent.startReadyJobs(limit, settings) }

    /**
     * Cancel one queued or running job. Later queued jobs keep running.
     *
     * @param jobId Job to cancel.
     */
    @AppFunction(isDescribedByKDoc = true)
    suspend fun cancelJob(jobId: String): JobActionResult = io { agent.cancelJob(jobId) }

    /**
     * Cancel the running encode and every queued job.
     */
    @AppFunction(isDescribedByKDoc = true)
    suspend fun cancelQueue(): JobActionResult = io { agent.cancelQueue() }

    /**
     * List videos, audio, or pictures already on the device. Requires library
     * access from Settings (Allow all). Use a returned contentUri with
     * importDeviceMedia, importDeviceMediaBatch, or compressNow.
     *
     * @param kind VIDEO, AUDIO, IMAGE, or ANY. Null means ANY.
     * @param query Optional display-name substring, such as clip.mp4.
     * @param limit Maximum items to return, from 1 to 40.
     * @param relativePath Optional MediaStore folder filter such as Download or DCIM/Camera.
     * @param addedAfterEpochMs Only items added after this epoch millisecond time. 0 means no date filter.
     * @param minDurationMs Minimum duration in milliseconds. 0 means no minimum.
     * @param maxDurationMs Maximum duration in milliseconds. 0 means no maximum.
     */
    @AppFunction(isDescribedByKDoc = true)
    suspend fun listDeviceMedia(
        kind: String? = null,
        query: String? = null,
        limit: Int = 20,
        relativePath: String? = null,
        addedAfterEpochMs: Long = 0L,
        minDurationMs: Long = 0L,
        maxDurationMs: Long = 0L,
    ): DeviceMediaList = io {
        agent.listDeviceMedia(
            kind = kind,
            query = query,
            limit = limit,
            relativePath = relativePath,
            addedAfterEpochMs = addedAfterEpochMs,
            minDurationMs = minDurationMs,
            maxDurationMs = maxDurationMs,
        )
    }

    /**
     * Import a file that is already on the device. Accepts a MediaStore content
     * URI, an absolute path such as /sdcard/Download/clip.mp4, a file:// URI,
     * or an exact display name. Requires library access from Settings.
     *
     * @param uriOrPath Content URI, file path, or exact file name.
     */
    @AppFunction(isDescribedByKDoc = true)
    suspend fun importDeviceMedia(uriOrPath: String): JobActionResult =
        io { agent.importDeviceMedia(uriOrPath) }

    /**
     * Import one video or audio file. With library access this may be a
     * MediaStore content URI. A picker or Share URI still works without it.
     * Pictures must use importCombine.
     *
     * @param contentUri Content URI of a video or audio file.
     */
    @AppFunction(isDescribedByKDoc = true)
    suspend fun importFile(contentUri: Uri): JobActionResult = io { agent.importFile(contentUri) }

    /**
     * Create a combine job from a picture or video plus a soundtrack. Both URIs
     * may be MediaStore URIs when library access is granted.
     *
     * @param visualUri Picture or video content URI.
     * @param audioUri Soundtrack content URI.
     */
    @AppFunction(isDescribedByKDoc = true)
    suspend fun importCombine(
        visualUri: Uri,
        audioUri: Uri,
    ): JobActionResult = io { agent.importCombine(visualUri, audioUri) }

    /**
     * Read the app-wide defaults used when a new file is imported or recorded.
     */
    @AppFunction(isDescribedByKDoc = true)
    suspend fun getAppDefaults(): AppDefaults = io { agent.getAppDefaults() }

    /**
     * Change app-wide defaults for future imports and recordings. Null or empty
     * fields stay unchanged. This does not edit existing jobs.
     *
     * @param preset SMALLER, BALANCED, or HIGHER. Null keeps the current default.
     * @param engine FFMPEG or MEDIA3. Null keeps the current default.
     * @param autoCompressAfterRecord Whether a finished recording starts encoding automatically. Pass null to leave unchanged.
     * @param rememberAdvanced Whether the compress screen restores last advanced settings. Pass null to leave unchanged.
     * @param deleteOriginalAfterEncode Whether new jobs default to deleting the source after success. Pass null to leave unchanged. Agent startJob still defaults to false.
     */
    @AppFunction(isDescribedByKDoc = true)
    suspend fun setAppDefaults(
        preset: String? = null,
        engine: String? = null,
        autoCompressAfterRecord: Boolean? = null,
        rememberAdvanced: Boolean? = null,
        deleteOriginalAfterEncode: Boolean? = null,
    ): AppDefaults = io {
        agent.setAppDefaults(
            presetName = preset,
            engineName = engine,
            autoCompressAfterRecord = autoCompressAfterRecord,
            rememberAdvanced = rememberAdvanced,
            deleteOriginalAfterEncode = deleteOriginalAfterEncode,
        )
    }

    /**
     * Import one file, optionally patch settings, and start encoding. Prefer this
     * for a single "compress this" request. deleteSourceAfter defaults to false.
     *
     * @param uriOrPath Content URI, absolute path, or exact file name.
     * @param settings Optional settings patch applied before start. Pass an empty object to keep defaults.
     * @param wait If true, block until the job finishes or [timeoutSec] elapses.
     * @param timeoutSec How long to wait when wait is true, from 5 to 180 seconds. Defaults to 45.
     * @param deleteSourceAfter If true, delete the source after a successful encode.
     */
    @AppFunction(isDescribedByKDoc = true)
    suspend fun compressNow(
        uriOrPath: String,
        settings: JobSettingsUpdate? = null,
        wait: Boolean = false,
        timeoutSec: Int = 45,
        deleteSourceAfter: Boolean = false,
    ): WaitResult = io {
        agent.compressNow(uriOrPath, settings, wait, timeoutSec, deleteSourceAfter)
    }

    /**
     * Wait until one job reaches SUCCEEDED, FAILED, or CANCELLED, or until the
     * timeout. If timedOut is true, call this again. Does not start the job.
     *
     * @param jobId Job that is already queued or running.
     * @param timeoutSec How long to wait, from 5 to 180 seconds. Defaults to 45.
     */
    @AppFunction(isDescribedByKDoc = true)
    suspend fun waitForJob(
        jobId: String,
        timeoutSec: Int = 45,
    ): WaitResult = io { agent.waitForJob(jobId, timeoutSec) }

    /**
     * Wait until the encode queue is idle, or until the timeout. If timedOut is
     * true, call this again.
     *
     * @param timeoutSec How long to wait, from 5 to 180 seconds. Defaults to 45.
     */
    @AppFunction(isDescribedByKDoc = true)
    suspend fun waitForQueue(
        timeoutSec: Int = 45,
    ): WaitResult = io { agent.waitForQueue(timeoutSec) }

    /**
     * Import several files already on the device. Partial success is returned
     * instead of failing the whole batch. Requires library access.
     *
     * @param uriOrPaths Content URIs, absolute paths, or exact file names, up to 40.
     */
    @AppFunction(isDescribedByKDoc = true)
    suspend fun importDeviceMediaBatch(
        uriOrPaths: List<String>,
    ): BatchImportResult = io { agent.importDeviceMediaBatch(uriOrPaths) }

    /**
     * Create a combine job from a picture or video plus a soundtrack, using
     * paths or MediaStore URIs. Requires library access.
     *
     * @param visualUriOrPath Picture or video content URI, path, or exact file name.
     * @param audioUriOrPath Soundtrack content URI, path, or exact file name.
     */
    @AppFunction(isDescribedByKDoc = true)
    suspend fun importCombineDeviceMedia(
        visualUriOrPath: String,
        audioUriOrPath: String,
    ): JobActionResult = io { agent.importCombineDeviceMedia(visualUriOrPath, audioUriOrPath) }

    /**
     * Re-queue a FAILED, CANCELLED, or SUCCEEDED job with the same source.
     * Optionally patch settings first. Does not delete gallery files.
     *
     * @param jobId Job to retry.
     * @param settings Optional settings patch. Pass an empty object to keep current settings.
     */
    @AppFunction(isDescribedByKDoc = true)
    suspend fun retryJob(
        jobId: String,
        settings: JobSettingsUpdate? = null,
    ): JobActionResult = io { agent.retryJob(jobId, settings) }

    /**
     * Create a second READY job from the same source, optionally with different
     * settings. Use this for 720p plus 1080p or MP4 plus WebM.
     *
     * @param jobId Job whose source to copy.
     * @param settings Optional settings for the new job. Null copies the original settings.
     */
    @AppFunction(isDescribedByKDoc = true)
    suspend fun cloneJob(
        jobId: String,
        settings: JobSettingsUpdate? = null,
    ): JobDetail = io { agent.cloneJob(jobId, settings) }

    /**
     * Remove one job from history and delete its cache files. Gallery outputs
     * stay. Cannot discard a queued, running, or recording job.
     *
     * @param jobId Job to remove from history.
     */
    @AppFunction(isDescribedByKDoc = true)
    suspend fun discardJob(jobId: String): JobActionResult = io { agent.discardJob(jobId) }

    /**
     * Open the system share sheet for a finished compressed file. Does not
     * upload the file. The user must pick a destination.
     *
     * @param jobId Successful job whose output to share.
     */
    @AppFunction(isDescribedByKDoc = true)
    suspend fun shareOutput(jobId: String): JobActionResult = io { agent.shareOutput(jobId) }

    /**
     * Open a finished compressed file in a viewer. Does not upload the file.
     *
     * @param jobId Successful job whose output to open.
     */
    @AppFunction(isDescribedByKDoc = true)
    suspend fun openOutput(jobId: String): JobActionResult = io { agent.openOutput(jobId) }

    /**
     * Open Settings and prompt for Device library access if it is not granted.
     * The user must choose Allow all. Call describeCapabilities afterwards.
     */
    @AppFunction(isDescribedByKDoc = true)
    suspend fun requestLibraryAccess(): LibraryAccessResult = io { agent.requestLibraryAccess() }

    /**
     * Report which hardware and software encoders this device can use. Call
     * this before choosing HEVC, AV1, or WebM on an unknown device.
     */
    @AppFunction(isDescribedByKDoc = true)
    suspend fun getEncoderCapabilities(): DeviceEncodeCaps = io { agent.getEncoderCapabilities() }

    /**
     * Probe one job's source for duration, size, frame rate, and audio. Does
     * not return file URIs.
     *
     * @param jobId Job to inspect.
     */
    @AppFunction(isDescribedByKDoc = true)
    suspend fun getSourceInfo(jobId: String): SourceInfo = io { agent.getSourceInfo(jobId) }

    private suspend fun <T> io(block: suspend () -> T): T = withContext(Dispatchers.IO) {
        try {
            block()
        } catch (error: AppFunctionElementNotFoundException) {
            throw error
        } catch (error: AppFunctionInvalidArgumentException) {
            throw error
        } catch (error: NoSuchElementException) {
            throw AppFunctionElementNotFoundException(error.message ?: "Not found")
        } catch (error: IllegalArgumentException) {
            throw AppFunctionInvalidArgumentException(error.message ?: "Invalid argument")
        } catch (error: IllegalStateException) {
            throw AppFunctionInvalidArgumentException(error.message ?: "Invalid state")
        }
    }
}
