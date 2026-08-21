package com.androidcompress.app.ui

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.androidcompress.app.R
import com.androidcompress.app.data.AudioOption
import com.androidcompress.app.data.JobStatus
import com.androidcompress.app.data.ContainerFormat
import com.androidcompress.app.data.Preset
import com.androidcompress.app.data.TargetSizePreset
import com.androidcompress.app.encode.BatchRecipe

@StringRes
fun JobStatus.labelRes(): Int = when (this) {
    JobStatus.DRAFT -> R.string.status_draft
    JobStatus.RECORDING -> R.string.status_recording
    JobStatus.READY -> R.string.status_ready
    JobStatus.QUEUED -> R.string.status_queued
    JobStatus.RUNNING -> R.string.status_running
    JobStatus.SUCCEEDED -> R.string.status_succeeded
    JobStatus.FAILED -> R.string.status_failed
    JobStatus.CANCELLED -> R.string.status_cancelled
}

@Composable
fun JobStatus.label(): String = stringResource(labelRes())

@Composable
fun presetLabel(preset: Preset): String = stringResource(
    when (preset) {
        Preset.SMALLER -> R.string.preset_smaller
        Preset.BALANCED -> R.string.preset_balanced
        Preset.HIGHER -> R.string.preset_higher
    },
)

@Composable
fun batchRecipeLabel(recipe: BatchRecipe): String = when {
    recipe.container == ContainerFormat.WEBM && recipe.preset == Preset.SMALLER ->
        stringResource(R.string.batch_webm_720)
    recipe.container == ContainerFormat.WEBM && recipe.preset == Preset.BALANCED ->
        stringResource(R.string.batch_webm_1080)
    recipe.preset == Preset.SMALLER -> stringResource(R.string.batch_smaller)
    recipe.preset == Preset.BALANCED -> stringResource(R.string.batch_balanced)
    recipe.preset == Preset.HIGHER -> stringResource(R.string.batch_higher)
    else -> presetLabel(recipe.preset)
}

@Composable
fun targetSizeLabel(preset: TargetSizePreset): String = stringResource(
    when (preset) {
        TargetSizePreset.OFF -> R.string.option_off
        TargetSizePreset.DISCORD -> R.string.compress_fit_discord
        TargetSizePreset.WHATSAPP -> R.string.compress_fit_whatsapp
        TargetSizePreset.WHATSAPP_64 -> R.string.compress_fit_whatsapp_64
        TargetSizePreset.GMAIL -> R.string.compress_fit_gmail
        TargetSizePreset.CUSTOM -> R.string.compress_fit_custom
    },
)

@Composable
fun heightLabel(value: Int?): String = when (value) {
    null -> stringResource(R.string.height_original)
    else -> stringResource(R.string.height_p, value)
}

@Composable
fun fpsLabel(fps: Int?): String =
    if (fps == null) stringResource(R.string.fps_original) else fps.toString()

@Composable
fun audioLabel(option: AudioOption, webm: Boolean = false): String {
    val codec = stringResource(if (webm) R.string.codec_opus else R.string.codec_aac)
    return when (option) {
        AudioOption.COPY -> stringResource(R.string.audio_keep_original)
        AudioOption.AAC_64 -> stringResource(R.string.audio_kbps, codec, 64)
        AudioOption.AAC_96 -> stringResource(R.string.audio_kbps, codec, 96)
        AudioOption.AAC_128 -> stringResource(R.string.audio_kbps, codec, 128)
        AudioOption.AAC_192 -> stringResource(R.string.audio_kbps, codec, 192)
        AudioOption.MUTE -> stringResource(R.string.audio_mute)
    }
}
