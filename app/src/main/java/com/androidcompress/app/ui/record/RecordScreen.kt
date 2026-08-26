package com.androidcompress.app.ui.record

import android.Manifest
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.androidcompress.app.R
import com.androidcompress.app.capture.BookmarkMode
import com.androidcompress.app.capture.FacecamLens
import com.androidcompress.app.capture.FacecamShape
import com.androidcompress.app.capture.FacecamSize
import com.androidcompress.app.capture.RecordContainer
import com.androidcompress.app.capture.RecordMicDevice
import com.androidcompress.app.capture.RecordPhase
import com.androidcompress.app.capture.RecordVideoCodec
import com.androidcompress.app.capture.canDrawOverlays
import com.androidcompress.app.capture.requestOverlayPermission
import com.androidcompress.app.data.RecordAudioMode
import com.androidcompress.app.data.RecordResolution
import com.androidcompress.app.ui.components.AppTopBar
import com.androidcompress.app.util.formatDuration
import kotlinx.coroutines.launch

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
    val options = ui.options
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var appPicker by remember { mutableStateOf(false) }
    val overlayNeeded = stringResource(R.string.record_overlay_needed)

    val captureLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        viewModel.onConsentResult(context, result.resultCode, result.data)
    }
    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) launchCapture(context, captureLauncher)
        else viewModel.markStarting(false)
    }
    val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) {
            viewModel.markStarting(false)
            return@rememberLauncherForActivityResult
        }
        if (options.audioMode.needsRecordAudioPermission) {
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
        } else {
            launchCapture(context, captureLauncher)
        }
    }
    val bluetoothPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted && options.micDevice == RecordMicDevice.BLUETOOTH) {
            viewModel.markStarting(false)
            return@rememberLauncherForActivityResult
        }
        if (options.needsCamera) {
            cameraPermission.launch(Manifest.permission.CAMERA)
        } else if (options.audioMode.needsRecordAudioPermission) {
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
        } else {
            launchCapture(context, captureLauncher)
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.refreshTapsService()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(recording.finishedJobId) {
        val id = recording.finishedJobId ?: return@LaunchedEffect
        val notice = recording.notice
        if (!notice.isNullOrBlank()) snackbar.showSnackbar(notice)
        onFinished(id)
    }

    fun beginStart() {
        viewModel.markStarting()
        if (Build.VERSION.SDK_INT >= 33) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (options.needsOverlay && !canDrawOverlays(context)) {
            requestOverlayPermission(context)
            viewModel.markStarting(false)
            scope.launch { snackbar.showSnackbar(overlayNeeded) }
            return
        }
        if (options.micDevice == RecordMicDevice.BLUETOOTH &&
            options.audioMode.usesMicrophone &&
            Build.VERSION.SDK_INT >= 31
        ) {
            bluetoothPermission.launch(Manifest.permission.BLUETOOTH_CONNECT)
            return
        }
        if (options.needsCamera) {
            cameraPermission.launch(Manifest.permission.CAMERA)
        } else if (options.audioMode.needsRecordAudioPermission) {
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
        } else {
            launchCapture(context, captureLauncher)
        }
    }

    val activity = context as? android.app.Activity
    LaunchedEffect(recording.capturing, recording.paused, recording.pipEnabled, recording.bookmarks.size) {
        if (Build.VERSION.SDK_INT >= 26 && recording.pipEnabled && activity != null) {
            activity.setPictureInPictureParams(RecordPip.params(context, recording))
        }
    }

    val controlsEnabled = !recording.active
    Scaffold(
        topBar = { AppTopBar(stringResource(R.string.record_title), onBack = onBack) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(stringResource(R.string.record_audio), style = MaterialTheme.typography.titleMedium)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = options.audioMode == RecordAudioMode.NONE,
                    onClick = { viewModel.setAudioMode(RecordAudioMode.NONE) },
                    enabled = controlsEnabled,
                    label = { Text(stringResource(R.string.record_audio_none)) },
                )
                FilterChip(
                    selected = options.audioMode == RecordAudioMode.MICROPHONE,
                    onClick = { viewModel.setAudioMode(RecordAudioMode.MICROPHONE) },
                    enabled = controlsEnabled,
                    label = { Text(stringResource(R.string.record_audio_mic)) },
                )
                if (viewModel.supportsInternalAudio()) {
                    FilterChip(
                        selected = options.audioMode == RecordAudioMode.INTERNAL,
                        onClick = { viewModel.setAudioMode(RecordAudioMode.INTERNAL) },
                        enabled = controlsEnabled,
                        label = { Text(stringResource(R.string.record_audio_internal)) },
                    )
                    FilterChip(
                        selected = options.audioMode == RecordAudioMode.BOTH,
                        onClick = { viewModel.setAudioMode(RecordAudioMode.BOTH) },
                        enabled = controlsEnabled,
                        label = { Text(stringResource(R.string.record_audio_both)) },
                    )
                }
            }
            if (options.audioMode == RecordAudioMode.BOTH && !recording.active) {
                Text(
                    stringResource(
                        if (options.isolateAudioTracks) {
                            R.string.record_audio_both_hint_isolated
                        } else {
                            R.string.record_audio_both_hint
                        },
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (options.audioMode.usesMicrophone) {
                SwitchRow(
                    title = stringResource(R.string.record_echo_cancel),
                    hint = stringResource(R.string.record_echo_cancel_hint),
                    checked = options.echoCancel,
                    enabled = controlsEnabled,
                    onChecked = { viewModel.update { o -> o.copy(echoCancel = it) } },
                )
                SwitchRow(
                    title = stringResource(R.string.record_noise_suppress),
                    hint = stringResource(R.string.record_noise_suppress_hint),
                    checked = options.noiseSuppress,
                    enabled = controlsEnabled,
                    onChecked = { viewModel.update { o -> o.copy(noiseSuppress = it) } },
                )
                Text(stringResource(R.string.record_mic_device), style = MaterialTheme.typography.titleMedium)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(
                        selected = options.micDevice == RecordMicDevice.AUTO,
                        onClick = { viewModel.update { o -> o.copy(micDevice = RecordMicDevice.AUTO) } },
                        enabled = controlsEnabled,
                        label = { Text(stringResource(R.string.record_mic_auto)) },
                    )
                    FilterChip(
                        selected = options.micDevice == RecordMicDevice.BUILTIN,
                        onClick = { viewModel.update { o -> o.copy(micDevice = RecordMicDevice.BUILTIN) } },
                        enabled = controlsEnabled,
                        label = { Text(stringResource(R.string.record_mic_phone)) },
                    )
                    FilterChip(
                        selected = options.micDevice == RecordMicDevice.BLUETOOTH,
                        onClick = { viewModel.update { o -> o.copy(micDevice = RecordMicDevice.BLUETOOTH) } },
                        enabled = controlsEnabled,
                        label = { Text(stringResource(R.string.record_mic_bluetooth)) },
                    )
                }
            }
            if (options.audioMode.usesInternalAudio && viewModel.supportsInternalAudio()) {
                Text(stringResource(R.string.record_internal_app), style = MaterialTheme.typography.titleMedium)
                TextButton(
                    enabled = controlsEnabled,
                    onClick = {
                        viewModel.loadApps()
                        appPicker = true
                    },
                ) {
                    Text(
                        options.internalAudioLabel.ifBlank { stringResource(R.string.record_internal_any) },
                    )
                }
                Text(
                    stringResource(R.string.record_internal_app_hint),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (options.audioMode == RecordAudioMode.BOTH ||
                options.audioMode == RecordAudioMode.MICROPHONE ||
                options.audioMode == RecordAudioMode.INTERNAL
            ) {
                Text(stringResource(R.string.record_mix), style = MaterialTheme.typography.titleMedium)
                if (options.audioMode.usesMicrophone) {
                    Text(stringResource(R.string.record_mic_gain, options.micGainPercent))
                    Slider(
                        value = options.micGainPercent.toFloat(),
                        onValueChange = { viewModel.update { o -> o.copy(micGainPercent = it.toInt()) } },
                        valueRange = 0f..200f,
                        enabled = controlsEnabled,
                    )
                }
                if (options.audioMode.usesInternalAudio) {
                    Text(stringResource(R.string.record_internal_gain, options.internalGainPercent))
                    Slider(
                        value = options.internalGainPercent.toFloat(),
                        onValueChange = { viewModel.update { o -> o.copy(internalGainPercent = it.toInt()) } },
                        valueRange = 0f..200f,
                        enabled = controlsEnabled,
                    )
                }
                if (options.audioMode == RecordAudioMode.BOTH) {
                    SwitchRow(
                        title = stringResource(R.string.record_isolate_tracks),
                        hint = stringResource(R.string.record_isolate_tracks_hint),
                        checked = options.isolateAudioTracks,
                        enabled = controlsEnabled,
                        onChecked = { viewModel.update { o -> o.copy(isolateAudioTracks = it) } },
                    )
                    SwitchRow(
                        title = stringResource(R.string.record_duck),
                        hint = stringResource(
                            if (options.isolateAudioTracks) {
                                R.string.record_duck_hint_isolated
                            } else {
                                R.string.record_duck_hint
                            },
                        ),
                        checked = options.duckAppAudio && !options.isolateAudioTracks,
                        enabled = controlsEnabled && !options.isolateAudioTracks,
                        onChecked = { viewModel.update { o -> o.copy(duckAppAudio = it) } },
                    )
                }
            }

            Text(stringResource(R.string.record_resolution), style = MaterialTheme.typography.titleMedium)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = options.resolution == RecordResolution.P720,
                    onClick = { viewModel.setResolution(RecordResolution.P720) },
                    enabled = controlsEnabled,
                    label = { Text(stringResource(R.string.height_p, 720)) },
                )
                FilterChip(
                    selected = options.resolution == RecordResolution.P1080,
                    onClick = { viewModel.setResolution(RecordResolution.P1080) },
                    enabled = controlsEnabled,
                    label = { Text(stringResource(R.string.height_p, 1080)) },
                )
                FilterChip(
                    selected = options.resolution == RecordResolution.DISPLAY,
                    onClick = { viewModel.setResolution(RecordResolution.DISPLAY) },
                    enabled = controlsEnabled,
                    label = { Text(stringResource(R.string.record_res_display)) },
                )
            }

            Text(stringResource(R.string.record_fps), style = MaterialTheme.typography.titleMedium)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = options.frameRate == 30,
                    onClick = { viewModel.update { o -> o.copy(frameRate = 30) } },
                    enabled = controlsEnabled,
                    label = { Text(stringResource(R.string.record_fps_30)) },
                )
                FilterChip(
                    selected = options.frameRate == 60,
                    onClick = { viewModel.update { o -> o.copy(frameRate = 60) } },
                    enabled = controlsEnabled,
                    label = { Text(stringResource(R.string.record_fps_60)) },
                )
            }
            Text(stringResource(R.string.record_bitrate), style = MaterialTheme.typography.bodyMedium)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                listOf(0, 4000, 8000, 12000, 16000).forEach { kbps ->
                    FilterChip(
                        selected = options.videoBitrateKbps == kbps,
                        onClick = { viewModel.update { o -> o.copy(videoBitrateKbps = kbps) } },
                        enabled = controlsEnabled,
                        label = {
                            Text(
                                if (kbps == 0) stringResource(R.string.record_bitrate_auto)
                                else stringResource(R.string.record_bitrate_mbps, kbps / 1000),
                            )
                        },
                    )
                }
            }

            Text(stringResource(R.string.record_output), style = MaterialTheme.typography.titleMedium)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = options.container == RecordContainer.MP4,
                    onClick = { viewModel.update { o -> o.copy(container = RecordContainer.MP4) } },
                    enabled = controlsEnabled,
                    label = { Text(stringResource(R.string.container_mp4)) },
                )
                FilterChip(
                    selected = options.container == RecordContainer.WEBM,
                    onClick = { viewModel.update { o -> o.copy(container = RecordContainer.WEBM) } },
                    enabled = controlsEnabled,
                    label = { Text(stringResource(R.string.container_webm)) },
                )
            }
            if (options.usesWebm) {
                Text(
                    stringResource(R.string.record_webm_hint),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            SwitchRow(
                title = stringResource(R.string.record_direct),
                hint = stringResource(R.string.record_direct_hint),
                checked = options.directEncode,
                enabled = controlsEnabled,
                onChecked = { viewModel.update { o -> o.copy(directEncode = it) } },
            )
            if (options.directEncode && !options.usesWebm) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    viewModel.availableCodecs().forEach { codec ->
                        FilterChip(
                            selected = options.videoCodec == codec,
                            onClick = { viewModel.update { o -> o.copy(videoCodec = codec) } },
                            enabled = controlsEnabled,
                            label = {
                                Text(
                                    stringResource(
                                        when (codec) {
                                            RecordVideoCodec.H264 -> R.string.codec_h264
                                            RecordVideoCodec.HEVC -> R.string.codec_hevc
                                            RecordVideoCodec.AV1 -> R.string.codec_av1
                                        },
                                    ),
                                )
                            },
                        )
                    }
                }
            }

            Text(stringResource(R.string.record_session), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.record_countdown), style = MaterialTheme.typography.bodyMedium)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                listOf(0, 3, 5, 10).forEach { seconds ->
                    FilterChip(
                        selected = options.countdownSeconds == seconds,
                        onClick = { viewModel.update { o -> o.copy(countdownSeconds = seconds) } },
                        enabled = controlsEnabled,
                        label = {
                            Text(
                                if (seconds == 0) stringResource(R.string.record_countdown_off)
                                else stringResource(R.string.record_countdown_s, seconds),
                            )
                        },
                    )
                }
            }
            Text(stringResource(R.string.record_max_duration), style = MaterialTheme.typography.bodyMedium)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                listOf(0, 1, 3, 10, 30).forEach { minutes ->
                    FilterChip(
                        selected = options.maxDurationMinutes == minutes,
                        onClick = { viewModel.update { o -> o.copy(maxDurationMinutes = minutes) } },
                        enabled = controlsEnabled,
                        label = {
                            Text(
                                if (minutes == 0) stringResource(R.string.record_max_off)
                                else stringResource(R.string.record_max_minutes, minutes),
                            )
                        },
                    )
                }
            }
            SwitchRow(
                title = stringResource(R.string.record_low_storage),
                hint = stringResource(R.string.record_low_storage_hint),
                checked = options.autoStopLowStorage,
                enabled = controlsEnabled,
                onChecked = { viewModel.update { o -> o.copy(autoStopLowStorage = it) } },
            )

            Text(stringResource(R.string.record_overlays), style = MaterialTheme.typography.titleMedium)
            SwitchRow(
                title = stringResource(R.string.record_region),
                hint = stringResource(R.string.record_region_hint),
                checked = options.captureRegion,
                enabled = controlsEnabled,
                onChecked = { viewModel.update { o -> o.copy(captureRegion = it) } },
            )
            if (viewModel.hasAnyCamera()) {
                SwitchRow(
                    title = stringResource(R.string.record_facecam),
                    hint = stringResource(R.string.record_facecam_hint),
                    checked = options.facecam,
                    enabled = controlsEnabled,
                    onChecked = { viewModel.update { o -> o.copy(facecam = it) } },
                )
            }
            if (options.facecam && viewModel.hasAnyCamera()) {
                if (ui.hasFrontCamera && ui.hasBackCamera) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        FilterChip(
                            selected = options.facecamLens == FacecamLens.FRONT,
                            onClick = { viewModel.update { o -> o.copy(facecamLens = FacecamLens.FRONT) } },
                            enabled = controlsEnabled,
                            label = { Text(stringResource(R.string.record_facecam_front)) },
                        )
                        FilterChip(
                            selected = options.facecamLens == FacecamLens.BACK,
                            onClick = { viewModel.update { o -> o.copy(facecamLens = FacecamLens.BACK) } },
                            enabled = controlsEnabled,
                            label = { Text(stringResource(R.string.record_facecam_back)) },
                        )
                    }
                }
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(
                        selected = options.facecamShape == FacecamShape.RECT,
                        onClick = { viewModel.update { o -> o.copy(facecamShape = FacecamShape.RECT) } },
                        enabled = controlsEnabled,
                        label = { Text(stringResource(R.string.record_facecam_rect)) },
                    )
                    FilterChip(
                        selected = options.facecamShape == FacecamShape.ROUND,
                        onClick = { viewModel.update { o -> o.copy(facecamShape = FacecamShape.ROUND) } },
                        enabled = controlsEnabled,
                        label = { Text(stringResource(R.string.record_facecam_round)) },
                    )
                    FacecamSize.entries.forEach { size ->
                        FilterChip(
                            selected = options.facecamSize == size,
                            onClick = { viewModel.update { o -> o.copy(facecamSize = size) } },
                            enabled = controlsEnabled,
                            label = {
                                Text(
                                    stringResource(
                                        when (size) {
                                            FacecamSize.SMALL -> R.string.record_facecam_small
                                            FacecamSize.MEDIUM -> R.string.record_facecam_medium
                                            FacecamSize.LARGE -> R.string.record_facecam_large
                                        },
                                    ),
                                )
                            },
                        )
                    }
                }
                SwitchRow(
                    title = stringResource(R.string.record_facecam_hide_pause),
                    hint = stringResource(R.string.record_facecam_hide_pause_hint),
                    checked = options.facecamHideOnPause,
                    enabled = controlsEnabled,
                    onChecked = { viewModel.update { o -> o.copy(facecamHideOnPause = it) } },
                )
            }
            SwitchRow(
                title = stringResource(R.string.record_taps),
                hint = stringResource(R.string.record_taps_hint),
                checked = options.showTaps,
                enabled = controlsEnabled,
                onChecked = { viewModel.update { o -> o.copy(showTaps = it) } },
            )
            SwitchRow(
                title = stringResource(R.string.record_laser),
                hint = stringResource(R.string.record_laser_hint),
                checked = options.showLaser,
                enabled = controlsEnabled,
                onChecked = { viewModel.update { o -> o.copy(showLaser = it) } },
            )
            SwitchRow(
                title = stringResource(R.string.record_annotation),
                hint = stringResource(R.string.record_annotation_hint),
                checked = options.showAnnotation,
                enabled = controlsEnabled,
                onChecked = { viewModel.update { o -> o.copy(showAnnotation = it) } },
            )
            if (options.needsPointerOverlay && !ui.tapsServiceEnabled) {
                TextButton(onClick = {
                    context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }) {
                    Text(stringResource(R.string.record_taps_enable))
                }
            }
            SwitchRow(
                title = stringResource(R.string.record_bubble),
                hint = stringResource(R.string.record_bubble_hint),
                checked = options.showBubble,
                enabled = controlsEnabled,
                onChecked = { viewModel.update { o -> o.copy(showBubble = it) } },
            )
            SwitchRow(
                title = stringResource(R.string.record_pip),
                hint = stringResource(R.string.record_pip_hint),
                checked = options.pipControls,
                enabled = controlsEnabled,
                onChecked = { viewModel.update { o -> o.copy(pipControls = it) } },
            )
            SwitchRow(
                title = stringResource(R.string.record_cover_status),
                hint = stringResource(R.string.record_cover_status_hint),
                checked = options.coverStatusBar,
                enabled = controlsEnabled,
                onChecked = { viewModel.update { o -> o.copy(coverStatusBar = it) } },
            )
            SwitchRow(
                title = stringResource(R.string.record_grayscale),
                hint = stringResource(R.string.record_grayscale_hint),
                checked = options.grayscale,
                enabled = controlsEnabled,
                onChecked = { viewModel.update { o -> o.copy(grayscale = it) } },
            )
            SwitchRow(
                title = stringResource(R.string.record_captions),
                hint = stringResource(R.string.record_captions_hint),
                checked = options.captions,
                enabled = controlsEnabled,
                onChecked = { on ->
                    viewModel.update { o -> o.copy(captions = on, burnCaptions = o.burnCaptions && on) }
                },
            )
            if (options.captions) {
                SwitchRow(
                    title = stringResource(R.string.record_burn_captions),
                    hint = stringResource(R.string.record_burn_captions_hint),
                    checked = options.burnCaptions,
                    enabled = controlsEnabled,
                    onChecked = { viewModel.update { o -> o.copy(burnCaptions = it) } },
                    modifier = Modifier.padding(start = 16.dp),
                )
            }
            SwitchRow(
                title = stringResource(R.string.record_quiet_notif),
                hint = stringResource(R.string.record_quiet_notif_hint),
                checked = options.quietNotification,
                enabled = controlsEnabled,
                onChecked = { viewModel.update { o -> o.copy(quietNotification = it) } },
            )
            Text(stringResource(R.string.record_bookmarks), style = MaterialTheme.typography.titleMedium)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = options.bookmarkMode == BookmarkMode.OFF,
                    onClick = { viewModel.update { o -> o.copy(bookmarkMode = BookmarkMode.OFF) } },
                    enabled = controlsEnabled,
                    label = { Text(stringResource(R.string.record_bookmarks_off)) },
                )
                FilterChip(
                    selected = options.bookmarkMode == BookmarkMode.CHAPTERS,
                    onClick = { viewModel.update { o -> o.copy(bookmarkMode = BookmarkMode.CHAPTERS) } },
                    enabled = controlsEnabled,
                    label = { Text(stringResource(R.string.record_bookmarks_chapters)) },
                )
                FilterChip(
                    selected = options.bookmarkMode == BookmarkMode.SPLIT,
                    onClick = { viewModel.update { o -> o.copy(bookmarkMode = BookmarkMode.SPLIT) } },
                    enabled = controlsEnabled,
                    label = { Text(stringResource(R.string.record_bookmarks_split)) },
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
                    when {
                        recording.saving -> stringResource(R.string.record_elapsed_saving)
                        recording.phase == RecordPhase.REGION -> stringResource(R.string.record_phase_region)
                        recording.phase == RecordPhase.COUNTDOWN -> stringResource(
                            R.string.record_hint_countdown,
                            recording.countdownRemaining,
                        )
                        recording.paused -> stringResource(R.string.record_elapsed_paused, formatDuration(elapsed))
                        else -> stringResource(R.string.record_elapsed, formatDuration(elapsed))
                    },
                    style = MaterialTheme.typography.headlineSmall,
                )
                Text(
                    stringResource(
                        when {
                            recording.saving -> R.string.record_hint_saving
                            recording.phase == RecordPhase.REGION -> R.string.record_hint_region
                            else -> R.string.record_hint_active
                        },
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
                if (!recording.saving && recording.capturing) {
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
                    if (options.bookmarkMode != BookmarkMode.OFF) {
                        OutlinedButton(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = { viewModel.bookmark(context) },
                        ) {
                            Text(
                                stringResource(
                                    R.string.record_bookmark_count,
                                    recording.bookmarks.size,
                                ),
                            )
                        }
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
                    onClick = { beginStart() },
                ) {
                    Text(stringResource(R.string.record_start))
                }
            }
        }
    }

    if (appPicker) {
        CaptureAppDialog(
            apps = ui.apps,
            onAny = {
                viewModel.update { o -> o.copy(internalAudioPackage = "", internalAudioLabel = "") }
                appPicker = false
            },
            onPick = { pkg, label ->
                viewModel.update { o -> o.copy(internalAudioPackage = pkg, internalAudioLabel = label) }
                appPicker = false
            },
            onDismiss = { appPicker = false },
        )
    }
}

@Composable
private fun SwitchRow(
    title: String,
    hint: String,
    checked: Boolean,
    enabled: Boolean,
    onChecked: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title)
            Text(
                hint,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onChecked, enabled = enabled)
    }
}

@Composable
private fun CaptureAppDialog(
    apps: List<com.androidcompress.app.capture.CaptureApp>,
    onAny: () -> Unit,
    onPick: (String, String) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val filtered = remember(apps, query) {
        val q = query.trim()
        if (q.isEmpty()) apps else apps.filter {
            it.label.contains(q, ignoreCase = true) || it.packageName.contains(q, ignoreCase = true)
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.record_internal_pick)) },
        text = {
            Column {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text(stringResource(R.string.record_internal_search)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                )
                TextButton(onClick = onAny) { Text(stringResource(R.string.record_internal_any)) }
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    items(filtered, key = { it.packageName }) { app ->
                        TextButton(
                            onClick = { onPick(app.packageName, app.label) },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(app.label, modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

private fun launchCapture(
    context: android.content.Context,
    launcher: androidx.activity.result.ActivityResultLauncher<Intent>,
) {
    val manager = context.getSystemService(MediaProjectionManager::class.java)
    launcher.launch(manager.createScreenCaptureIntent())
}
