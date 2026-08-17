package com.androidcompress.app.ui.home

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.androidcompress.app.data.CompressJob
import com.androidcompress.app.data.JobStatus
import com.androidcompress.app.di.AppContainer
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
                .onFailure { onError(it.message ?: "Unable to read that file") }
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

    fun clearHistory(context: Context, onDone: (String) -> Unit) {
        viewModelScope.launch {
            val active = container.jobs.listActive()
            if (active.any { it.status == JobStatus.RUNNING || it.status == JobStatus.QUEUED }) {
                CompressService.cancelAll(context)
            }
            onDone(container.history.clearHistory().message())
        }
    }
}
