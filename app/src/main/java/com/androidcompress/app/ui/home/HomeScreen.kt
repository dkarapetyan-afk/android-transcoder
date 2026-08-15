package com.androidcompress.app.ui.home

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.androidcompress.app.data.CompressJob
import com.androidcompress.app.data.JobStatus
import com.androidcompress.app.data.label
import com.androidcompress.app.ui.components.AppTopBar
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

    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) viewModel.createImportJob(uri, onCompress) { msg ->
            scope.launch { snackbar.showSnackbar(msg) }
        }
    }
    val safPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.createImportJob(uri, onCompress) { msg ->
            scope.launch { snackbar.showSnackbar(msg) }
        }
    }

    LaunchedEffect(recording.finishedJobId, recording.error) {
        recording.finishedJobId?.let {
            viewModel.consumeRecordingEvent()
            onCompress(it)
        }
        recording.error?.let {
            viewModel.consumeRecordingEvent()
            snackbar.showSnackbar(it)
        }
    }

    Scaffold(
        topBar = {
            AppTopBar(
                title = "Recording Compressor",
                actions = {
                    IconButton(onClick = onLibrary) { Icon(Icons.Default.Movie, contentDescription = "Library") }
                    IconButton(onClick = onSettings) { Icon(Icons.Default.Settings, contentDescription = "Settings") }
                    IconButton(onClick = onAbout) { Icon(Icons.Default.Info, contentDescription = "About") }
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
                    title = "Record screen",
                    body = if (recording.active) "Recording in progress…" else "Capture the display, then compress the file.",
                    icon = Icons.Default.Videocam,
                    onClick = onRecord,
                )
            }
            item {
                ActionCard(
                    title = "Compress a video",
                    body = "Pick a screen recording from your gallery or files.",
                    icon = Icons.Default.FolderOpen,
                    onClick = {
                        photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly))
                    },
                )
                TextButton(onClick = { safPicker.launch(arrayOf("video/*")) }) {
                    Text("Browse files")
                }
            }
            item {
                Text("Recent", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
                if (hasLastLog) {
                    TextButton(onClick = onLastLog) {
                        Text("View last encode log")
                    }
                }
            }
            if (jobs.isEmpty()) {
                item { EmptyState("Nothing yet", "Record or import a video to get started.") }
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
                buildString {
                    append(job.status.label())
                    append(" · ")
                    append(formatDuration(job.durationMs))
                    append(" · ")
                    append(formatBytes(job.sourceBytes))
                    job.outputBytes?.let { compressed ->
                        append(" → ")
                        append(formatBytes(compressed))
                    }
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
