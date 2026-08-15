package com.androidcompress.app.ui.record

import android.Manifest
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.androidcompress.app.data.RecordAudioMode
import com.androidcompress.app.data.RecordResolution
import com.androidcompress.app.ui.components.AppTopBar
import com.androidcompress.app.util.formatDuration

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordScreen(
    viewModel: RecordViewModel,
    onBack: () -> Unit,
    onFinished: (String) -> Unit,
) {
    val context = LocalContext.current
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val recording by viewModel.recording.collectAsStateWithLifecycle()

    val captureLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        viewModel.onConsentResult(context, result.resultCode, result.data)
    }
    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) launchCapture(context, captureLauncher)
        else viewModel.markStarting()
    }

    LaunchedEffect(recording.finishedJobId) {
        recording.finishedJobId?.let(onFinished)
    }

    Scaffold(topBar = { AppTopBar("Record screen", onBack = onBack) }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Audio", style = MaterialTheme.typography.titleMedium)
            androidx.compose.foundation.layout.Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = ui.audioMode == RecordAudioMode.NONE,
                    onClick = { viewModel.setAudioMode(RecordAudioMode.NONE) },
                    label = { Text("None") },
                )
                FilterChip(
                    selected = ui.audioMode == RecordAudioMode.MICROPHONE,
                    onClick = { viewModel.setAudioMode(RecordAudioMode.MICROPHONE) },
                    label = { Text("Microphone") },
                )
                if (viewModel.supportsInternalAudio()) {
                    FilterChip(
                        selected = ui.audioMode == RecordAudioMode.INTERNAL,
                        onClick = { viewModel.setAudioMode(RecordAudioMode.INTERNAL) },
                        label = { Text("Internal") },
                    )
                }
            }
            Text("Resolution", style = MaterialTheme.typography.titleMedium)
            androidx.compose.foundation.layout.Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = ui.resolution == RecordResolution.P720,
                    onClick = { viewModel.setResolution(RecordResolution.P720) },
                    label = { Text("720p") },
                )
                FilterChip(
                    selected = ui.resolution == RecordResolution.P1080,
                    onClick = { viewModel.setResolution(RecordResolution.P1080) },
                    label = { Text("1080p") },
                )
                FilterChip(
                    selected = ui.resolution == RecordResolution.DISPLAY,
                    onClick = { viewModel.setResolution(RecordResolution.DISPLAY) },
                    label = { Text("Display") },
                )
            }
            if (recording.active) {
                val elapsed by produceState(0L, recording.startedAt) {
                    while (true) {
                        value = System.currentTimeMillis() - recording.startedAt
                        kotlinx.coroutines.delay(250)
                    }
                }
                Text(
                    "Recording ${formatDuration(elapsed)}",
                    style = MaterialTheme.typography.headlineSmall,
                )
                Text("Use the notification or the button below to stop. Locking the phone also stops capture on Android 15+.")
            } else {
                Text(
                    "Android will ask which screen or app to capture. Consent is required every time.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(12.dp))
            Button(
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    if (recording.active) {
                        viewModel.stop(context)
                    } else {
                        viewModel.markStarting()
                        if (Build.VERSION.SDK_INT >= 33) {
                            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                        val needsMic = ui.audioMode == RecordAudioMode.MICROPHONE || ui.audioMode == RecordAudioMode.INTERNAL
                        if (needsMic) {
                            micPermission.launch(Manifest.permission.RECORD_AUDIO)
                        } else {
                            launchCapture(context, captureLauncher)
                        }
                    }
                },
            ) {
                Text(if (recording.active) "Stop recording" else "Start recording")
            }
        }
    }
}

private fun launchCapture(
    context: android.content.Context,
    launcher: androidx.activity.result.ActivityResultLauncher<Intent>,
) {
    val manager = context.getSystemService(MediaProjectionManager::class.java)
    launcher.launch(manager.createScreenCaptureIntent())
}
