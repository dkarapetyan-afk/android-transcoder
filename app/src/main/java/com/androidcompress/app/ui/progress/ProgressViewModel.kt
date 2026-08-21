package com.androidcompress.app.ui.progress

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.androidcompress.app.data.CompressJob
import com.androidcompress.app.data.EncodeProgress
import com.androidcompress.app.data.SettingsJson
import com.androidcompress.app.di.AppContainer
import com.androidcompress.app.encode.BatchQueueSettings
import com.androidcompress.app.encode.BatchRecipe
import com.androidcompress.app.encode.CompressService
import com.androidcompress.app.encode.EncodeQueue
import com.androidcompress.app.encode.Media3EncodePlanner
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ProgressUi(
    val job: CompressJob? = null,
    val progress: EncodeProgress? = null,
    val active: List<CompressJob> = emptyList(),
    val position: Int = 0,
    val total: Int = 0,
    val durationMs: Long = 0,
    val queuedCount: Int = 0,
)

class ProgressViewModel(
    private val container: AppContainer,
    val jobId: String,
) : ViewModel() {
    val ui = combine(
        container.jobs.observe(jobId),
        container.jobs.observeActive(),
        container.encodeProgress.progress,
    ) { job, active, progress ->
        val ordered = EncodeQueue.active(active)
        val (position, total) = EncodeQueue.position(ordered, jobId)
        ProgressUi(
            job = job,
            progress = progress?.takeIf { it.jobId == jobId || job?.status == com.androidcompress.app.data.JobStatus.RUNNING },
            active = ordered,
            position = position,
            total = total,
            durationMs = job?.let {
                Media3EncodePlanner.outputDurationMs(
                    SettingsJson.decode(it.settingsJson),
                    it.durationMs,
                    it.width > 0 && it.height > 0,
                )
            } ?: 0L,
            queuedCount = BatchQueueSettings.targets(ordered, queuedOnly = true).size,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ProgressUi())

    fun cancelCurrent(context: Context) {
        CompressService.cancelJob(context, jobId)
    }

    fun cancelAll(context: Context) {
        CompressService.cancelAll(context)
    }

    fun applyToQueued(recipe: BatchRecipe, onDone: (Int) -> Unit) {
        viewModelScope.launch {
            onDone(container.applyBatchRecipe(recipe, queuedOnly = true))
        }
    }
}
