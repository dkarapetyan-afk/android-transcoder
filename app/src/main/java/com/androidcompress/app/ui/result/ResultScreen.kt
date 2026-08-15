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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.androidcompress.app.data.JobStatus
import com.androidcompress.app.ui.components.AppTopBar
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
    Scaffold(topBar = { AppTopBar("Result", onBack = onBack) }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (current == null) {
                Text("Job not found")
                return@Column
            }
            VideoThumbnail(
                uri = (current.outputUri ?: current.sourceUri).takeIf { it.isNotBlank() }?.let(Uri::parse),
                modifier = Modifier.fillMaxWidth().height(180.dp),
            )
            Text(current.displayName, style = MaterialTheme.typography.titleMedium)
            when (current.status) {
                JobStatus.SUCCEEDED -> {
                    StatLine("Original", formatBytes(current.sourceBytes))
                    StatLine("Compressed", formatBytes(current.outputBytes ?: 0))
                    val saved = current.sourceBytes - (current.outputBytes ?: current.sourceBytes)
                    if (saved > 0) StatLine("Saved", formatBytes(saved))
                    Button(onClick = { viewModel.open(context) }, modifier = Modifier.fillMaxWidth()) {
                        Text("Open")
                    }
                    OutlinedButton(onClick = { viewModel.share(context) }, modifier = Modifier.fillMaxWidth()) {
                        Text("Share")
                    }
                    if (current.sourceDeleted) {
                        Text("Original file deleted.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else if (current.sourceUri.isNotBlank()) {
                        OutlinedButton(onClick = { viewModel.deleteOriginal() }, modifier = Modifier.fillMaxWidth()) {
                            Text("Delete original")
                        }
                    }
                    deleteMessage?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    Text(
                        "Saved to Movies/RecordingCompressor in your gallery.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                JobStatus.FAILED -> Text(current.error ?: "Compression failed")
                JobStatus.CANCELLED -> Text("Compression cancelled.")
                else -> Text("Status: ${current.status}")
            }
            if (hasLog) {
                OutlinedButton(onClick = onViewLog, modifier = Modifier.fillMaxWidth()) {
                    Text("View encode log")
                }
            }
        }
    }
}
