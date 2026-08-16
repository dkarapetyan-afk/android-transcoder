package com.androidcompress.app.ui.library

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.androidcompress.app.data.JobStatus
import com.androidcompress.app.di.AppContainer
import com.androidcompress.app.encode.CompressService
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class LibraryViewModel(private val container: AppContainer) : ViewModel() {
    val jobs = container.jobs.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun delete(context: Context, id: String) {
        viewModelScope.launch {
            val job = container.jobs.get(id)
            if (job?.status == JobStatus.RUNNING || job?.status == JobStatus.QUEUED) {
                CompressService.cancelJob(context, id)
            }
            container.history.deleteJob(id)
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
