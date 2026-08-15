package com.androidcompress.app.ai

import com.androidcompress.app.data.EncodeSettings
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GeminiFfmpegAssistantTest {

    @Test
    fun parseJsonObject() {
        val suggestion = GeminiFfmpegAssistant.parseSuggestion(
            """{"args":"-vf hflip","note":"Mirror the video."}""",
        )
        assertEquals("-vf hflip", suggestion.args)
        assertEquals("Mirror the video.", suggestion.note)
    }

    @Test
    fun parseFencedJson() {
        val suggestion = GeminiFfmpegAssistant.parseSuggestion(
            """
            ```json
            {"args":"-r 30","note":"Cap frame rate."}
            ```
            """.trimIndent(),
        )
        assertEquals("-r 30", suggestion.args)
    }

    @Test
    fun parseBareFlagLine() {
        val suggestion = GeminiFfmpegAssistant.parseSuggestion("Here you go:\n-vf eq=contrast=1.1\n")
        assertEquals("-vf eq=contrast=1.1", suggestion.args)
    }

    @Test
    fun extractCandidateText() {
        val body = """
            {"candidates":[{"content":{"parts":[{"text":"{\"args\":\"-vf vflip\",\"note\":\"Flip\"}"}]}}]}
        """.trimIndent()
        val text = GeminiFfmpegAssistant.extractText(body)
        assertTrue(text.contains("-vf vflip"))
    }

    @Test
    fun parseGoogleError() {
        val message = GeminiFfmpegAssistant.parseError(
            """{"error":{"message":"API key not valid"}}""",
            400,
        )
        assertEquals("API key not valid", message)
    }

    @Test
    fun hasSeveralFreeFlashFallbacks() {
        assertEquals("gemini-3.7-flash", GeminiFfmpegAssistant.MODELS.first())
        assertTrue(GeminiFfmpegAssistant.MODELS.size >= 8)
        assertTrue(GeminiFfmpegAssistant.MODELS.contains("gemini-3.5-flash-lite"))
        assertTrue(GeminiFfmpegAssistant.MODELS.contains("gemini-2.5-flash"))
        assertTrue(GeminiFfmpegAssistant.MODELS.contains("gemini-flash-latest"))
    }

    @Test
    fun retriesMissingModelAndRateLimit() {
        assertTrue(GeminiFfmpegAssistant.shouldTryNextModel(404, "models/x is not found"))
        assertTrue(GeminiFfmpegAssistant.shouldTryNextModel(429, "Resource exhausted"))
        assertTrue(GeminiFfmpegAssistant.shouldTryNextModel(503, "The service is currently unavailable"))
        assertTrue(GeminiFfmpegAssistant.shouldTryNextModel(null, "timeout"))
        assertFalse(GeminiFfmpegAssistant.shouldTryNextModel(400, "API key not valid"))
        assertFalse(GeminiFfmpegAssistant.shouldTryNextModel(401, "Unauthorized"))
    }

    @Test
    fun walksModelsUntilOneSucceeds() = runBlocking {
        val attempted = mutableListOf<String>()
        val assistant = GeminiFfmpegAssistant { url, _, _ ->
            val model = url.substringAfter("/models/").substringBefore(":")
            attempted += model
            when (attempted.size) {
                1 -> HttpText(404, """{"error":{"message":"$model is not found"}}""")
                2 -> HttpText(429, """{"error":{"message":"Resource exhausted"}}""")
                3 -> HttpText(200, """{"candidates":[{"content":{"parts":[{"text":""}]}}]}""")
                else -> HttpText(
                    200,
                    """{"candidates":[{"content":{"parts":[{"text":"{\"args\":\"-vf hflip\",\"note\":\"ok\"}"}]}}]}""",
                )
            }
        }
        val result = assistant.suggest("key", "flip it", EncodeSettings(), null, "h264_mediacodec")
        assertTrue(result.isSuccess)
        assertEquals("-vf hflip", result.getOrThrow().args)
        assertEquals(GeminiFfmpegAssistant.MODELS.take(4), attempted)
    }

    @Test
    fun userPromptIncludesBuiltCommand() {
        val command = "-y -hide_banner -i INPUT -c:v h264_mediacodec -b:v 2500k OUTPUT"
        val text = GeminiFfmpegAssistant.userPrompt(
            "flip it",
            EncodeSettings(ffmpegExtraArgs = "-vf hflip"),
            null,
            "h264_mediacodec",
            command,
        )
        assertTrue(text.contains("Current command: $command"))
        assertTrue(text.contains("Current extra args: -vf hflip"))
        assertTrue(text.contains("User request: flip it"))
    }

    @Test
    fun requestBodyIncludesBuiltCommand() = runBlocking {
        var posted = ""
        val command = "-y -hide_banner -i INPUT -vf scale=1280:720 -c:v h264_mediacodec OUTPUT"
        val assistant = GeminiFfmpegAssistant { _, _, body ->
            posted = body
            HttpText(
                200,
                """{"candidates":[{"content":{"parts":[{"text":"{\"args\":\"-vf hflip\",\"note\":\"ok\"}"}]}}]}""",
            )
        }
        assistant.suggest("key", "flip it", EncodeSettings(), null, "h264_mediacodec", command)
        assertTrue(posted.contains("Current command:"))
        assertTrue(posted.contains("-c:v h264_mediacodec"))
        assertTrue(posted.contains("INPUT"))
        assertTrue(posted.contains("OUTPUT"))
    }

    @Test
    fun stopsOnInvalidApiKey() = runBlocking {
        var calls = 0
        val assistant = GeminiFfmpegAssistant { _, _, _ ->
            calls += 1
            HttpText(400, """{"error":{"message":"API key not valid. Please pass a valid API key."}}""")
        }
        val result = assistant.suggest("bad", "flip it", EncodeSettings(), null, "h264_mediacodec")
        assertTrue(result.isFailure)
        assertEquals(1, calls)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("API key"))
    }
}
