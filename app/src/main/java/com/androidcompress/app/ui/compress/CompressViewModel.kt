package com.androidcompress.app.ui.compress

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.androidcompress.app.R
import com.androidcompress.app.ai.GeminiFfmpegAssistant
import com.androidcompress.app.data.AudioOption
import com.androidcompress.app.data.BitrateMode
import com.androidcompress.app.data.CompressJob
import com.androidcompress.app.data.EncodeEngine
import com.androidcompress.app.data.EncodeSettings
import com.androidcompress.app.data.EncoderCapabilities
import com.androidcompress.app.data.ContainerFormat
import com.androidcompress.app.data.OutputMode
import com.androidcompress.app.data.Preset
import com.androidcompress.app.data.SettingsJson
import com.androidcompress.app.data.SourceVideo
import com.androidcompress.app.data.TargetSizePreset
import com.androidcompress.app.data.VideoCodec
import com.androidcompress.app.data.audioOutput
import com.androidcompress.app.data.effectiveAudio
import com.androidcompress.app.data.usesWebm
import com.androidcompress.app.data.withContainer
import com.androidcompress.app.data.withTargetPreset
import com.androidcompress.app.util.parseMegabytesToBytes
import com.androidcompress.app.di.AppContainer
import com.androidcompress.app.encode.CompressService
import com.androidcompress.app.encode.ExtraArgsSanitizer
import com.androidcompress.app.encode.FfmpegCommandBuilder
import com.androidcompress.app.encode.FfmpegCommandTemplate
import com.androidcompress.app.encode.Media3EncodePlanner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class CompressUiState(
    val job: CompressJob? = null,
    val settings: EncodeSettings = EncodeSettings.forPreset(Preset.BALANCED),
    val capabilities: EncoderCapabilities = EncoderCapabilities(),
    val advancedOpen: Boolean = false,
    val estimateBytes: Long = 0,
    val encoderLabel: String = "",
    val queueBusy: Boolean = false,
    val deleteSourceAfter: Boolean = false,
    val extraArgsError: String? = null,
    val aiPrompt: String = "",
    val aiBusy: Boolean = false,
    val aiMessage: String? = null,
    val hasGeminiKey: Boolean = false,
    val generatedCommand: String = "",
    val commandTemplate: String = "",
    val commandCustomized: Boolean = false,
    val plannedVideoBitrateKbps: Int = 0,
    val plannedAudioBitrateKbps: Int = 0,
    val twoPassActive: Boolean = false,
)

