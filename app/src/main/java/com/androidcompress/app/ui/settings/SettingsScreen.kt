package com.androidcompress.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.androidcompress.app.data.EncodeEngine
import com.androidcompress.app.data.Preset
import com.androidcompress.app.ui.components.AppTopBar

@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onBack: () -> Unit,
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val uriHandler = LocalUriHandler.current
    Scaffold(topBar = { AppTopBar("Settings", onBack = onBack) }) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Default engine", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = settings.defaultEngine == EncodeEngine.FFMPEG,
                    onClick = { viewModel.setEngine(EncodeEngine.FFMPEG) },
                    label = { Text("FFmpeg") },
                )
                FilterChip(
                    selected = settings.defaultEngine == EncodeEngine.MEDIA3,
                    onClick = { viewModel.setEngine(EncodeEngine.MEDIA3) },
                    label = { Text("Device (Media3)") },
                )
            }
            Text(
                "Device uses Media3 Transformer and the hardware encoder, without FFmpeg. Applied to new jobs.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text("Default preset", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = settings.defaultPreset == Preset.SMALLER,
                    onClick = { viewModel.setPreset(Preset.SMALLER) },
                    label = { Text("Smaller") },
                )
                FilterChip(
                    selected = settings.defaultPreset == Preset.BALANCED,
                    onClick = { viewModel.setPreset(Preset.BALANCED) },
                    label = { Text("Balanced") },
                )
                FilterChip(
                    selected = settings.defaultPreset == Preset.HIGHER,
                    onClick = { viewModel.setPreset(Preset.HIGHER) },
                    label = { Text("Higher quality") },
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Remember advanced settings")
                    Text(
                        "Reuse the last resolution, bitrate, and audio choices.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = settings.rememberAdvanced, onCheckedChange = viewModel::setRememberAdvanced)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Auto-compress after recording")
                    Text(
                        "Start Balanced (or your remembered settings) as soon as a recording stops.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = settings.autoCompressAfterRecord, onCheckedChange = viewModel::setAutoCompress)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Delete original after compress")
                    Text(
                        "Default for new jobs. The original is removed only after the compressed file is written. Recordings this app created can always be deleted; gallery files may be blocked by Android.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = settings.deleteOriginalAfterEncode, onCheckedChange = viewModel::setDeleteOriginal)
            }
            Text("Gemini extra args", style = MaterialTheme.typography.titleMedium)
            Text(
                "Optional. A free Google AI Studio key lets the compress screen turn a plain-English request into extra FFmpeg flags. It starts with the latest Flash model and falls back to older or lighter ones if that model is missing or rate-limited. The video file stays on this device; only the text prompt and encode settings are sent to Google.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = settings.geminiApiKey,
                onValueChange = viewModel::setGeminiApiKey,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("Gemini API key") },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            )
            TextButton(onClick = { uriHandler.openUri("https://aistudio.google.com/apikey") }) {
                Text("Get a free Gemini API key")
            }
        }
    }
}
