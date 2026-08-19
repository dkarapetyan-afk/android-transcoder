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
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import com.androidcompress.app.ui.label
import com.androidcompress.app.ui.components.ConfirmClearJobsDialog
import com.androidcompress.app.ui.components.EmptyState
import com.androidcompress.app.util.formatBytes
import com.androidcompress.app.util.formatDuration
import kotlinx.coroutines.launch

@Composable
fun LibraryScreen(
    viewModel: LibraryViewModel,
    onBack: () -> Unit,
    onOpen: (String, JobStatus) -> Unit,
) {
    val jobs by viewModel.jobs.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var confirmClear by remember { mutableStateOf(false) }
    Scaffold(
        topBar = {
            AppTopBar(
                title = stringResource(R.string.library_title),
                onBack = onBack,
                actions = {
                    if (jobs.any { it.status != JobStatus.RECORDING }) {
                        IconButton(onClick = { confirmClear = true }) {
                            Icon(
                                Icons.Default.DeleteSweep,
                                contentDescription = stringResource(R.string.cd_clear_all_jobs),
                            )
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        if (jobs.isEmpty()) {
            EmptyState(
                stringResource(R.string.library_empty_title),
                stringResource(R.string.library_empty_body),
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
                                    Text(
                                        stringResource(
                                            R.string.job_status_duration,
                                            job.status.label(),
                                            formatDuration(job.durationMs),
                                        ),
                                    )
                                    Text(
                                        stringResource(
                                            R.string.job_size_arrow,
                                            formatBytes(job.sourceBytes),
                                            job.outputBytes?.let(::formatBytes) ?: stringResource(R.string.em_dash),
                                        ),
                                    )
                                }
                            },
                            trailingContent = {
                                IconButton(onClick = { viewModel.delete(context, job.id) }) {
                                    Icon(
                                        Icons.Default.Delete,
                                        contentDescription = stringResource(R.string.cd_remove_from_history),
                                    )
                                }
                            },
                        )
                    }
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
