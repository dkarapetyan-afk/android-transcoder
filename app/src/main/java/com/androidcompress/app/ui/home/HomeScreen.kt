package com.androidcompress.app.ui.home

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MusicVideo
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.androidcompress.app.R
import com.androidcompress.app.data.CompressJob
import com.androidcompress.app.data.JobStatus
import com.androidcompress.app.ui.components.AppTopBar
import com.androidcompress.app.ui.label
import com.androidcompress.app.ui.components.ConfirmClearJobsDialog
import com.androidcompress.app.ui.components.EmptyState
import com.androidcompress.app.util.formatBytes
import com.androidcompress.app.util.formatDuration
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    onRecord: () -> Unit,
    onCompress: (String) -> Unit,
    onProgress: (String) -> Unit,
    onResult: (String) -> Unit,
    onLibrary: () -> Unit,
    onLastLog: () -> Unit,
    onSettings: () -> Unit,
    onAbout: () -> Unit,
) {
    val jobs by viewModel.jobs.collectAsStateWithLifecycle()
    val recording by viewModel.recording.collectAsStateWithLifecycle()
    val hasLastLog by viewModel.hasLastLog.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var confirmClear by remember { mutableStateOf(false) }

    var pendingVisual by remember { mutableStateOf<Uri?>(null) }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.createImportJob(uri, onReady = onCompress) { msg ->
            scope.launch { snackbar.showSnackbar(msg) }
        }
    }
    val combineAudioPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { audio ->
        val visual = pendingVisual
        pendingVisual = null
        if (audio != null && visual != null) {
            viewModel.createCombineJob(visual, audio, onReady = onCompress) { msg ->
                scope.launch { snackbar.showSnackbar(msg) }
            }
        }
    }
    val combineVisualPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { visual ->
        if (visual != null) {
            pendingVisual = visual
            combineAudioPicker.launch(arrayOf("audio/*", "video/*"))
        }
    }

    LaunchedEffect(recording.finishedJobId, recording.error, recording.notice) {
        recording.finishedJobId?.let { id ->
            val openResult = recording.openResult
            val notice = recording.notice
            viewModel.consumeRecordingEvent()
            if (!notice.isNullOrBlank()) scope.launch { snackbar.showSnackbar(notice) }
            if (openResult) onResult(id) else viewModel.openIfReadyToCompress(id, onCompress)
        }
        recording.error?.let {
            viewModel.consumeRecordingEvent()
            snackbar.showSnackbar(it)
        }
    }

    Scaffold(
        topBar = {
            AppTopBar(
                title = stringResource(R.string.app_name),
                actions = {
                    IconButton(onClick = onLibrary) {
                        Icon(Icons.Default.Movie, contentDescription = stringResource(R.string.cd_library))
                    }
                    IconButton(onClick = onSettings) {
                        Icon(Icons.Default.Settings, contentDescription = stringResource(R.string.cd_settings))
                    }
                    IconButton(onClick = onAbout) {
                        Icon(Icons.Default.Info, contentDescription = stringResource(R.string.cd_about))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                ActionCard(
                    title = stringResource(R.string.home_record_title),
                    body = when {
                            recording.saving -> stringResource(R.string.home_record_body_saving)
                            recording.phase == com.androidcompress.app.capture.RecordPhase.REGION ->
                                stringResource(R.string.home_record_body_region)
                            recording.phase == com.androidcompress.app.capture.RecordPhase.COUNTDOWN ->
                                stringResource(R.string.home_record_body_countdown, recording.countdownRemaining)
                            recording.active && recording.paused -> stringResource(R.string.home_record_body_paused)
                            recording.active -> stringResource(R.string.home_record_body_active)
                            else -> stringResource(R.string.home_record_body)
                        },
                    icon = Icons.Default.Videocam,
                    onClick = onRecord,
                )
            }
            item {
                ActionCard(
                    title = stringResource(R.string.home_compress_title),
                    body = stringResource(R.string.home_compress_body),
                    icon = Icons.Default.FolderOpen,
                    onClick = { filePicker.launch(arrayOf("video/*", "audio/*")) },
                )
            }
            item {
                ActionCard(
                    title = stringResource(R.string.home_combine_title),
                    body = stringResource(R.string.home_combine_body),
                    icon = Icons.Default.MusicVideo,
                    onClick = { combineVisualPicker.launch(arrayOf("image/*", "video/*")) },
                )
            }
            item {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(R.string.home_recent),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                    if (jobs.any { it.status != JobStatus.RECORDING }) {
                        TextButton(onClick = { confirmClear = true }) {
                            Text(stringResource(R.string.home_clear_all))
                        }
                    }
                }
                if (hasLastLog) {
                    TextButton(onClick = onLastLog) {
                        Text(stringResource(R.string.home_view_last_log))
                    }
                }
            }
            if (jobs.isEmpty()) {
                item {
                    EmptyState(
                        stringResource(R.string.home_empty_title),
                        stringResource(R.string.home_empty_body),
                    )
                }
            } else {
                items(jobs.take(8), key = { it.id }) { job ->
                    JobRow(job, onClick = {
                        when (job.status) {
                            JobStatus.RUNNING, JobStatus.QUEUED -> onProgress(job.id)
                            JobStatus.SUCCEEDED -> onResult(job.id)
                            JobStatus.READY, JobStatus.RECORDING, JobStatus.DRAFT -> onCompress(job.id)
                            else -> onResult(job.id)
                        }
                    })
                }
            }
        }
    }
    if (confirmClear) {
        ConfirmClearJobsDialog(
            onConfirm = {
                confirmClear = false
                viewModel.clearHistory(context) { msg ->
                    scope.launch { snackbar.showSnackbar(msg) }
                }
            },
            onDismiss = { confirmClear = false },
        )
    }
}

@Composable
private fun ActionCard(
    title: String,
    body: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(36.dp))
            Column(Modifier.padding(start = 16.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                Text(body, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun JobRow(job: CompressJob, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(Modifier.padding(16.dp)) {
            Text(job.displayName, style = MaterialTheme.typography.titleSmall)
            Text(
                job.outputBytes?.let { compressed ->
                    stringResource(
                        R.string.job_meta_compressed,
                        job.status.label(),
                        formatDuration(job.durationMs),
                        formatBytes(job.sourceBytes),
                        formatBytes(compressed),
                    )
                } ?: stringResource(
                    R.string.job_meta,
                    job.status.label(),
                    formatDuration(job.durationMs),
                    formatBytes(job.sourceBytes),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
