package com.androidcompress.app.ui.result

import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.androidcompress.app.R
import com.androidcompress.app.data.JobStatus
import com.androidcompress.app.data.OutputMode
import com.androidcompress.app.ui.components.AppTopBar
import com.androidcompress.app.ui.label
import com.androidcompress.app.ui.components.StatLine
import com.androidcompress.app.ui.components.VideoThumbnail
import com.androidcompress.app.util.formatBytes

@Composable
fun ResultScreen(
    viewModel: ResultViewModel,
    onBack: () -> Unit,
    onViewLog: () -> Unit,
) {
    val job by viewModel.job.collectAsStateWithLifecycle()
    val deleteMessage by viewModel.deleteMessage.collectAsStateWithLifecycle()
    val hasLog by viewModel.hasLog.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val current = job
    Scaffold(topBar = { AppTopBar(stringResource(R.string.result_title), onBack = onBack) }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (current == null) {
                Text(stringResource(R.string.result_job_not_found))
                return@Column
            }
            val audioOnly = viewModel.outputMode(current.settingsJson) == OutputMode.AUDIO
            if (!audioOnly) {
                VideoThumbnail(
                    uri = (current.outputUri ?: current.sourceUri).takeIf { it.isNotBlank() }?.let(Uri::parse),
                    modifier = Modifier.fillMaxWidth().height(180.dp),
                )
            }
            Text(current.displayName, style = MaterialTheme.typography.titleMedium)
            when (current.status) {
                JobStatus.SUCCEEDED -> {
                    StatLine(stringResource(R.string.result_original), formatBytes(current.sourceBytes))
                    StatLine(stringResource(R.string.result_compressed), formatBytes(current.outputBytes ?: 0))
                    val saved = current.sourceBytes - (current.outputBytes ?: current.sourceBytes)
                    if (saved > 0) StatLine(stringResource(R.string.result_saved), formatBytes(saved))
                    Button(onClick = { viewModel.open(context) }, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.result_open))
                    }
                    OutlinedButton(onClick = { viewModel.share(context) }, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.result_share))
                    }
                    if (current.sourceDeleted) {
                        Text(
                            stringResource(R.string.result_original_deleted),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else if (current.sourceUri.isNotBlank()) {
                        OutlinedButton(onClick = { viewModel.deleteOriginal() }, modifier = Modifier.fillMaxWidth()) {
                            Text(
                                stringResource(
                                    if (current.isCombine) {
                                        R.string.result_delete_originals
                                    } else {
                                        R.string.result_delete_original
                                    },
                                ),
                            )
                        }
                    }
                    deleteMessage?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    Text(
                        stringResource(
                            R.string.result_saved_to,
                            viewModel.galleryFolder(current.settingsJson),
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                JobStatus.FAILED -> Text(current.error ?: stringResource(R.string.result_failed))
                JobStatus.CANCELLED -> Text(stringResource(R.string.result_cancelled))
                else -> Text(stringResource(R.string.result_status, current.status.label()))
            }
            if (hasLog) {
                OutlinedButton(onClick = onViewLog, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.result_view_log))
                }
            }
        }
    }
}