class CompressViewModel(
    private val container: AppContainer,
    private val jobId: String,
) : ViewModel() {

    private val settings = MutableStateFlow(EncodeSettings.forPreset(Preset.BALANCED))
    private val caps = MutableStateFlow(EncoderCapabilities())
    private val advanced = MutableStateFlow(false)
    private val deleteSourceAfter = MutableStateFlow(false)
    private val extraArgsError = MutableStateFlow<String?>(null)
    private val aiPrompt = MutableStateFlow("")
    private val aiBusy = MutableStateFlow(false)
    private val aiMessage = MutableStateFlow<String?>(null)
    private val gemini = GeminiFfmpegAssistant()

    val ui = combine(
        combine(
            container.jobs.observe(jobId),
            container.jobs.observeActive(),
            settings,
        ) { job, active, enc -> Triple(job, active, enc) },
        combine(caps, advanced, deleteSourceAfter) { cap, open, deleteSource ->
            Triple(cap, open, deleteSource)
        },
        combine(extraArgsError, aiPrompt, aiBusy, aiMessage) { err, prompt, busy, message ->
            AiSlice(err, prompt, busy, message)
        },
        container.prefs.settings.map { it.geminiApiKey.isNotBlank() },
    ) { left, right, ai, hasKey ->
        val (job, active, enc) = left
        val (cap, open, deleteSource) = right
        val realSource = job.toSource()
        val source = realSource ?: previewSource()
        val plan = if (enc.engine == EncodeEngine.FFMPEG) {
            FfmpegCommandBuilder.build(
                FfmpegCommandTemplate.INPUT,
                FfmpegCommandTemplate.OUTPUT,
                enc,
                source,
                cap,
                audioInput = if (source.isCombine) FfmpegCommandTemplate.AUDIO else null,
            )
        } else {
            null
        }
        val generated = plan?.let { FfmpegCommandTemplate.fromArgs(it.args) }.orEmpty()
        val override = enc.ffmpegCommandOverride
        val customized = override.isNotBlank() && override.trim() != generated.trim()
        val media3Spec = if (enc.engine == EncodeEngine.MEDIA3) {
            Media3EncodePlanner.plan(enc, source)
        } else {
            null
        }
        val app = container.appContext
        val encoderLabel = when {
            job == null || job.sourceUri.isBlank() -> ""
            enc.engine == EncodeEngine.MEDIA3 -> media3Spec?.encoderLabel.orEmpty()
            enc.audioOutput(source.hasVideo) -> when {
                enc.effectiveAudio(source.hasVideo) == AudioOption.COPY ->
                    app.getString(R.string.encoder_ffmpeg_audio_copy)
                enc.usesWebm() -> app.getString(R.string.encoder_ffmpeg_opus)
                else -> app.getString(R.string.encoder_ffmpeg_aac)
            }
            else -> plan?.videoEncoder.orEmpty()
        }
        CompressUiState(
            job = job,
            settings = enc,
            capabilities = cap,
            advancedOpen = open,
            estimateBytes = realSource?.let { src ->
                FfmpegCommandBuilder.estimateOutputBytes(src, enc)
            } ?: 0,
            encoderLabel = encoderLabel,
            queueBusy = active.any { it.id != jobId },
            deleteSourceAfter = deleteSource,
            extraArgsError = ai.error,
            aiPrompt = ai.prompt,
            aiBusy = ai.busy,
            aiMessage = ai.message,
            hasGeminiKey = hasKey,
            generatedCommand = generated,
            commandTemplate = if (customized) override else generated,
            commandCustomized = customized,
            plannedVideoBitrateKbps = plan?.videoBitrateKbps
                ?: media3Spec?.videoBitrateBps?.div(1000)
                ?: 0,
            plannedAudioBitrateKbps = plan?.audioBitrateKbps
                ?: media3Spec?.audioBitrateBps?.div(1000)
                ?: 0,
            twoPassActive = plan?.firstPassArgs != null,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CompressUiState())

    init {
        viewModelScope.launch {
            val prefs = container.prefs.current()
            val job = container.jobs.get(jobId)
            val loaded = when {
                prefs.rememberAdvanced && prefs.lastSettingsJson != null -> SettingsJson.decode(prefs.lastSettingsJson)
                job != null -> SettingsJson.decode(job.settingsJson)
                else -> EncodeSettings.forPreset(prefs.defaultPreset, prefs.defaultEngine)
            }
            settings.value = when {
                job != null && job.isCombine -> loaded.copy(
                    output = OutputMode.VIDEO,
                    audio = if (loaded.audio == AudioOption.MUTE) AudioOption.AAC_128 else loaded.audio,
                )
                job != null && job.width <= 0 && job.height <= 0 -> loaded.copy(
                    output = OutputMode.AUDIO,
                    audio = if (loaded.audio == AudioOption.MUTE) AudioOption.AAC_128 else loaded.audio,
                )
                else -> loaded
            }
            deleteSourceAfter.value = job?.deleteSourceAfter ?: prefs.deleteOriginalAfterEncode
            caps.value = container.encoderCapabilities()
        }
    }

    fun setDeleteSourceAfter(value: Boolean) {
        deleteSourceAfter.value = value
    }

    fun setClipStartMs(startMs: Long) {
        update { current ->
            val start = startMs.coerceAtLeast(0L)
            val end = current.clipEndMs
            if (end != null && end <= start) {
                current.copy(clipStartMs = start, clipEndMs = start + Media3EncodePlanner.MIN_CLIP_MS)
            } else {
                current.copy(clipStartMs = start)
            }
        }
    }

    fun setClipEndMs(endMs: Long?) {
        update { current ->
            val end = endMs?.takeIf { it > 0L }
            if (end != null && end <= current.clipStartMs) {
                current.copy(
                    clipStartMs = (end - Media3EncodePlanner.MIN_CLIP_MS).coerceAtLeast(0L),
                    clipEndMs = end,
                )
            } else {
                current.copy(clipEndMs = end)
            }
        }
    }

    fun clearClip() {
        update { it.copy(clipStartMs = 0L, clipEndMs = null) }
    }

    fun setOutput(mode: OutputMode) {
        if (ui.value.job?.isCombine == true && mode != OutputMode.VIDEO) return
        update { current ->
            current.copy(
                output = mode,
                audio = if (mode == OutputMode.AUDIO && current.audio == AudioOption.MUTE) {
                    AudioOption.AAC_128
                } else {
                    current.audio
                },
            )
        }
    }

    fun setContainer(format: ContainerFormat) {
        update { it.withContainer(format) }
    }

    fun applyTargetPreset(preset: TargetSizePreset) {
        update { it.withTargetPreset(preset) }
    }

    fun setCustomTargetMegabytes(raw: String) {
        val bytes = parseMegabytesToBytes(raw) ?: return
        if (bytes !in TargetSizePreset.MIN_BYTES..TargetSizePreset.MAX_BYTES) return
        update {
            it.copy(
                targetSizePreset = TargetSizePreset.CUSTOM,
                targetSizeBytes = bytes,
                bitrateMode = BitrateMode.CBR,
            )
        }
    }

    fun applyPreset(preset: Preset) {
        val previous = settings.value
        settings.value = EncodeSettings.forPreset(preset, previous.engine).copy(
            bitrateMode = previous.bitrateMode,
            keyframeInterval = previous.keyframeInterval,
            h264Profile = previous.h264Profile,
            hdrMode = previous.hdrMode,
            audioVolumePercent = previous.audioVolumePercent,
            fastStart = previous.fastStart,
            bFrames = previous.bFrames,
            ffmpegExtraArgs = previous.ffmpegExtraArgs,
            ffmpegCommandOverride = previous.ffmpegCommandOverride,
            twoPass = previous.twoPass,
            grayscale = previous.grayscale,
            captions = previous.captions,
            burnCaptions = previous.burnCaptions,
            clipStartMs = previous.clipStartMs,
            clipEndMs = previous.clipEndMs,
            output = previous.output,
            targetSizePreset = TargetSizePreset.OFF,
            targetSizeBytes = null,
        ).withContainer(previous.container)
        persist()
    }

    fun update(transform: (EncodeSettings) -> EncodeSettings) {
        settings.value = transform(settings.value)
        extraArgsError.value = null
        persist()
    }

    fun setAiPrompt(value: String) {
        aiPrompt.value = value
    }

    fun setCommandTemplate(value: String) {
        val generated = ui.value.generatedCommand
        val override = if (value.trim() == generated.trim()) "" else value
        settings.value = settings.value.copy(ffmpegCommandOverride = override)
        extraArgsError.value = null
        persist()
    }

    fun resetCommandTemplate() {
        settings.value = settings.value.copy(ffmpegCommandOverride = "")
        extraArgsError.value = null
        persist()
    }

    fun generateExtraArgs() {
        viewModelScope.launch {
            if (aiBusy.value) return@launch
            aiBusy.value = true
            aiMessage.value = null
            extraArgsError.value = null
            val key = container.prefs.current().geminiApiKey
            val enc = settings.value
            val job = container.jobs.get(jobId)
            val source = job.toSource()
            val encoder = when {
                enc.audioOutput(source?.hasVideo ?: true) ->
                    if (enc.usesWebm()) "libopus" else "aac"
                source != null -> FfmpegCommandBuilder.selectVideoEncoder(enc, caps.value)
                else -> ""
            }
            val command = currentFfmpegCommand(enc, source, caps.value)
            val result = gemini.suggest(key, aiPrompt.value, enc, source, encoder, command)
            result.fold(
                onSuccess = { suggestion ->
                    if (suggestion.args.isBlank()) {
                        aiMessage.value = suggestion.note.ifBlank {
                            container.appContext.getString(R.string.gemini_no_extra_args)
                        }
                    } else {
                        settings.value = enc.copy(
                            ffmpegExtraArgs = suggestion.args,
                            ffmpegCommandOverride = "",
                        )
                        persist()
                        aiMessage.value = suggestion.note.ifBlank {
                            container.appContext.getString(R.string.gemini_extra_args_filled)
                        }
                    }
                },
                onFailure = { error ->
                    extraArgsError.value = error.message
                        ?: container.appContext.getString(R.string.error_generate_extra_args)
                },
            )
            aiBusy.value = false
        }
    }

    fun toggleAdvanced() {
        advanced.value = !advanced.value
    }

    fun start(context: Context, onStarted: () -> Unit) {
        viewModelScope.launch {
            val enc = settings.value
            if (enc.engine == EncodeEngine.FFMPEG) {
                if (enc.ffmpegCommandOverride.isNotBlank()) {
                    val parsed = FfmpegCommandTemplate.parse(enc.ffmpegCommandOverride)
                    if (!parsed.isValid) {
                        extraArgsError.value = parsed.error
                        return@launch
                    }
                    settings.value = enc.copy(ffmpegCommandOverride = parsed.canonical)
                } else if (enc.ffmpegExtraArgs.isNotBlank()) {
                    val parsed = ExtraArgsSanitizer.parse(enc.ffmpegExtraArgs)
                    if (!parsed.isValid) {
                        extraArgsError.value = parsed.error
                        return@launch
                    }
                    if (parsed.canonical != enc.ffmpegExtraArgs.trim()) {
                        settings.value = enc.copy(ffmpegExtraArgs = parsed.canonical)
                    }
                }
            }
            val job = container.jobs.get(jobId) ?: return@launch
            val json = SettingsJson.encode(settings.value)
            container.prefs.setLastSettingsJson(json)
            container.jobs.upsert(job.copy(settingsJson = json, deleteSourceAfter = deleteSourceAfter.value))
            container.jobs.enqueue(job.id, json)
            CompressService.enqueue(context, jobId)
            onStarted()
        }
    }

    fun maybeAutoStart(context: Context, onStarted: () -> Unit) {
        viewModelScope.launch {
            val prefs = container.prefs.current()
            val job = container.jobs.get(jobId) ?: return@launch
            if (prefs.autoCompressAfterRecord &&
                job.type == com.androidcompress.app.data.JobType.RECORD &&
                job.status == com.androidcompress.app.data.JobStatus.READY
            ) {
                start(context, onStarted)
            }
        }
    }

    private fun currentFfmpegCommand(
        enc: EncodeSettings,
        source: SourceVideo?,
        capabilities: EncoderCapabilities,
    ): String {
        val preview = source ?: previewSource()
        val plan = FfmpegCommandBuilder.build(
            FfmpegCommandTemplate.INPUT,
            FfmpegCommandTemplate.OUTPUT,
            enc,
            preview,
            capabilities,
            audioInput = if (preview.isCombine) FfmpegCommandTemplate.AUDIO else null,
        )
        val generated = FfmpegCommandTemplate.fromArgs(plan.args)
        val override = enc.ffmpegCommandOverride
        return if (override.isNotBlank()) override else generated
    }

    private fun persist() {
        viewModelScope.launch {
            val job = container.jobs.get(jobId) ?: return@launch
            container.jobs.upsert(job.copy(settingsJson = SettingsJson.encode(settings.value)))
        }
    }
}

private fun previewSource(): SourceVideo = SourceVideo(
    uri = FfmpegCommandTemplate.INPUT,
    displayName = "video.mp4",
    width = 1920,
    height = 1080,
    durationMs = 60_000,
    bytes = 80_000_000,
    frameRate = 30f,
    audioCodec = "aac",
    hasAudio = true,
)

private fun CompressJob?.toSource(): SourceVideo? {
    val job = this ?: return null
    if (job.sourceUri.isBlank()) return null
    return SourceVideo(
        uri = job.sourceUri,
        displayName = job.displayName,
        width = job.width,
        height = job.height,
        durationMs = job.durationMs,
        bytes = job.sourceBytes,
        frameRate = 30f,
        audioCodec = null,
        hasAudio = !job.isCombine || job.audioUri.isNotBlank(),
        hasVideo = job.width > 0 && job.height > 0 || job.stillImage,
        stillImage = job.stillImage,
        audioUri = job.audioUri,
    )
}

fun codecAllowed(codec: VideoCodec, caps: EncoderCapabilities, engine: EncodeEngine = EncodeEngine.FFMPEG): Boolean =
    when (codec) {
        VideoCodec.HEVC -> caps.hasHevcMediaCodec
        VideoCodec.AV1 -> caps.av1Available(engine)
        else -> true
    }

private data class AiSlice(
    val error: String?,
    val prompt: String,
    val busy: Boolean,
    val message: String?,
)
