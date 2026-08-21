package com.androidcompress.app.ui.progress

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.androidcompress.app.R
import com.androidcompress.app.data.JobStatus
import com.androidcompress.app.ui.components.AppTopBar
import com.androidcompress.app.ui.components.BatchRecipeChips
import com.androidcompress.app.ui.label
import com.androidcompress.app.util.formatDuration
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Composable
fun ProgressScreen(
    viewModel: ProgressViewModel,
    onBack: () -> Unit,
    onFinished: (String) -> Unit,
    onSwitch: (String) -> Unit,
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val job = ui.job
    val progress = ui.progress
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var handedOff by remember { mutableStateOf(false) }

    LaunchedEffect(job?.status, ui.active.map { it.id + it.status }) {
        if (handedOff) return@LaunchedEffect
        val status = job?.status
        val terminal = status == JobStatus.SUCCEEDED || status == JobStatus.FAILED || status == JobStatus.CANCELLED
        if (!terminal) return@LaunchedEffect
        val next = ui.active.firstOrNull { it.status == JobStatus.RUNNING || it.status == JobStatus.QUEUED }
        if (next != null && next.id != viewModel.jobId) {
            handedOff = true
            onSwitch(next.id)
        } else if (next == null) {
            handedOff = true
            onFinished(viewModel.jobId)
        }
    }

    val fraction = if (job?.status == JobStatus.RUNNING && progress != null && progress.jobId == job.id) {
        progress.fraction
    } else {
        0f
    }
    Scaffold(
        topBar = { AppTopBar(stringResource(R.string.progress_title), onBack = onBack) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (ui.total > 1) {
                Text(
                    stringResource(R.string.progress_job_of, ui.position, ui.total),
                    style = MaterialTheme.typography.labelLarge,
                )
            }
            Text(job?.displayName ?: stringResource(R.string.untitled_video), style = MaterialTheme.typography.titleMedium)
            if (job?.status == JobStatus.QUEUED) {
                Text(stringResource(R.string.progress_waiting), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            LinearProgressIndicator(
                progress = { fraction },
                modifier = Modifier.fillMaxWidth(),
            )
            Text(stringResource(R.string.progress_percent, (fraction * 100).roundToInt()))
            val passMessage = progress?.message?.takeIf { it.isNotBlank() }
            if (passMessage != null && job?.status == JobStatus.RUNNING) {
                Text(passMessage, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (progress != null && job != null && job.status == JobStatus.RUNNING) {
                Text(
                    stringResource(
                        R.string.progress_time,
                        formatDuration(progress.timeMs),
                        formatDuration(ui.durationMs),
                    ),
                )
            }
            if (ui.active.size > 1) {
                Text(stringResource(R.string.progress_queue), style = MaterialTheme.typography.titleSmall)
                ui.active.forEachIndexed { index, item ->
                    Text(
                        stringResource(
                            R.string.progress_queue_item,
                            index + 1,
                            item.displayName,
                            item.status.label(),
                        ),
                    )
                }
            }
            if (ui.queuedCount > 0) {
                BatchRecipeChips(waitingCount = ui.queuedCount) { recipe ->
                    viewModel.applyToQueued(recipe) { count ->
                        scope.launch {
                            snackbar.showSnackbar(
                                if (count > 0) {
                                    context.getString(R.string.batch_applied, count)
                                } else {
                                    context.getString(R.string.batch_none)
                                },
                            )
                        }
                    }
                }
            }
            Text(
                stringResource(R.string.progress_leave_hint),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(onClick = { viewModel.cancelCurrent(context) }, modifier = Modifier.fillMaxWidth()) {
                Text(
                    stringResource(
                        if (job?.status == JobStatus.QUEUED) {
                            R.string.progress_remove_from_queue
                        } else {
                            R.string.progress_cancel_job
                        },
                    ),
                )
            }
            if (ui.total > 1) {
                OutlinedButton(onClick = { viewModel.cancelAll(context) }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.progress_cancel_all))
                }
            }
        }
    }
}
