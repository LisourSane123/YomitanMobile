package com.yomitanmobile.data.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-function tests around AiSummaryService — prompt templating and
 * response parsing. The HTTP path is not exercised here (no network in
 * unit tests), but the JSON walkers and prompt renderer are the parts
 * most likely to break with provider format drift.
 */
class AiSummaryServiceTest {

    private val service = AiSummaryService()

    @Test
    fun renderPromptSubstitutesAllPlaceholders() {
        val rendered = service.renderPrompt(
            template = AI_DEFAULT_PROMPT,
            expression = "食べる",
            reading = "たべる",
            meanings = listOf("to eat", "to live on"),
            language = "Polish"
        )
        assertTrue("expression substituted: $rendered", rendered.contains("食べる"))
        assertTrue("reading substituted: $rendered", rendered.contains("たべる"))
        assertTrue("language substituted: $rendered", rendered.contains("Polish"))
        assertTrue("meanings joined: $rendered", rendered.contains("to eat; to live on"))
        assertTrue(
            "raw placeholder must not survive: $rendered",
            !rendered.contains("{expression}") &&
                !rendered.contains("{reading}") &&
                !rendered.contains("{meaning}") &&
                !rendered.contains("{language}")
        )
    }

    @Test
    fun renderPromptCustomTemplateOverridesDefault() {
        val rendered = service.renderPrompt(
            template = "Word: {expression} ({reading})",
            expression = "猫",
            reading = "ねこ",
            meanings = emptyList(),
            language = "English"
        )
        assertEquals("Word: 猫 (ねこ)", rendered)
    }

    @Test
    fun parseFirstStringExtractsGeminiResponseShape() {
        // Real Gemini response shape — `candidates[0].content.parts[0].text`.
        val payload = """
            {
              "candidates": [
                {
                  "content": {
                    "parts": [
                      {"text": "短い解説"}
                    ]
                  }
                }
              ]
            }
        """.trimIndent()
        val result = service.parseFirstString(
            payload,
            listOf("candidates", "0", "content", "parts", "0", "text")
        )
        assertEquals("短い解説", result)
    }

    @Test
    fun parseFirstStringExtractsOpenAiResponseShape() {
        // OpenAI / DeepSeek response shape — `choices[0].message.content`.
        val payload = """
            {"choices":[{"message":{"role":"assistant","content":"krótkie streszczenie"}}]}
        """.trimIndent()
        val result = service.parseFirstString(
            payload,
            listOf("choices", "0", "message", "content")
        )
        assertEquals("krótkie streszczenie", result)
    }

    @Test
    fun parseFirstStringReturnsNullForMissingPath() {
        val payload = """{"choices":[]}"""
        val result = service.parseFirstString(
            payload,
            listOf("choices", "0", "message", "content")
        )
        assertNull(result)
    }

    @Test
    fun extractErrorMessagePullsProviderError() {
        val payload = """{"error":{"message":"Invalid API key","code":401}}"""
        assertEquals("Invalid API key", service.extractErrorMessage(payload))
    }

    @Test
    fun extractErrorMessageReturnsNullWhenNoErrorField() {
        assertNull(service.extractErrorMessage("""{"choices":[]}"""))
    }

    @Test
    fun providerFromStorageRoundTrip() {
        AiProvider.values().forEach { p ->
            assertEquals(p, AiProvider.fromStorage(p.storageValue))
        }
        assertEquals(AiProvider.GEMINI, AiProvider.fromStorage(null))
        assertEquals(AiProvider.GEMINI, AiProvider.fromStorage("nonsense-key"))
    }
}
