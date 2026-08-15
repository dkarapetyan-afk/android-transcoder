package com.androidcompress.app.data

import org.json.JSONObject

object SettingsJson {
    fun encode(settings: EncodeSettings): String = JSONObject().apply {
        put("preset", settings.preset.name)
        put("maxHeight", settings.maxHeight ?: JSONObject.NULL)
        put("fpsCap", settings.fpsCap ?: JSONObject.NULL)
        put("codec", settings.codec.name)
        put("preferHardware", settings.preferHardware)
        put("videoBitrateKbps", settings.videoBitrateKbps)
        put("audio", settings.audio.name)
        put("engine", settings.engine.name)
        put("bitrateMode", settings.bitrateMode.name)
        put("keyframe", settings.keyframeInterval.name)
        put("h264Profile", settings.h264Profile.name)
        put("hdrMode", settings.hdrMode.name)
        put("audioVolume", settings.audioVolumePercent)
        put("fastStart", settings.fastStart)
        put("bFrames", settings.bFrames.name)
        put("extraArgs", settings.ffmpegExtraArgs)
        put("commandOverride", settings.ffmpegCommandOverride)
        put("clipStartMs", settings.clipStartMs)
        put("clipEndMs", settings.clipEndMs ?: JSONObject.NULL)
        put("output", settings.output.name)
    }.toString()

    fun decode(raw: String?): EncodeSettings {
        if (raw.isNullOrBlank()) return EncodeSettings.forPreset(Preset.BALANCED)
        return runCatching {
            val obj = JSONObject(raw)
            EncodeSettings(
                preset = Preset.valueOf(obj.getString("preset")),
                maxHeight = if (obj.isNull("maxHeight")) null else obj.getInt("maxHeight"),
                fpsCap = if (obj.isNull("fpsCap")) null else obj.getInt("fpsCap"),
                codec = VideoCodec.valueOf(obj.getString("codec")),
                preferHardware = obj.getBoolean("preferHardware"),
                videoBitrateKbps = obj.getInt("videoBitrateKbps"),
                audio = AudioOption.valueOf(obj.getString("audio")),
                engine = enumOr(obj.optString("engine"), EncodeEngine.FFMPEG),
                bitrateMode = enumOr(obj.optString("bitrateMode"), BitrateMode.CBR),
                keyframeInterval = enumOr(obj.optString("keyframe"), KeyframeInterval.AUTO),
                h264Profile = enumOr(obj.optString("h264Profile"), H264Profile.AUTO),
                hdrMode = enumOr(obj.optString("hdrMode"), HdrMode.KEEP),
                audioVolumePercent = obj.optInt("audioVolume", 100).coerceIn(10, 400),
                fastStart = if (obj.has("fastStart")) obj.getBoolean("fastStart") else true,
                bFrames = enumOr(obj.optString("bFrames"), BFrameSetting.AUTO),
                ffmpegExtraArgs = obj.optString("extraArgs", ""),
                ffmpegCommandOverride = obj.optString("commandOverride", ""),
                clipStartMs = obj.optLong("clipStartMs", 0L).coerceAtLeast(0L),
                clipEndMs = if (!obj.has("clipEndMs") || obj.isNull("clipEndMs")) {
                    null
                } else {
                    obj.getLong("clipEndMs").takeIf { it > 0L }
                },
                output = enumOr(obj.optString("output"), OutputMode.VIDEO),
            )
        }.getOrElse { EncodeSettings.forPreset(Preset.BALANCED) }
    }

    fun encodeCaps(caps: EncoderCapabilities): String = JSONObject().apply {
        put("h264mc", caps.hasH264MediaCodec)
        put("hevcMc", caps.hasHevcMediaCodec)
        put("openh264", caps.hasOpenH264)
        put("mpeg4", caps.hasMpeg4)
    }.toString()

    fun decodeCaps(raw: String?): EncoderCapabilities? {
        if (raw.isNullOrBlank()) return null
        return runCatching {
            val obj = JSONObject(raw)
            EncoderCapabilities(
                hasH264MediaCodec = obj.optBoolean("h264mc"),
                hasHevcMediaCodec = obj.optBoolean("hevcMc"),
                hasOpenH264 = obj.optBoolean("openh264"),
                hasMpeg4 = obj.optBoolean("mpeg4", true),
            )
        }.getOrNull()
    }

    private inline fun <reified T : Enum<T>> enumOr(raw: String?, fallback: T): T {
        if (raw.isNullOrBlank()) return fallback
        return runCatching { java.lang.Enum.valueOf(T::class.java, raw) }.getOrDefault(fallback)
    }
}
