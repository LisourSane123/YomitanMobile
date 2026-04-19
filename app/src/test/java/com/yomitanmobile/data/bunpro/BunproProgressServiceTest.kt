package com.yomitanmobile.data.bunpro

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BunproProgressServiceTest {

    private val service = BunproProgressService()

    @Test
    fun buildRequestUrl_replacesTokenPlaceholder() {
        val url = service.buildRequestUrl(
            endpointTemplate = "https://bunpro.jp/api/user/{token}",
            token = "abc-123"
        )

        assertEquals("https://bunpro.jp/api/user/abc-123", url)
    }

    @Test
    fun parseProgressFromResponse_extractsVocabularyAndKanji() {
        val payload = """
            {
              "data": {
                "vocabulary": [
                  { "word": "食べる", "meaning": "to eat" },
                  { "word": "電車", "meaning": "train" },
                  { "word": "ひらがな", "meaning": "hiragana" }
                ]
              }
            }
        """.trimIndent()

        val progress = service.parseProgressFromResponse(payload)

        assertNotNull(progress)
        val learned = progress!!
        assertTrue(learned.learnedVocabulary.contains("食べる"))
        assertTrue(learned.learnedVocabulary.contains("電車"))
        assertTrue(learned.learnedKanji.contains("食"))
        assertTrue(learned.learnedKanji.contains("電"))
        assertTrue(learned.learnedKanji.contains("車"))
    }

    @Test
    fun parseProgressFromResponse_fallbackMode_stillExtractsJapaneseTerms() {
        val payload = """
            {
              "items": [
                { "text": "学校" },
                { "text": "先生" },
                { "text": "teacher" }
              ]
            }
        """.trimIndent()

        val progress = service.parseProgressFromResponse(payload)

        assertNotNull(progress)
        val learned = progress!!
        assertTrue(learned.learnedVocabulary.contains("学校"))
        assertTrue(learned.learnedVocabulary.contains("先生"))
    }
}
