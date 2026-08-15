package com.androidcompress.app.ui.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.androidcompress.app.data.JobStatus
import com.androidcompress.app.data.label
import com.androidcompress.app.ui.components.AppTopBar
import com.androidcompress.app.ui.components.EmptyState
import com.androidcompress.app.util.formatBytes
import com.androidcompress.app.util.formatDuration

@Composable
fun LibraryScreen(
    viewModel: LibraryViewModel,
    onBack: () -> Unit,
    onOpen: (String, JobStatus) -> Unit,
) {
    val jobs by viewModel.jobs.collectAsStateWithLifecycle()
    val context = LocalContext.current
    Scaffold(topBar = { AppTopBar("Library", onBack = onBack) }) { padding ->
        if (jobs.isEmpty()) {
            EmptyState(
                "No jobs yet",
                "Recorded and imported videos and audio will show up here.",
                modifier = Modifier.padding(padding),
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(jobs, key = { it.id }) { job ->
                    Card(Modifier.fillMaxWidth().clickable { onOpen(job.id, job.status) }) {
                        ListItem(
                            headlineContent = { Text(job.displayName) },
                            supportingContent = {
                                Column {
                                    Text("${job.status.label()} · ${formatDuration(job.durationMs)}")
                                    Text("${formatBytes(job.sourceBytes)} → ${job.outputBytes?.let(::formatBytes) ?: "—"}")
                                }
                            },
                            trailingContent = {
                                IconButton(onClick = { viewModel.delete(context, job.id) }) {
                                    Icon(Icons.Default.Delete, contentDescription = "Remove from history")
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}
