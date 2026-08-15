package com.androidcompress.app.ui.log

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.androidcompress.app.di.AppContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class JobLogUi(
    val jobId: String? = null,
    val title: String = "Encode log",
    val text: String = "",
    val empty: Boolean = true,
)

class JobLogViewModel(
    private val container: AppContainer,
    requestedId: String,
) : ViewModel() {
    private val _ui = MutableStateFlow(JobLogUi())
    val ui = _ui.asStateFlow()

    init {
        viewModelScope.launch {
            val id = withContext(Dispatchers.IO) {
                if (requestedId.isBlank() || requestedId == LAST) {
                    container.jobLogs.lastJobId()
                } else {
                    requestedId
                }
            }
            val job = id?.let { container.jobs.get(it) }
            val text = withContext(Dispatchers.IO) {
                id?.let { container.jobLogs.read(it) }
            }
            _ui.value = JobLogUi(
                jobId = id,
                title = job?.displayName?.let { "Log · $it" } ?: "Encode log",
                text = text.orEmpty(),
                empty = text.isNullOrBlank(),
            )
        }
    }

    fun copy(context: Context): Boolean {
        val text = _ui.value.text
        if (text.isBlank()) return false
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("encode log", text))
        return true
    }

    companion object {
        const val LAST = "last"
    }
}
