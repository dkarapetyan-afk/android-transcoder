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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.androidcompress.app.data.JobStatus
import com.androidcompress.app.data.label
import com.androidcompress.app.ui.components.AppTopBar
import com.androidcompress.app.util.formatDuration
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
    Scaffold(topBar = { AppTopBar("Compressing", onBack = onBack) }) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (ui.total > 1) {
                Text("Job ${ui.position} of ${ui.total}", style = MaterialTheme.typography.labelLarge)
            }
            Text(job?.displayName ?: "Video", style = MaterialTheme.typography.titleMedium)
            if (job?.status == JobStatus.QUEUED) {
                Text("Waiting in queue", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            LinearProgressIndicator(
                progress = { fraction },
                modifier = Modifier.fillMaxWidth(),
            )
            Text("${(fraction * 100).roundToInt()}%")
            if (progress != null && job != null && job.status == JobStatus.RUNNING) {
                Text("${formatDuration(progress.timeMs)} / ${formatDuration(ui.durationMs)}")
            }
            if (ui.active.size > 1) {
                Text("Queue", style = MaterialTheme.typography.titleSmall)
                ui.active.forEachIndexed { index, item ->
                    Text("${index + 1}. ${item.displayName} · ${item.status.label()}")
                }
            }
            Text(
                "You can leave this screen. Jobs keep running in a notification, one at a time.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(onClick = { viewModel.cancelCurrent(context) }, modifier = Modifier.fillMaxWidth()) {
                Text(if (job?.status == JobStatus.QUEUED) "Remove from queue" else "Cancel this job")
            }
            if (ui.total > 1) {
                OutlinedButton(onClick = { viewModel.cancelAll(context) }, modifier = Modifier.fillMaxWidth()) {
                    Text("Cancel all")
                }
            }
        }
    }
}
