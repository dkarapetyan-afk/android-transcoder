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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.androidcompress.app.R
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
import com.androidcompress.app.ui.audioLabel
import com.androidcompress.app.ui.components.AppTopBar
import com.androidcompress.app.ui.components.StatLine
import com.androidcompress.app.ui.components.VideoThumbnail
import com.androidcompress.app.ui.fpsLabel
import com.androidcompress.app.ui.heightLabel
import com.androidcompress.app.ui.presetLabel
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

    Scaffold(topBar = { AppTopBar(stringResource(R.string.compress_title), onBack = onBack) }) { padding ->
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
                StatLine(
                    stringResource(R.string.compress_source),
                    stringResource(
                        R.string.compress_source_meta,
                        formatResolution(job.width, job.height),
                        formatDuration(job.durationMs),
                        formatBytes(job.sourceBytes),
                    ),
                )
                if (job.isCombine) {
                    StatLine(
                        stringResource(
                            if (job.stillImage) R.string.compress_picture_audio else R.string.compress_video_audio,
                        ),
                        if (job.stillImage) {
                            stringResource(R.string.compress_still_held, formatDuration(job.durationMs))
                        } else {
                            stringResource(R.string.compress_video_soundtrack)
                        },
                    )
                }
                val sourceHasVideo = job.width > 0 && job.height > 0 || job.stillImage
                if (settings.engine == EncodeEngine.MEDIA3 || settings.audioOutput(sourceHasVideo) || job.isCombine) {
                    val clip = Media3EncodePlanner.clipWindow(settings, job.durationMs)
                    if (clip.active) {
                        val endLabel = clip.endMs?.let { formatDuration(it) } ?: formatDuration(job.durationMs)
                        StatLine(
                            stringResource(R.string.compress_clip),
                            stringResource(
                                R.string.compress_clip_range,
                                formatDuration(clip.startMs),
                                endLabel,
                                formatDuration(clip.durationMs(job.durationMs)),
                            ),
                        )
                    }
                }
                StatLine(
                    stringResource(R.string.compress_estimated_output),
                    stringResource(R.string.compress_estimate_bytes, formatBytes(ui.estimateBytes)),
                )
                if (ui.encoderLabel.isNotBlank()) {
                    StatLine(stringResource(R.string.compress_encoder), ui.encoderLabel)
                }
            }
            Text(stringResource(R.string.compress_preset), style = MaterialTheme.typography.titleMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Preset.entries.forEach { preset ->
                    FilterChip(
                        selected = settings.preset == preset,
                        onClick = { viewModel.applyPreset(preset) },
                        label = { Text(presetLabel(preset)) },
                    )
                }
            }
            Text(stringResource(R.string.compress_engine), style = MaterialTheme.typography.titleMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = settings.engine == EncodeEngine.FFMPEG,
                    onClick = { viewModel.update { it.copy(engine = EncodeEngine.FFMPEG) } },
                    label = { Text(stringResource(R.string.engine_ffmpeg)) },
                )
                FilterChip(
                    selected = settings.engine == EncodeEngine.MEDIA3,
                    onClick = { viewModel.update { it.copy(engine = EncodeEngine.MEDIA3) } },
                    label = { Text(stringResource(R.string.engine_media3)) },
                )
            }
            Text(
                stringResource(
                    if (settings.engine == EncodeEngine.MEDIA3) {
                        R.string.compress_engine_media3_hint
                    } else {
                        R.string.compress_engine_ffmpeg_hint
                    },
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val sourceHasVideo = job == null || job.width > 0 || job.height > 0 || job.stillImage
            val combineJob = job?.isCombine == true
            val stillImage = job?.stillImage == true
            Text(stringResource(R.string.compress_output), style = MaterialTheme.typography.titleMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (sourceHasVideo) {
                    FilterChip(
                        selected = settings.output == OutputMode.VIDEO || combineJob,
                        onClick = { viewModel.setOutput(OutputMode.VIDEO) },
                        label = { Text(stringResource(R.string.compress_output_video)) },
                    )
                }
                if (!combineJob) {
                    FilterChip(
                        selected = settings.output == OutputMode.AUDIO || !sourceHasVideo,
                        onClick = { viewModel.setOutput(OutputMode.AUDIO) },
                        label = { Text(stringResource(R.string.compress_output_audio)) },
                    )
                }
            }
            Text(stringResource(R.string.compress_container), style = MaterialTheme.typography.titleMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = settings.container == ContainerFormat.MP4,
                    onClick = { viewModel.setContainer(ContainerFormat.MP4) },
                    label = {
                        Text(
                            stringResource(
                                if (settings.audioOutput(sourceHasVideo)) {
                                    R.string.container_m4a
                                } else {
                                    R.string.container_mp4
                                },
                            ),
                        )
                    },
                )
                FilterChip(
                    selected = settings.container == ContainerFormat.WEBM,
                    onClick = { viewModel.setContainer(ContainerFormat.WEBM) },
                    label = { Text(stringResource(R.string.container_webm)) },
                )
            }
            Text(
                stringResource(
                    when {
                        combineJob && stillImage && settings.usesWebm() ->
                            R.string.compress_container_still_webm
                        combineJob && stillImage ->
                            R.string.compress_container_still_mp4
                        combineJob && settings.usesWebm() ->
                            R.string.compress_container_combine_webm
                        combineJob ->
                            R.string.compress_container_combine_mp4
                        settings.audioOutput(sourceHasVideo) && settings.usesWebm() ->
                            R.string.compress_container_audio_webm
                        settings.audioOutput(sourceHasVideo) ->
                            R.string.compress_container_audio_m4a
                        settings.usesWebm() ->
                            R.string.compress_container_webm
                        else ->
                            R.string.compress_container_mp4
                    },
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (settings.engine == EncodeEngine.MEDIA3 || settings.audioOutput(sourceHasVideo) || combineJob) {
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
                Text(
                    stringResource(
                        if (ui.advancedOpen) R.string.compress_hide_advanced else R.string.compress_advanced,
                    ),
                )
            }
            if (ui.advancedOpen) {
                val audioOnly = settings.audioOutput(sourceHasVideo)
                if (!audioOnly) {
                Text(stringResource(R.string.compress_resolution_cap))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(null, 2160, 1440, 1080, 720, 480, 360).forEach { height ->
                        FilterChip(
                            selected = settings.maxHeight == height,
                            onClick = { viewModel.update { it.copy(maxHeight = height) } },
                            label = { Text(heightLabel(height)) },
                        )
                    }
                }
                Text(stringResource(R.string.compress_codec))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (settings.usesWebm()) {
                        FilterChip(
                            selected = settings.codec == VideoCodec.VP9,
                            onClick = { viewModel.update { it.copy(codec = VideoCodec.VP9) } },
                            label = { Text(stringResource(R.string.codec_vp9)) },
                        )
                        FilterChip(
                            selected = settings.codec == VideoCodec.VP8,
                            onClick = { viewModel.update { it.copy(codec = VideoCodec.VP8) } },
                            label = { Text(stringResource(R.string.codec_vp8)) },
                        )
                    } else {
                        FilterChip(
                            selected = settings.codec == VideoCodec.H264,
                            onClick = { viewModel.update { it.copy(codec = VideoCodec.H264) } },
                            label = { Text(stringResource(R.string.codec_h264)) },
                        )
                        if (ui.capabilities.hasHevcMediaCodec) {
                            FilterChip(
                                selected = settings.codec == VideoCodec.HEVC,
                                onClick = { viewModel.update { it.copy(codec = VideoCodec.HEVC) } },
                                label = { Text(stringResource(R.string.codec_hevc)) },
                            )
                        }
                    }
                    if (ui.capabilities.av1Available(settings.engine)) {
                        FilterChip(
                            selected = settings.codec == VideoCodec.AV1,
                            onClick = { viewModel.update { it.copy(codec = VideoCodec.AV1) } },
                            label = { Text(stringResource(R.string.codec_av1)) },
                        )
                    }
                }
                if (settings.codec == VideoCodec.AV1) {
                    Text(
                        stringResource(
                            if (settings.engine == EncodeEngine.MEDIA3) {
                                R.string.compress_av1_hint_media3
                            } else {
                                R.string.compress_av1_hint_ffmpeg
                            },
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(stringResource(R.string.compress_quality_kbps, settings.videoBitrateKbps))
                Slider(
                    value = settings.videoBitrateKbps.toFloat(),
                    onValueChange = { value -> viewModel.update { it.copy(videoBitrateKbps = value.toInt()) } },
                    valueRange = 400f..20000f,
                )
                Text(stringResource(R.string.compress_bitrate_mode))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = settings.bitrateMode == BitrateMode.CBR,
                        onClick = { viewModel.update { it.copy(bitrateMode = BitrateMode.CBR) } },
                        label = { Text(stringResource(R.string.bitrate_cbr)) },
                    )
                    FilterChip(
                        selected = settings.bitrateMode == BitrateMode.VBR,
                        onClick = { viewModel.update { it.copy(bitrateMode = BitrateMode.VBR) } },
                        label = { Text(stringResource(R.string.bitrate_vbr)) },
                    )
                }
                Text(
                    stringResource(R.string.compress_bitrate_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(stringResource(R.string.compress_frame_rate))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(null, 60, 30, 24).forEach { fps ->
                        FilterChip(
                            selected = settings.fpsCap == fps,
                            onClick = { viewModel.update { it.copy(fpsCap = fps) } },
                            label = { Text(fpsLabel(fps)) },
                        )
                    }
                }
                Text(stringResource(R.string.compress_keyframe))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = settings.keyframeInterval == KeyframeInterval.AUTO,
                        onClick = { viewModel.update { it.copy(keyframeInterval = KeyframeInterval.AUTO) } },
                        label = { Text(stringResource(R.string.option_auto)) },
                    )
                    FilterChip(
                        selected = settings.keyframeInterval == KeyframeInterval.SEC_1,
                        onClick = { viewModel.update { it.copy(keyframeInterval = KeyframeInterval.SEC_1) } },
                        label = { Text(stringResource(R.string.keyframe_1s)) },
                    )
                    FilterChip(
                        selected = settings.keyframeInterval == KeyframeInterval.SEC_2,
                        onClick = { viewModel.update { it.copy(keyframeInterval = KeyframeInterval.SEC_2) } },
                        label = { Text(stringResource(R.string.keyframe_2s)) },
                    )
                    FilterChip(
                        selected = settings.keyframeInterval == KeyframeInterval.SEC_5,
                        onClick = { viewModel.update { it.copy(keyframeInterval = KeyframeInterval.SEC_5) } },
                        label = { Text(stringResource(R.string.keyframe_5s)) },
                    )
                }
                if (!settings.usesWebm() && settings.codec == VideoCodec.H264) {
                    Text(stringResource(R.string.compress_h264_profile))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = settings.h264Profile == H264Profile.AUTO,
                            onClick = { viewModel.update { it.copy(h264Profile = H264Profile.AUTO) } },
                            label = { Text(stringResource(R.string.option_auto)) },
                        )
                        FilterChip(
                            selected = settings.h264Profile == H264Profile.BASELINE,
                            onClick = { viewModel.update { it.copy(h264Profile = H264Profile.BASELINE) } },
                            label = { Text(stringResource(R.string.h264_baseline)) },
                        )
                        FilterChip(
                            selected = settings.h264Profile == H264Profile.MAIN,
                            onClick = { viewModel.update { it.copy(h264Profile = H264Profile.MAIN) } },
                            label = { Text(stringResource(R.string.h264_main)) },
                        )
                        FilterChip(
                            selected = settings.h264Profile == H264Profile.HIGH,
                            onClick = { viewModel.update { it.copy(h264Profile = H264Profile.HIGH) } },
                            label = { Text(stringResource(R.string.h264_high)) },
                        )
                    }
                    Text(
                        stringResource(
                            if (settings.engine == EncodeEngine.MEDIA3) {
                                R.string.compress_h264_hint_media3
                            } else {
                                R.string.compress_h264_hint_ffmpeg
                            },
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (!settings.usesWebm() && settings.codec != VideoCodec.AV1) {
                Text(stringResource(R.string.compress_bframes))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = settings.bFrames == BFrameSetting.AUTO,
                        onClick = { viewModel.update { it.copy(bFrames = BFrameSetting.AUTO) } },
                        label = { Text(stringResource(R.string.option_auto)) },
                    )
                    FilterChip(
                        selected = settings.bFrames == BFrameSetting.NONE,
                        onClick = { viewModel.update { it.copy(bFrames = BFrameSetting.NONE) } },
                        label = { Text(stringResource(R.string.option_off)) },
                    )
                    FilterChip(
                        selected = settings.bFrames == BFrameSetting.ONE,
                        onClick = { viewModel.update { it.copy(bFrames = BFrameSetting.ONE) } },
                        label = { Text(stringResource(R.string.bframes_one)) },
                    )
                    FilterChip(
                        selected = settings.bFrames == BFrameSetting.TWO,
                        onClick = { viewModel.update { it.copy(bFrames = BFrameSetting.TWO) } },
                        label = { Text(stringResource(R.string.bframes_two)) },
                    )
                }
                }
                Text(stringResource(R.string.compress_hdr))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = settings.hdrMode == HdrMode.KEEP,
                        onClick = { viewModel.update { it.copy(hdrMode = HdrMode.KEEP) } },
                        label = { Text(stringResource(R.string.option_keep)) },
                    )
                    FilterChip(
                        selected = settings.hdrMode == HdrMode.TONE_MAP,
                        onClick = { viewModel.update { it.copy(hdrMode = HdrMode.TONE_MAP) } },
                        label = { Text(stringResource(R.string.hdr_tone_map)) },
                    )
                }
                Text(
                    stringResource(
                        if (settings.engine == EncodeEngine.MEDIA3) {
                            R.string.compress_hdr_hint_media3
                        } else {
                            R.string.compress_hdr_hint_ffmpeg
                        },
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                }
                Text(stringResource(R.string.compress_audio))
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
                    Text(stringResource(R.string.compress_volume_percent, settings.audioVolumePercent))
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
                        label = {
                            Text(
                                stringResource(
                                    if (settings.fastStart) {
                                        R.string.compress_fast_start_on
                                    } else {
                                        R.string.compress_fast_start_off
                                    },
                                ),
                            )
                        },
                    )
                    }
                    if (!audioOnly && (!settings.usesWebm() || settings.codec == VideoCodec.AV1)) {
                        FilterChip(
                            selected = settings.preferHardware,
                            onClick = { viewModel.update { it.copy(preferHardware = !it.preferHardware) } },
                            label = {
                                Text(
                                    stringResource(
                                        if (settings.preferHardware) {
                                            R.string.compress_hw_on
                                        } else {
                                            R.string.compress_hw_off
                                        },
                                    ),
                                )
                            },
                        )
                    }
                    Text(stringResource(R.string.compress_extra_args))
                    Text(
                        stringResource(R.string.compress_extra_args_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = ui.aiPrompt,
                        onValueChange = viewModel::setAiPrompt,
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2,
                        label = { Text(stringResource(R.string.compress_ai_prompt_label)) },
                        placeholder = { Text(stringResource(R.string.compress_ai_prompt_placeholder)) },
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
                        Text(
                            stringResource(
                                if (ui.hasGeminiKey) {
                                    R.string.compress_generate_extra_args
                                } else {
                                    R.string.compress_generate_extra_args_no_key
                                },
                            ),
                        )
                    }
                    OutlinedTextField(
                        value = settings.ffmpegExtraArgs,
                        onValueChange = { value -> viewModel.update { it.copy(ffmpegExtraArgs = value) } },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2,
                        label = { Text(stringResource(R.string.compress_extra_args)) },
                        placeholder = { Text(stringResource(R.string.compress_extra_args_placeholder)) },
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
                Text(stringResource(R.string.compress_command_title), style = MaterialTheme.typography.titleMedium)
                Text(
                    stringResource(
                        if (combineJob) R.string.compress_command_hint_combine else R.string.compress_command_hint,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = ui.commandTemplate,
                    onValueChange = viewModel::setCommandTemplate,
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 4,
                    label = { Text(stringResource(R.string.compress_command_template)) },
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                )
                if (ui.commandCustomized) {
                    Text(
                        stringResource(R.string.compress_command_customized),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(onClick = viewModel::resetCommandTemplate) {
                        Text(stringResource(R.string.compress_reset_command))
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
                    Text(stringResource(R.string.compress_delete_original))
                    Text(
                        stringResource(R.string.compress_delete_original_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = ui.deleteSourceAfter, onCheckedChange = viewModel::setDeleteSourceAfter)
            }
            Button(
                modifier = Modifier.fillMaxWidth(),
                enabled = job != null && job.sourceUri.isNotBlank() && (!job.isCombine || job.audioUri.isNotBlank()),
                onClick = { viewModel.start(context) { onStarted(jobIdOr(job)) } },
            ) {
                Text(
                    stringResource(
                        if (ui.queueBusy) R.string.compress_add_to_queue else R.string.compress_start,
                    ),
                )
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
    Text(stringResource(R.string.compress_clip), style = MaterialTheme.typography.titleMedium)
    Text(
        stringResource(R.string.compress_clip_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (durationMs > 0L) {
        Text(stringResource(R.string.compress_clip_start, formatDuration(start)))
        Slider(
            value = start.toFloat().coerceIn(0f, durationMs.toFloat()),
            onValueChange = { value -> onStart(value.toLong().coerceIn(0L, durationMs)) },
            valueRange = 0f..durationMs.toFloat(),
        )
        Text(
            stringResource(
                R.string.compress_clip_end,
                formatDuration(if (window.endMs == null) durationMs else end),
            ),
        )
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
                stringResource(
                    R.string.compress_clip_keeps,
                    formatDuration(window.durationMs(durationMs)),
                    formatDuration(durationMs),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = onClear) { Text(stringResource(R.string.compress_use_whole_video)) }
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
            label = { Text(stringResource(R.string.compress_clip_start_label)) },
            placeholder = { Text(stringResource(R.string.compress_clip_start_placeholder)) },
        )
        OutlinedTextField(
            value = endText,
            onValueChange = { value ->
                endText = value
                if (value.isBlank()) onEnd(null) else parseDurationMs(value)?.let(onEnd)
            },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text(stringResource(R.string.compress_clip_end_label)) },
            placeholder = { Text(stringResource(R.string.compress_clip_end_placeholder)) },
        )
        if (startMs > 0L || endMs != null) {
            TextButton(onClick = {
                startText = ""
                endText = ""
                onClear()
            }) { Text(stringResource(R.string.compress_use_whole_video)) }
        }
    }
}

private fun jobIdOr(job: com.androidcompress.app.data.CompressJob?) = job?.id.orEmpty()
