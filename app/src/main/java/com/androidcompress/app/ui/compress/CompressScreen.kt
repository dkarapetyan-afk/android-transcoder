package com.androidcompress.app.ui.compress

import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.androidcompress.app.data.AudioOption
import com.androidcompress.app.data.BFrameSetting
import com.androidcompress.app.data.BitrateMode
import com.androidcompress.app.data.EncodeEngine
import com.androidcompress.app.data.ContainerFormat
import com.androidcompress.app.data.EncodeSettings
import com.androidcompress.app.data.OutputMode
import com.androidcompress.app.data.audioOutput
import com.androidcompress.app.data.usesWebm
import com.androidcompress.app.data.H264Profile
import com.androidcompress.app.data.HdrMode
import com.androidcompress.app.data.KeyframeInterval
import com.androidcompress.app.data.Preset
import com.androidcompress.app.data.VideoCodec
import com.androidcompress.app.encode.Media3EncodePlanner
import com.androidcompress.app.ui.components.AppTopBar
import com.androidcompress.app.ui.components.StatLine
import com.androidcompress.app.ui.components.VideoThumbnail
import com.androidcompress.app.util.formatBytes
import com.androidcompress.app.util.formatDuration
import com.androidcompress.app.util.formatResolution
import com.androidcompress.app.util.parseDurationMs

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun CompressScreen(
    viewModel: CompressViewModel,
    onBack: () -> Unit,
    onStarted: (String) -> Unit,
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val job = ui.job
    val settings = ui.settings

    LaunchedEffect(job?.id, job?.status) {
        viewModel.maybeAutoStart(context) { onStarted(jobIdOr(job)) }
    }

    Scaffold(topBar = { AppTopBar("Compress", onBack = onBack) }) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            VideoThumbnail(
                uri = job?.sourceUri?.takeIf { it.isNotBlank() }?.let(Uri::parse),
                modifier = Modifier.fillMaxWidth().height(180.dp),
            )
            if (job != null) {
                Text(job.displayName, style = MaterialTheme.typography.titleMedium)
                StatLine("Source", "${formatResolution(job.width, job.height)} · ${formatDuration(job.durationMs)} · ${formatBytes(job.sourceBytes)}")
                val sourceHasVideo = job.width > 0 && job.height > 0
                if (settings.engine == EncodeEngine.MEDIA3 || settings.audioOutput(sourceHasVideo)) {
                    val clip = Media3EncodePlanner.clipWindow(settings, job.durationMs)
                    if (clip.active) {
                        val endLabel = clip.endMs?.let { formatDuration(it) } ?: formatDuration(job.durationMs)
                        StatLine(
                            "Clip",
                            "${formatDuration(clip.startMs)} – $endLabel · ${formatDuration(clip.durationMs(job.durationMs))}",
                        )
                    }
                }
                StatLine("Estimated output", "${formatBytes(ui.estimateBytes)} (estimate)")
                if (ui.encoderLabel.isNotBlank()) {
                    StatLine("Encoder", ui.encoderLabel)
                }
            }
            Text("Preset", style = MaterialTheme.typography.titleMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Preset.entries.forEach { preset ->
                    FilterChip(
                        selected = settings.preset == preset,
                        onClick = { viewModel.applyPreset(preset) },
                        label = { Text(presetLabel(preset)) },
                    )
                }
            }
            Text("Engine", style = MaterialTheme.typography.titleMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = settings.engine == EncodeEngine.FFMPEG,
                    onClick = { viewModel.update { it.copy(engine = EncodeEngine.FFMPEG) } },
                    label = { Text("FFmpeg") },
                )
                FilterChip(
                    selected = settings.engine == EncodeEngine.MEDIA3,
                    onClick = { viewModel.update { it.copy(engine = EncodeEngine.MEDIA3) } },
                    label = { Text("Device (Media3)") },
                )
            }
            Text(
                if (settings.engine == EncodeEngine.MEDIA3) {
                    "Uses Android Media3 Transformer and the hardware encoder. No FFmpeg."
                } else {
                    "Uses the bundled FFmpeg build, including software fallback if the hardware encoder fails."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val sourceHasVideo = job == null || job.width > 0 || job.height > 0
            Text("Output", style = MaterialTheme.typography.titleMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (sourceHasVideo) {
                    FilterChip(
                        selected = settings.output == OutputMode.VIDEO,
                        onClick = { viewModel.setOutput(OutputMode.VIDEO) },
                        label = { Text("Video") },
                    )
                }
                FilterChip(
                    selected = settings.output == OutputMode.AUDIO || !sourceHasVideo,
                    onClick = { viewModel.setOutput(OutputMode.AUDIO) },
                    label = { Text("Audio only") },
                )
            }
            Text("Container", style = MaterialTheme.typography.titleMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = settings.container == ContainerFormat.MP4,
                    onClick = { viewModel.setContainer(ContainerFormat.MP4) },
                    label = { Text(if (settings.audioOutput(sourceHasVideo)) "M4A" else "MP4") },
                )
                FilterChip(
                    selected = settings.container == ContainerFormat.WEBM,
                    onClick = { viewModel.setContainer(ContainerFormat.WEBM) },
                    label = { Text("WebM") },
                )
            }
            Text(
                when {
                    settings.audioOutput(sourceHasVideo) && settings.usesWebm() ->
                        "Writes an Opus .webm. From a video this extracts the soundtrack; from audio this re-encodes it."
                    settings.audioOutput(sourceHasVideo) ->
                        "Writes an AAC .m4a. From a video this extracts the soundtrack; from audio this re-encodes it."
                    settings.usesWebm() ->
                        "Writes a VP8/VP9 + Opus WebM. FFmpeg uses software libvpx. Media3 uses the device encoder."
                    else ->
                        "Writes a compressed MP4 with video and audio."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (settings.engine == EncodeEngine.MEDIA3 || settings.audioOutput(sourceHasVideo)) {
                Media3ClipControls(
                    durationMs = job?.durationMs ?: 0L,
                    startMs = settings.clipStartMs,
                    endMs = settings.clipEndMs,
                    onStart = viewModel::setClipStartMs,
                    onEnd = viewModel::setClipEndMs,
                    onClear = viewModel::clearClip,
                )
            }
            TextButton(onClick = viewModel::toggleAdvanced) {
                Text(if (ui.advancedOpen) "Hide advanced" else "Advanced")
            }
            if (ui.advancedOpen) {
                val audioOnly = settings.audioOutput(sourceHasVideo)
                if (!audioOnly) {
                Text("Resolution cap")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(null, 2160, 1440, 1080, 720, 480, 360).forEach { height ->
                        FilterChip(
                            selected = settings.maxHeight == height,
                            onClick = { viewModel.update { it.copy(maxHeight = height) } },
                            label = { Text(heightLabel(height)) },
                        )
                    }
                }
                Text("Codec")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (settings.usesWebm()) {
                        FilterChip(
                            selected = settings.codec == VideoCodec.VP9,
                            onClick = { viewModel.update { it.copy(codec = VideoCodec.VP9) } },
                            label = { Text("VP9") },
                        )
                        FilterChip(
                            selected = settings.codec == VideoCodec.VP8,
                            onClick = { viewModel.update { it.copy(codec = VideoCodec.VP8) } },
                            label = { Text("VP8") },
                        )
                    } else {
                        FilterChip(
                            selected = settings.codec == VideoCodec.H264,
                            onClick = { viewModel.update { it.copy(codec = VideoCodec.H264) } },
                            label = { Text("H.264") },
                        )
                        if (ui.capabilities.hasHevcMediaCodec) {
                            FilterChip(
                                selected = settings.codec == VideoCodec.HEVC,
                                onClick = { viewModel.update { it.copy(codec = VideoCodec.HEVC) } },
                                label = { Text("HEVC") },
                            )
                        }
                    }
                }
                Text("Quality  ${settings.videoBitrateKbps} kbps")
                Slider(
                    value = settings.videoBitrateKbps.toFloat(),
                    onValueChange = { value -> viewModel.update { it.copy(videoBitrateKbps = value.toInt()) } },
                    valueRange = 400f..20000f,
                )
                Text("Bitrate mode")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = settings.bitrateMode == BitrateMode.CBR,
                        onClick = { viewModel.update { it.copy(bitrateMode = BitrateMode.CBR) } },
                        label = { Text("CBR") },
                    )
                    FilterChip(
                        selected = settings.bitrateMode == BitrateMode.VBR,
                        onClick = { viewModel.update { it.copy(bitrateMode = BitrateMode.VBR) } },
                        label = { Text("VBR") },
                    )
                }
                Text(
                    "CBR holds a steady rate. VBR can look better at the same average size. MediaTek devices still try VBR first.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text("Frame rate")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(null, 60, 30, 24).forEach { fps ->
                        FilterChip(
                            selected = settings.fpsCap == fps,
                            onClick = { viewModel.update { it.copy(fpsCap = fps) } },
                            label = { Text(if (fps == null) "Original" else fps.toString()) },
                        )
                    }
                }
                Text("Keyframe interval")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = settings.keyframeInterval == KeyframeInterval.AUTO,
                        onClick = { viewModel.update { it.copy(keyframeInterval = KeyframeInterval.AUTO) } },
                        label = { Text("Auto") },
                    )
                    FilterChip(
                        selected = settings.keyframeInterval == KeyframeInterval.SEC_1,
                        onClick = { viewModel.update { it.copy(keyframeInterval = KeyframeInterval.SEC_1) } },
                        label = { Text("1 s") },
                    )
                    FilterChip(
                        selected = settings.keyframeInterval == KeyframeInterval.SEC_2,
                        onClick = { viewModel.update { it.copy(keyframeInterval = KeyframeInterval.SEC_2) } },
                        label = { Text("2 s") },
                    )
                    FilterChip(
                        selected = settings.keyframeInterval == KeyframeInterval.SEC_5,
                        onClick = { viewModel.update { it.copy(keyframeInterval = KeyframeInterval.SEC_5) } },
                        label = { Text("5 s") },
                    )
                }
                if (!settings.usesWebm() && settings.codec == VideoCodec.H264) {
                    Text("H.264 profile")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = settings.h264Profile == H264Profile.AUTO,
                            onClick = { viewModel.update { it.copy(h264Profile = H264Profile.AUTO) } },
                            label = { Text("Auto") },
                        )
                        FilterChip(
                            selected = settings.h264Profile == H264Profile.BASELINE,
                            onClick = { viewModel.update { it.copy(h264Profile = H264Profile.BASELINE) } },
                            label = { Text("Baseline") },
                        )
                        FilterChip(
                            selected = settings.h264Profile == H264Profile.MAIN,
                            onClick = { viewModel.update { it.copy(h264Profile = H264Profile.MAIN) } },
                            label = { Text("Main") },
                        )
                        FilterChip(
                            selected = settings.h264Profile == H264Profile.HIGH,
                            onClick = { viewModel.update { it.copy(h264Profile = H264Profile.HIGH) } },
                            label = { Text("High") },
                        )
                    }
                    Text(
                        if (settings.engine == EncodeEngine.MEDIA3) {
                            "Some device H.264 encoders ignore profile. FFmpeg honors it more reliably."
                        } else {
                            "Baseline is the most compatible. High usually looks better at the same bitrate."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (!settings.usesWebm()) {
                Text("B-frames")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = settings.bFrames == BFrameSetting.AUTO,
                        onClick = { viewModel.update { it.copy(bFrames = BFrameSetting.AUTO) } },
                        label = { Text("Auto") },
                    )
                    FilterChip(
                        selected = settings.bFrames == BFrameSetting.NONE,
                        onClick = { viewModel.update { it.copy(bFrames = BFrameSetting.NONE) } },
                        label = { Text("Off") },
                    )
                    FilterChip(
                        selected = settings.bFrames == BFrameSetting.ONE,
                        onClick = { viewModel.update { it.copy(bFrames = BFrameSetting.ONE) } },
                        label = { Text("1") },
                    )
                    FilterChip(
                        selected = settings.bFrames == BFrameSetting.TWO,
                        onClick = { viewModel.update { it.copy(bFrames = BFrameSetting.TWO) } },
                        label = { Text("2") },
                    )
                }
                }
                Text("HDR")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = settings.hdrMode == HdrMode.KEEP,
                        onClick = { viewModel.update { it.copy(hdrMode = HdrMode.KEEP) } },
                        label = { Text("Keep") },
                    )
                    FilterChip(
                        selected = settings.hdrMode == HdrMode.TONE_MAP,
                        onClick = { viewModel.update { it.copy(hdrMode = HdrMode.TONE_MAP) } },
                        label = { Text("Tone-map to SDR") },
                    )
                }
                Text(
                    if (settings.engine == EncodeEngine.MEDIA3) {
                        "Tone-map uses OpenGL on the device. Pixel 10 still tone-maps H.264/HEVC automatically."
                    } else {
                        "Tone-map writes Rec.709 SDR tags. Full HDR mapping depends on the FFmpeg build."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                }
                Text("Audio")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AudioOption.entries
                        .filter { option -> !audioOnly || option != AudioOption.MUTE }
                        .forEach { option ->
                        FilterChip(
                            selected = settings.audio == option,
                            onClick = { viewModel.update { it.copy(audio = option) } },
                            label = { Text(audioLabel(option, settings.usesWebm())) },
                        )
                    }
                }
                if (settings.audio != AudioOption.MUTE) {
                    Text("Volume  ${settings.audioVolumePercent}%")
                    Slider(
                        value = settings.audioVolumePercent.toFloat(),
                        onValueChange = { value ->
                            viewModel.update { it.copy(audioVolumePercent = (value / 10f).toInt() * 10) }
                        },
                        valueRange = 50f..200f,
                        steps = 14,
                    )
                }
                if (settings.engine == EncodeEngine.FFMPEG) {
                    if (!settings.usesWebm()) {
                    FilterChip(
                        selected = settings.fastStart,
                        onClick = { viewModel.update { it.copy(fastStart = !it.fastStart) } },
                        label = { Text(if (settings.fastStart) "Fast start on" else "Fast start off") },
                    )
                    }
                    if (!audioOnly && !settings.usesWebm()) {
                        FilterChip(
                            selected = settings.preferHardware,
                            onClick = { viewModel.update { it.copy(preferHardware = !it.preferHardware) } },
                            label = { Text(if (settings.preferHardware) "Hardware encoder on" else "Hardware encoder off") },
                        )
                    }
                    Text("Extra FFmpeg args")
                    Text(
                        "Appended after the built-in flags. Type them yourself or describe what you want and let Gemini write the flags. Video stays on this device.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = ui.aiPrompt,
                        onValueChange = viewModel::setAiPrompt,
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2,
                        label = { Text("Describe the extra change") },
                        placeholder = { Text("e.g. flip horizontally and boost contrast a little") },
                    )
                    Button(
                        onClick = viewModel::generateExtraArgs,
                        enabled = !ui.aiBusy,
                    ) {
                        if (ui.aiBusy) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp).padding(end = 8.dp),
                                strokeWidth = 2.dp,
                            )
                        }
                        Text(if (ui.hasGeminiKey) "Generate extra args" else "Generate extra args (add key in Settings)")
                    }
                    OutlinedTextField(
                        value = settings.ffmpegExtraArgs,
                        onValueChange = { value -> viewModel.update { it.copy(ffmpegExtraArgs = value) } },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2,
                        label = { Text("Extra FFmpeg args") },
                        placeholder = { Text("-vf hflip") },
                    )
                    if (ui.aiMessage != null) {
                        Text(ui.aiMessage!!, style = MaterialTheme.typography.bodySmall)
                    }
                    if (ui.extraArgsError != null) {
                        Text(
                            ui.extraArgsError!!,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
            if (settings.engine == EncodeEngine.FFMPEG) {
                Text("FFmpeg command", style = MaterialTheme.typography.titleMedium)
                Text(
                    "This is the command that will run. INPUT and OUTPUT are this job’s files and cannot be pointed elsewhere. Edit anything else, then start.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = ui.commandTemplate,
                    onValueChange = viewModel::setCommandTemplate,
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 4,
                    label = { Text("Command template") },
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                )
                if (ui.commandCustomized) {
                    Text(
                        "Using your edited command. Preset and advanced chips will not change it until you reset.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(onClick = viewModel::resetCommandTemplate) {
                        Text("Reset to generated command")
                    }
                }
                if (!ui.advancedOpen && ui.extraArgsError != null) {
                    Text(
                        ui.extraArgsError!!,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Delete original after success")
                    Text(
                        "Removes the source file only after the compressed copy is saved. Gallery files may be blocked by Android.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = ui.deleteSourceAfter, onCheckedChange = viewModel::setDeleteSourceAfter)
            }
            Button(
                modifier = Modifier.fillMaxWidth(),
                enabled = job != null && job.sourceUri.isNotBlank(),
                onClick = { viewModel.start(context) { onStarted(jobIdOr(job)) } },
            ) {
                Text(if (ui.queueBusy) "Add to queue" else "Start compression")
            }
        }
    }
}

@Composable
private fun Media3ClipControls(
    durationMs: Long,
    startMs: Long,
    endMs: Long?,
    onStart: (Long) -> Unit,
    onEnd: (Long?) -> Unit,
    onClear: () -> Unit,
) {
    val window = Media3EncodePlanner.clipWindow(
        EncodeSettings(clipStartMs = startMs, clipEndMs = endMs),
        durationMs,
    )
    val start = window.startMs
    val end = window.endMs ?: durationMs
    Text("Clip", style = MaterialTheme.typography.titleMedium)
    Text(
        "Exports only this range. Leave both ends at the edges for the whole video.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (durationMs > 0L) {
        Text("Start  ${formatDuration(start)}")
        Slider(
            value = start.toFloat().coerceIn(0f, durationMs.toFloat()),
            onValueChange = { value -> onStart(value.toLong().coerceIn(0L, durationMs)) },
            valueRange = 0f..durationMs.toFloat(),
        )
        Text("End  ${formatDuration(if (window.endMs == null) durationMs else end)}")
        Slider(
            value = end.toFloat().coerceIn(0f, durationMs.toFloat()),
            onValueChange = { value ->
                val next = value.toLong().coerceIn(0L, durationMs)
                onEnd(if (next >= durationMs) null else next)
            },
            valueRange = 0f..durationMs.toFloat(),
        )
        if (window.active) {
            Text(
                "Keeps ${formatDuration(window.durationMs(durationMs))} of ${formatDuration(durationMs)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = onClear) { Text("Use whole video") }
        }
    } else {
        var startText by rememberSaveable { mutableStateOf(if (startMs > 0) formatDuration(startMs) else "") }
        var endText by rememberSaveable { mutableStateOf(endMs?.let { formatDuration(it) }.orEmpty()) }
        OutlinedTextField(
            value = startText,
            onValueChange = { value ->
                startText = value
                if (value.isBlank()) onStart(0L) else parseDurationMs(value)?.let(onStart)
            },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text("Start") },
            placeholder = { Text("0:00") },
        )
        OutlinedTextField(
            value = endText,
            onValueChange = { value ->
                endText = value
                if (value.isBlank()) onEnd(null) else parseDurationMs(value)?.let(onEnd)
            },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text("End") },
            placeholder = { Text("End of video") },
        )
        if (startMs > 0L || endMs != null) {
            TextButton(onClick = {
                startText = ""
                endText = ""
                onClear()
            }) { Text("Use whole video") }
        }
    }
}

private fun jobIdOr(job: com.androidcompress.app.data.CompressJob?) = job?.id.orEmpty()

private fun presetLabel(preset: Preset) = when (preset) {
    Preset.SMALLER -> "Smaller"
    Preset.BALANCED -> "Balanced"
    Preset.HIGHER -> "Higher quality"
}
