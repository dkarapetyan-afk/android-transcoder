package com.androidcompress.app.ui.result

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.androidcompress.app.di.AppContainer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ResultViewModel(
    private val container: AppContainer,
    val jobId: String,
) : ViewModel() {
    val job = container.jobs.observe(jobId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _deleteMessage = MutableStateFlow<String?>(null)
    val deleteMessage = _deleteMessage.asStateFlow()
    val hasLog = container.jobs.observe(jobId)
        .map { container.jobLogs.read(jobId) != null }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), container.jobLogs.read(jobId) != null)

    fun deleteOriginal() {
        viewModelScope.launch {
            val current = container.jobs.get(jobId) ?: return@launch
            if (current.sourceDeleted) return@launch
            val result = container.sourceDeleter.delete(current.sourceUri, current.outputUri)
            container.jobs.markSourceDeleted(jobId, result.deleted)
            _deleteMessage.value = if (result.deleted) {
                "Original file deleted."
            } else {
                result.error ?: "Could not delete the original file."
            }
        }
    }

    fun share(context: Context) {
        val uri = job.value?.outputUri?.let(Uri::parse) ?: return
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "video/mp4"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Share video"))
    }

    fun open(context: Context) {
        val uri = job.value?.outputUri?.let(Uri::parse) ?: return
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "video/mp4")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(intent)
    }
}
