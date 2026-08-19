package com.androidcompress.app.ui.record

import android.Manifest
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.androidcompress.app.R
import com.androidcompress.app.data.RecordAudioMode
import com.androidcompress.app.data.RecordResolution
import com.androidcompress.app.ui.components.AppTopBar
import com.androidcompress.app.util.formatDuration

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
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
        else viewModel.markStarting(false)
    }

    LaunchedEffect(recording.finishedJobId) {
        recording.finishedJobId?.let(onFinished)
    }

    Scaffold(topBar = { AppTopBar(stringResource(R.string.record_title), onBack = onBack) }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            val controlsEnabled = !recording.active
            Text(stringResource(R.string.record_audio), style = MaterialTheme.typography.titleMedium)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = ui.audioMode == RecordAudioMode.NONE,
                    onClick = { viewModel.setAudioMode(RecordAudioMode.NONE) },
                    enabled = controlsEnabled,
                    label = { Text(stringResource(R.string.record_audio_none)) },
                )
                FilterChip(
                    selected = ui.audioMode == RecordAudioMode.MICROPHONE,
                    onClick = { viewModel.setAudioMode(RecordAudioMode.MICROPHONE) },
                    enabled = controlsEnabled,
                    label = { Text(stringResource(R.string.record_audio_mic)) },
                )
                if (viewModel.supportsInternalAudio()) {
                    FilterChip(
                        selected = ui.audioMode == RecordAudioMode.INTERNAL,
                        onClick = { viewModel.setAudioMode(RecordAudioMode.INTERNAL) },
                        enabled = controlsEnabled,
                        label = { Text(stringResource(R.string.record_audio_internal)) },
                    )
                    FilterChip(
                        selected = ui.audioMode == RecordAudioMode.BOTH,
                        onClick = { viewModel.setAudioMode(RecordAudioMode.BOTH) },
                        enabled = controlsEnabled,
                        label = { Text(stringResource(R.string.record_audio_both)) },
                    )
                }
            }
            if (ui.audioMode == RecordAudioMode.BOTH && !recording.active) {
                Text(
                    stringResource(R.string.record_audio_both_hint),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Text(stringResource(R.string.record_resolution), style = MaterialTheme.typography.titleMedium)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = ui.resolution == RecordResolution.P720,
                    onClick = { viewModel.setResolution(RecordResolution.P720) },
                    enabled = controlsEnabled,
                    label = { Text(stringResource(R.string.height_p, 720)) },
                )
                FilterChip(
                    selected = ui.resolution == RecordResolution.P1080,
                    onClick = { viewModel.setResolution(RecordResolution.P1080) },
                    enabled = controlsEnabled,
                    label = { Text(stringResource(R.string.height_p, 1080)) },
                )
                FilterChip(
                    selected = ui.resolution == RecordResolution.DISPLAY,
                    onClick = { viewModel.setResolution(RecordResolution.DISPLAY) },
                    enabled = controlsEnabled,
                    label = { Text(stringResource(R.string.record_res_display)) },
                )
            }
            if (recording.active) {
                val elapsed by produceState(0L, recording) {
                    while (true) {
                        value = recording.elapsedMs()
                        kotlinx.coroutines.delay(250)
                    }
                }
                Text(
                    if (recording.saving) {
                        stringResource(R.string.record_elapsed_saving)
                    } else {
                        stringResource(
                            if (recording.paused) R.string.record_elapsed_paused else R.string.record_elapsed,
                            formatDuration(elapsed),
                        )
                    },
                    style = MaterialTheme.typography.headlineSmall,
                )
                Text(
                    stringResource(
                        if (recording.saving) R.string.record_hint_saving else R.string.record_hint_active,
                    ),
                )
            } else {
                Text(
                    stringResource(R.string.record_hint_idle),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(12.dp))
            if (recording.active) {
                if (!recording.saving) {
                    OutlinedButton(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            if (recording.paused) viewModel.resume(context)
                            else viewModel.pause(context)
                        },
                    ) {
                        Text(
                            stringResource(
                                if (recording.paused) R.string.record_resume else R.string.record_pause,
                            ),
                        )
                    }
                }
                Button(
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !recording.saving,
                    onClick = { viewModel.stop(context) },
                ) {
                    Text(stringResource(R.string.record_stop))
                }
            } else {
                Button(
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !ui.starting,
                    onClick = {
                        viewModel.markStarting()
                        if (Build.VERSION.SDK_INT >= 33) {
                            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                        if (ui.audioMode.needsRecordAudioPermission) {
                            micPermission.launch(Manifest.permission.RECORD_AUDIO)
                        } else {
                            launchCapture(context, captureLauncher)
                        }
                    },
                ) {
                    Text(stringResource(R.string.record_start))
                }
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
