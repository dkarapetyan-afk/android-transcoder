package com.androidcompress.app.agent

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import com.androidcompress.app.capture.ScreenRecordService
import com.androidcompress.app.container
import com.androidcompress.app.data.CompressJob
import com.androidcompress.app.data.JobStatus
import com.androidcompress.app.util.AppLog
import com.androidcompress.app.util.runCatchingLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class AutomationCoordinator(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun dispatch(intent: Intent, onDone: () -> Unit) {
        scope.launch {
            try {
                handle(intent)
            } catch (t: Throwable) {
                AppLog.e(TAG, "automation dispatch", t)
                send(
                    AutomationIntents.completionFailed(
                        intent.action.orEmpty(),
                        IntentExtras(intent).let(AutomationIntents::requestId),
                        t.message ?: "Automation failed.",
                    ),
                    IntentExtras(intent).let(AutomationIntents::replyPackage),
                )
            } finally {
                onDone()
            }
        }
    }

    private suspend fun handle(intent: Intent) {
        val extras = IntentExtras(intent)
        val action = intent.action.orEmpty()
        val requestId = AutomationIntents.requestId(extras)
        val replyPackage = AutomationIntents.replyPackage(extras)
        when (action) {
            AutomationIntents.ACTION_COMPRESS -> compress(intent, extras, requestId, replyPackage)
            AutomationIntents.ACTION_RECORD_STOP -> recordStop(requestId, replyPackage)
            AutomationIntents.ACTION_CANCEL_QUEUE -> cancelQueue(requestId, replyPackage)
            else -> send(
                AutomationIntents.completionFailed(action, requestId, "Unknown automation action."),
                replyPackage,
            )
        }
    }

    private suspend fun compress(
        intent: Intent,
        extras: ExtraLookup,
        requestId: String,
        replyPackage: String,
    ) {
        val uriOrPath = AutomationIntents.uriOrPath(intent.dataString, streamUri(intent), extras)
            ?: error("COMPRESS needs a uri, path, file extra, or Intent data.")
        val settings = AutomationIntents.settingsUpdate(extras)
        val patch = settings.takeUnless { it == JobSettingsUpdate() }
        val agent = JobAgent(context)
        val started = agent.compressNow(
            uriOrPath = uriOrPath,
            update = patch,
            wait = false,
            timeoutSec = AgentWait.DEFAULT_TIMEOUT_SEC,
            deleteSourceAfter = AutomationIntents.deleteSourceAfter(extras),
        )
        val job = started.jobs.firstOrNull()?.summary
            ?: error(started.message.ifBlank { "Import did not create a job." })
        watch(
            jobId = job.jobId,
            action = AutomationIntents.ACTION_COMPRESS,
            requestId = requestId,
            replyPackage = replyPackage,
            autoCompressAfterRecord = false,
        )
    }

    private suspend fun recordStop(requestId: String, replyPackage: String) {
        val app = context.container()
        val liveId = app.recording.state.value.jobId
        val jobId = liveId
            ?: app.jobs.listAll().firstOrNull { it.status == JobStatus.RECORDING }?.id
        if (jobId.isNullOrBlank()) {
            send(
                AutomationIntents.completionFailed(
                    AutomationIntents.ACTION_RECORD_STOP,
                    requestId,
                    "No recording in progress.",
                ),
                replyPackage,
            )
            return
        }
        val autoCompress = app.prefs.current().autoCompressAfterRecord
        watch(jobId, AutomationIntents.ACTION_RECORD_STOP, requestId, replyPackage, autoCompress)
        ScreenRecordService.stop(context)
    }

    private suspend fun cancelQueue(requestId: String, replyPackage: String) {
        val result = JobAgent(context).cancelQueue()
        send(
            AutomationIntents.completionCancelled(requestId, result.message, result.jobs.size),
            replyPackage,
        )
    }

    private fun watch(
        jobId: String,
        action: String,
        requestId: String,
        replyPackage: String,
        autoCompressAfterRecord: Boolean,
    ) {
        scope.launch {
            try {
                val job = context.container().jobs.observe(jobId)
                    .filterNotNull()
                    .first { AutomationIntents.isWatchFinished(it, action, autoCompressAfterRecord) }
                send(completionMessage(action, requestId, job), replyPackage)
            } catch (t: Throwable) {
                AppLog.e(TAG, "automation watch", t)
                send(
                    AutomationIntents.completionFailed(
                        action,
                        requestId,
                        t.message ?: "Automation watch failed.",
                        jobId,
                    ),
                    replyPackage,
                )
            }
        }
    }

    private fun completionMessage(
        action: String,
        requestId: String,
        job: CompressJob,
    ): AutomationCompletion {
        val message = when (job.status) {
            JobStatus.SUCCEEDED -> "Job ${job.displayName} succeeded."
            JobStatus.FAILED -> "Job ${job.displayName} failed. ${job.error.orEmpty()}".trim()
            JobStatus.CANCELLED -> "Job ${job.displayName} was cancelled."
            JobStatus.READY -> "Recording ${job.displayName} is ready."
            else -> "Job ${job.displayName} is ${job.status.name}."
        }
        return AutomationIntents.completionForJob(action, requestId, job, message)
    }

    private fun send(completion: AutomationCompletion, replyPackage: String) {
        val intent = Intent(AutomationIntents.ACTION_COMPLETED)
            .putExtra(AutomationIntents.EXTRA_ACTION, completion.action)
            .putExtra(AutomationIntents.EXTRA_REQUEST_ID, completion.requestId)
            .putExtra(AutomationIntents.EXTRA_JOB_ID, completion.jobId)
            .putExtra(AutomationIntents.EXTRA_STATUS, completion.status)
            .putExtra(AutomationIntents.EXTRA_DISPLAY_NAME, completion.displayName)
            .putExtra(AutomationIntents.EXTRA_MESSAGE, completion.message)
            .putExtra(AutomationIntents.EXTRA_ERROR, completion.error)
            .putExtra(AutomationIntents.EXTRA_OUTPUT_URI, completion.outputUri)
            .putExtra(AutomationIntents.EXTRA_OUTPUT_BYTES, completion.outputBytes)
            .putExtra(AutomationIntents.EXTRA_DURATION_MS, completion.durationMs)
            .putExtra(AutomationIntents.EXTRA_TYPE, completion.type)
            .putExtra(AutomationIntents.EXTRA_COUNT, completion.count)
            .addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
        if (replyPackage.isNotBlank()) {
            intent.setPackage(replyPackage)
            if (completion.outputUri.isNotBlank()) {
                runCatchingLog(TAG, "grant output uri") {
                    val uri = Uri.parse(completion.outputUri)
                    if (uri.scheme == "content") {
                        context.grantUriPermission(
                            replyPackage,
                            uri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION,
                        )
                        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                }
            }
        }
        context.sendBroadcast(intent)
    }

    private fun streamUri(intent: Intent): String? {
        val stream = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri
        }
        return stream?.toString()?.takeIf { it.isNotBlank() }
            ?: intent.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri?.toString()
    }

    private companion object {
        const val TAG = "Automation"
    }
}

internal class IntentExtras(private val intent: Intent) : ExtraLookup {
    override fun has(key: String): Boolean = intent.hasExtra(key)

    override fun string(key: String): String? {
        if (!has(key)) return null
        return MapExtras.stringify(raw(key))
    }

    override fun int(key: String): Int? = MapExtras.number(raw(key))?.toInt()

    override fun long(key: String): Long? = MapExtras.number(raw(key))

    override fun boolean(key: String): Boolean? = MapExtras.flag(raw(key))

    private fun raw(key: String): Any? {
        if (!has(key)) return null
        intent.getStringExtra(key)?.let { return it }
        val extras = intent.extras ?: return null
        @Suppress("DEPRECATION")
        return extras.get(key)
    }
}
