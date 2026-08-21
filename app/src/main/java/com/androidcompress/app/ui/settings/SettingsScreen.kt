package com.androidcompress.app.ui.settings

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.androidcompress.app.R
import com.androidcompress.app.data.EncodeEngine
import com.androidcompress.app.data.Preset
import com.androidcompress.app.encode.EncodeStallTimeout
import com.androidcompress.app.media.MediaLibraryAccess
import com.androidcompress.app.ui.components.AppTopBar
import com.androidcompress.app.ui.presetLabel

@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onBack: () -> Unit,
    promptLibraryAccess: Boolean = false,
    onHardwareTest: () -> Unit = {},
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val uriHandler = LocalUriHandler.current
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var grantEpoch by remember { mutableIntStateOf(0) }
    val libraryGranted = remember(grantEpoch) { MediaLibraryAccess.granted(context) }
    val libraryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grantEpoch++ }
    LaunchedEffect(promptLibraryAccess, libraryGranted) {
        if (promptLibraryAccess && !libraryGranted) {
            libraryLauncher.launch(MediaLibraryAccess.requiredPermissions())
        }
    }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) grantEpoch++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    Scaffold(topBar = { AppTopBar(stringResource(R.string.settings_title), onBack = onBack) }) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(stringResource(R.string.settings_default_engine), style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = settings.defaultEngine == EncodeEngine.FFMPEG,
                    onClick = { viewModel.setEngine(EncodeEngine.FFMPEG) },
                    label = { Text(stringResource(R.string.engine_ffmpeg)) },
                )
                FilterChip(
                    selected = settings.defaultEngine == EncodeEngine.MEDIA3,
                    onClick = { viewModel.setEngine(EncodeEngine.MEDIA3) },
                    label = { Text(stringResource(R.string.engine_media3)) },
                )
            }
            Text(
                stringResource(R.string.settings_engine_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(stringResource(R.string.settings_default_preset), style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = settings.defaultPreset == Preset.SMALLER,
                    onClick = { viewModel.setPreset(Preset.SMALLER) },
                    label = { Text(presetLabel(Preset.SMALLER)) },
                )
                FilterChip(
                    selected = settings.defaultPreset == Preset.BALANCED,
                    onClick = { viewModel.setPreset(Preset.BALANCED) },
                    label = { Text(presetLabel(Preset.BALANCED)) },
                )
                FilterChip(
                    selected = settings.defaultPreset == Preset.HIGHER,
                    onClick = { viewModel.setPreset(Preset.HIGHER) },
                    label = { Text(presetLabel(Preset.HIGHER)) },
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.settings_remember_advanced))
                    Text(
                        stringResource(R.string.settings_remember_advanced_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = settings.rememberAdvanced, onCheckedChange = viewModel::setRememberAdvanced)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.settings_auto_compress))
                    Text(
                        stringResource(R.string.settings_auto_compress_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = settings.autoCompressAfterRecord, onCheckedChange = viewModel::setAutoCompress)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.settings_delete_original))
                    Text(
                        stringResource(R.string.settings_delete_original_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = settings.deleteOriginalAfterEncode, onCheckedChange = viewModel::setDeleteOriginal)
            }
            Text(stringResource(R.string.settings_stall_title), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(R.string.settings_stall_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            StallTimeoutField(
                label = stringResource(R.string.settings_stall_one_pass),
                value = settings.stallTimeoutSec,
                defaultSec = EncodeStallTimeout.DEFAULT_SEC,
                onCommit = viewModel::setStallTimeoutSec,
            )
            StallTimeoutField(
                label = stringResource(R.string.settings_stall_two_pass),
                value = settings.twoPassStallTimeoutSec,
                defaultSec = EncodeStallTimeout.DEFAULT_TWO_PASS_SEC,
                onCommit = viewModel::setTwoPassStallTimeoutSec,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.settings_library_access))
                    Text(
                        stringResource(
                            if (libraryGranted) {
                                R.string.settings_library_granted
                            } else {
                                R.string.settings_library_needed
                            },
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = libraryGranted,
                    onCheckedChange = { enable ->
                        if (enable) {
                            libraryLauncher.launch(MediaLibraryAccess.requiredPermissions())
                        } else {
                            context.startActivity(
                                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).setData(
                                    Uri.fromParts("package", context.packageName, null),
                                ),
                            )
                        }
                    },
                )
            }
            Text(stringResource(R.string.settings_hardware_title), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(R.string.settings_hardware_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = onHardwareTest) {
                Text(stringResource(R.string.settings_hardware_open))
            }
            Text(stringResource(R.string.settings_gemini_title), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(R.string.settings_gemini_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = settings.geminiApiKey,
                onValueChange = viewModel::setGeminiApiKey,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text(stringResource(R.string.settings_gemini_key)) },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            )
            TextButton(onClick = { uriHandler.openUri("https://aistudio.google.com/apikey") }) {
                Text(stringResource(R.string.settings_gemini_get_key))
            }
        }
    }
}

@Composable
private fun StallTimeoutField(
    label: String,
    value: Int,
    defaultSec: Int,
    onCommit: (Int) -> Unit,
) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    fun commit() {
        val parsed = text.toIntOrNull()
        if (parsed != null) onCommit(parsed) else text = value.toString()
    }
    OutlinedTextField(
        value = text,
        onValueChange = { text = it.filter { ch -> ch.isDigit() }.take(9) },
        modifier = Modifier
            .fillMaxWidth()
            .onFocusChanged { if (!it.isFocused) commit() },
        singleLine = true,
        label = { Text(label) },
        supportingText = {
            Text(stringResource(R.string.settings_stall_default, defaultSec))
        },
        suffix = { Text(stringResource(R.string.settings_stall_seconds_suffix)) },
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Number,
            imeAction = ImeAction.Done,
        ),
        keyboardActions = KeyboardActions(onDone = { commit() }),
    )
}
