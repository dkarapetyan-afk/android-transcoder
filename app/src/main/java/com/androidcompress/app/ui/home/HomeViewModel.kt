package com.androidcompress.app.ui.home

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.androidcompress.app.R
import com.androidcompress.app.data.CompressJob
import com.androidcompress.app.data.JobStatus
import com.androidcompress.app.di.AppContainer
import com.androidcompress.app.encode.BatchQueueSettings
import com.androidcompress.app.encode.BatchRecipe
import com.androidcompress.app.encode.CompressService
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class HomeViewModel(private val container: AppContainer) : ViewModel() {
    val jobs: StateFlow<List<CompressJob>> = container.jobs.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val recording = container.recording.state
    val hasLastLog: StateFlow<Boolean> = container.jobs.observeAll()
        .map { !container.jobLogs.lastJobId().isNullOrBlank() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), !container.jobLogs.lastJobId().isNullOrBlank())

    fun createImportJob(
        uri: Uri,
        onReady: (String) -> Unit,
        onError: (String) -> Unit,
    ) {
        viewModelScope.launch {
            runCatching { container.importer.import(uri) }
                .onSuccess(onReady)
                .onFailure { onError(it.message ?: container.appContext.getString(R.string.error_read_file)) }
        }
    }

    fun createCombineJobs(
        uris: List<Uri>,
        mimeOf: (Uri) -> String?,
        onReady: (String) -> Unit,
        onMessage: (String) -> Unit,
    ) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            val ctx = container.appContext
            val batch = runCatching { container.importer.importCombinePicks(uris, mimeOf) }
                .getOrElse {
                    onMessage(it.message ?: ctx.getString(R.string.error_combine))
                    return@launch
                }
            when {
                batch.jobIds.isNotEmpty() -> {
                    onReady(batch.jobIds.first())
                    if (batch.errors.isNotEmpty()) {
                        onMessage(
                            ctx.getString(
                                R.string.share_opened_partial,
                                batch.jobIds.size,
                                batch.errors.size,
                            ),
                        )
                    } else if (batch.jobIds.size > 1) {
                        onMessage(ctx.getString(R.string.share_opened_rest, batch.jobIds.size))
                    }
                }
                else -> onMessage(batch.errors.firstOrNull() ?: ctx.getString(R.string.error_combine))
            }
        }
    }

    fun consumeRecordingEvent() {
        container.recording.consumeFinished()
    }

    fun openIfReadyToCompress(jobId: String, onReady: (String) -> Unit) {
        viewModelScope.launch {
            val job = container.jobs.get(jobId) ?: return@launch
            if (job.status == JobStatus.READY || job.status == JobStatus.DRAFT) {
                onReady(jobId)
            }
        }
    }

    fun waitingCount(jobs: List<CompressJob>): Int =
        BatchQueueSettings.targets(jobs, queuedOnly = false).size

    fun applyToWaiting(recipe: BatchRecipe, onDone: (Int) -> Unit) {
        viewModelScope.launch {
            onDone(container.applyBatchRecipe(recipe, queuedOnly = false))
        }
    }

    fun clearHistory(context: Context, onDone: (String) -> Unit) {
        viewModelScope.launch {
            val active = container.jobs.listActive()
            if (active.any { it.status == JobStatus.RUNNING || it.status == JobStatus.QUEUED }) {
                CompressService.cancelAll(context)
            }
            onDone(container.history.clearHistory().message(container.appContext))
        }
    }
}
