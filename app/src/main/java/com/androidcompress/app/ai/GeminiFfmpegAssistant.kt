package com.androidcompress.app.ai

import com.androidcompress.app.data.EncodeSettings
import com.androidcompress.app.data.SourceVideo
import com.androidcompress.app.encode.ExtraArgsSanitizer
import com.androidcompress.app.util.AppLog
import com.androidcompress.app.util.runCatchingLog
import com.androidcompress.app.util.formatDuration
import com.androidcompress.app.util.formatResolution
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class GeminiSuggestion(
    val args: String,
    val note: String,
)

class GeminiFfmpegAssistant(
    private val post: (url: String, apiKey: String, body: String) -> HttpText = ::defaultPost,
) {

    suspend fun suggest(
        apiKey: String,
        userRequest: String,
        settings: EncodeSettings,
        source: SourceVideo?,
        encoder: String,
        command: String = "",
    ): Result<GeminiSuggestion> = runCatching {
        require(apiKey.isNotBlank()) { "Add a free Gemini API key in Settings." }
        val request = userRequest.trim()
        require(request.isNotBlank()) { "Describe the extra encode change you want." }
        withContext(Dispatchers.IO) {
            val payload = buildRequest(request, settings, source, encoder, command)
            var lastError = "Gemini request failed"
            var lastModel = MODELS.first()
            for (model in MODELS) {
                lastModel = model
                val url = "$ENDPOINT/models/$model:generateContent"
                val response = try {
                    post(url, apiKey.trim(), payload)
                } catch (error: Exception) {
                    AppLog.e(TAG, "gemini $model", error)
                    lastError = error.message?.takeIf { it.isNotBlank() } ?: error.javaClass.simpleName
                    if (!shouldTryNextModel(code = null, lastError)) error(lastError)
                    continue
                }
                if (response.code in 200..299) {
                    val parsed = runCatchingLog(TAG, "parse gemini reply") { acceptReply(response.body) }
                    if (parsed.isSuccess) return@withContext parsed.getOrThrow()
                    lastError = parsed.exceptionOrNull()?.message ?: "Invalid Gemini reply"
                    continue
                }
                lastError = parseError(response.body, response.code)
                if (!shouldTryNextModel(response.code, lastError)) error(lastError)
            }
            error("Gemini failed after trying ${MODELS.size} models. Last error ($lastModel): $lastError")
        }
    }

    companion object {
        private const val TAG = "GeminiFfmpeg"
        private const val ENDPOINT = "https://generativelanguage.googleapis.com/v1beta"
        internal val MODELS = listOf(
            "gemini-3.7-flash",
            "gemini-flash-latest",
            "gemini-3.6-flash",
            "gemini-3.5-flash",
            "gemini-3-flash-preview",
            "gemini-2.5-flash",
            "gemini-3.5-flash-lite",
            "gemini-flash-lite-latest",
            "gemini-3.1-flash-lite",
            "gemini-2.5-flash-lite",
        )

        internal fun parseSuggestion(text: String): GeminiSuggestion {
            val cleaned = stripFences(text)
            val obj = extractJsonObject(cleaned)
            if (obj != null) {
                val args = obj.optString("args").ifBlank { obj.optString("command") }
                if (args.isNotBlank()) {
                    return GeminiSuggestion(args.trim(), obj.optString("note").trim())
                }
            }
            val line = cleaned.lineSequence()
                .map { it.trim().trim('`') }
                .firstOrNull { it.startsWith("-") }
            if (line != null) return GeminiSuggestion(line, "")
            error("Gemini did not return FFmpeg arguments.")
        }

        internal fun extractText(body: String): String {
            val root = JSONObject(body)
            val parts = root.optJSONArray("candidates")
                ?.optJSONObject(0)
                ?.optJSONObject("content")
                ?.optJSONArray("parts")
            if (parts == null || parts.length() == 0) {
                val block = root.optJSONArray("candidates")
                    ?.optJSONObject(0)
                    ?.optString("finishReason")
                    .orEmpty()
                error(if (block.isNotBlank()) "Gemini blocked the reply ($block)." else "Empty Gemini reply.")
            }
            val text = buildString {
                for (i in 0 until parts.length()) {
                    append(parts.optJSONObject(i)?.optString("text").orEmpty())
                }
            }.trim()
            if (text.isBlank()) error("Empty Gemini reply.")
            return text
        }

        internal fun parseError(body: String, code: Int): String {
            val message = runCatchingLog(TAG, "parse gemini error") {
                JSONObject(body).optJSONObject("error")?.optString("message")
            }.getOrNull()?.takeIf { it.isNotBlank() }
            return message ?: "Gemini HTTP $code"
        }

        internal fun shouldTryNextModel(code: Int?, message: String): Boolean {
            if (isFatalAuthError(code, message)) return false
            return true
        }

        internal fun isFatalAuthError(code: Int?, message: String): Boolean {
            if (code == 401) return true
            val lower = message.lowercase()
            return lower.contains("api key not valid") ||
                lower.contains("api_key_invalid") ||
                lower.contains("invalid api key") ||
                lower.contains("api key expired") ||
                (code == 400 && lower.contains("api key"))
        }

        private fun acceptReply(body: String): GeminiSuggestion {
            val suggestion = parseSuggestion(extractText(body))
            val parsed = ExtraArgsSanitizer.parse(suggestion.args)
            if (suggestion.args.isNotBlank() && !parsed.isValid) {
                error("${parsed.error} Suggested: ${suggestion.args}")
            }
            return suggestion.copy(args = parsed.canonical)
        }

        private fun stripFences(text: String): String {
            var value = text.trim()
            if (value.startsWith("```")) {
                value = value.removePrefix("```json").removePrefix("```JSON").removePrefix("```").trim()
                value = value.removeSuffix("```").trim()
            }
            return value
        }

        private fun extractJsonObject(text: String): JSONObject? {
            runCatchingLog(TAG, "gemini json") { return JSONObject(text) }
            val start = text.indexOf('{')
            val end = text.lastIndexOf('}')
            if (start >= 0 && end > start) {
                return runCatchingLog(TAG, "gemini json slice") {
                    JSONObject(text.substring(start, end + 1))
                }.getOrNull()
            }
            return null
        }

        internal fun userPrompt(
            userRequest: String,
            settings: EncodeSettings,
            source: SourceVideo?,
            encoder: String,
            command: String,
        ): String {
            val sourceLine = if (source == null) {
                "unknown source"
            } else {
                "${formatResolution(source.width, source.height)}, ${source.frameRate} fps, " +
                    formatDuration(source.durationMs)
            }
            return buildString {
                appendLine("User request: $userRequest")
                appendLine("Current extra args: ${settings.ffmpegExtraArgs.ifBlank { "(none)" }}")
                appendLine("Current command: ${command.ifBlank { "(none)" }}")
                appendLine("Output mode: ${settings.output}")
                appendLine("Container: ${settings.container}")
                appendLine("Source: $sourceLine")
                appendLine("Encoder already chosen: $encoder")
                appendLine("Codec setting: ${settings.codec}")
                appendLine("Video bitrate kbps: ${settings.videoBitrateKbps}")
                appendLine("Bitrate mode: ${settings.bitrateMode}")
                appendLine("Two-pass: ${settings.twoPass}")
                appendLine("Grayscale: ${settings.grayscale}")
                appendLine("Captions: ${settings.captions}")
                appendLine(
                    "Target size: " + if (settings.targetSizeBytes != null && settings.targetSizeBytes > 0L) {
                        "${settings.targetSizePreset} ${settings.targetSizeBytes} bytes"
                    } else {
                        "off"
                    },
                )
                appendLine("Audio: ${settings.audio}")
                appendLine("Hardware preferred: ${settings.preferHardware}")
            }
        }

        private fun buildRequest(
            userRequest: String,
            settings: EncodeSettings,
            source: SourceVideo?,
            encoder: String,
            command: String,
        ): String {
            val user = userPrompt(userRequest, settings, source, encoder, command)
            return JSONObject().apply {
                put(
                    "system_instruction",
                    JSONObject().put(
                        "parts",
                        JSONArray().put(JSONObject().put("text", SYSTEM_PROMPT)),
                    ),
                )
                put(
                    "contents",
                    JSONArray().put(
                        JSONObject()
                            .put("role", "user")
                            .put("parts", JSONArray().put(JSONObject().put("text", user))),
                    ),
                )
                put(
                    "generationConfig",
                    JSONObject()
                        .put("temperature", 0.2)
                        .put("maxOutputTokens", 2048)
                        .put("response_mime_type", "application/json"),
                )
            }.toString()
        }

        private fun defaultPost(url: String, apiKey: String, body: String): HttpText {
            val conn = URL(url).openConnection() as HttpURLConnection
            try {
                conn.requestMethod = "POST"
                conn.connectTimeout = 20_000
                conn.readTimeout = 30_000
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                conn.setRequestProperty("x-goog-api-key", apiKey)
                conn.doOutput = true
                conn.outputStream.buffered().use { out ->
                    out.write(body.toByteArray(Charsets.UTF_8))
                }
                val stream = if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
                val text = stream?.use { it.bufferedReader(Charsets.UTF_8).readText() }.orEmpty()
                return HttpText(conn.responseCode, text)
            } finally {
                conn.disconnect()
            }
        }

        private const val SYSTEM_PROMPT = """
You write extra FFmpeg CLI arguments for Recording Compressor on Android.

The user message includes the currently built command (INPUT and OUTPUT are placeholders for this job’s files). Extra args you return are inserted into that command. Do not repeat flags already present unless the user asked to change them.

If output mode is AUDIO and the container is MP4, the app already supplies -y -hide_banner -i INPUT -vn, optional -ss/-t, audio (-c:a aac or copy), optional -movflags +faststart, extra args, and an .m4a OUTPUT. Do not add -c:v.

If output mode is AUDIO and the container is WEBM, the app already supplies -y -hide_banner -i INPUT -vn, optional -ss/-t, audio (-c:a libopus or copy), extra args, and a .webm OUTPUT. Do not add -c:v or -movflags.

If output mode is VIDEO and the container is MP4, the app already supplies: -y -hide_banner -i INPUT, optional scale/fps, -c:v (h264_mediacodec, hevc_mediacodec, av1_mediacodec, libaom-av1, libsvtav1, libopenh264, or mpeg4), -b:v, optional -maxrate/-bufsize, -pix_fmt, profile/GOP/B-frames, audio (-c:a aac or copy or -an), optional -movflags +faststart, extra args, and OUTPUT.

If output mode is VIDEO and the container is WEBM, the app already supplies: -y -hide_banner -i INPUT, optional scale/fps, format=yuv420p, -c:v (libvpx-vp9, libvpx, av1_mediacodec, or libaom-av1), -b:v, libvpx deadline/cpu-used or libaom realtime flags, -pix_fmt yuv420p, audio (-c:a libopus or copy or -an), extra args, and a .webm OUTPUT. Do not add -movflags. Do not switch the output to MP4. Do not use vp8_mediacodec or vp9_mediacodec.

If the current command already has -i INPUT and -i AUDIO, this is a combine job (still image or video picture plus a separate soundtrack). Do not add another -i. Do not drop AUDIO. Do not use -filter_complex.

This FFmpeg build is LGPL. There is no libx264 or libx265. Do not use -crf, -preset slow, -tune, CUDA, NVENC, QSV, or extra -i inputs.

Reply with JSON only: {"args":"<space-separated extra flags>","note":"<one short sentence>"}
Rules for args:
- Extra flags only. No ffmpeg binary, no -i, no input/output paths, no -filter_complex, no concat, no file names, no URLs.
- Allowed examples: -vf eq=contrast=1.1, -vf crop=in_w-16:in_h-16, -vf hflip, -vf transpose=1, -r 30, -g 60, -bf 0, -profile:v high, -b:v 1800k, -maxrate 1800k -bufsize 3600k, -filter:a volume=1.5, -ar 44100, -metadata title=clip
- If the user wants a filter and the app already scales, include scale=W:H in the same -vf if you replace -vf.
- If the request cannot be done on this build, return {"args":"","note":"why not"}.
"""
    }
}

data class HttpText(val code: Int, val body: String)
