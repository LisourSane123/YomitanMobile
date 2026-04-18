package com.yomitanmobile.data.sentence

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OnlineSentenceServiceTest {

    @Test
    fun parseSentenceFromResponse_returnsSentenceAndEnglishTranslation() {
        val service = OnlineSentenceService()
        val payload = """
            {
              "results": [
                {
                  "text": "写真を撮るのが好きです。",
                  "translations": [
                    [
                      {"lang": "eng", "text": "I like taking pictures."}
                    ]
                  ]
                }
              ]
            }
        """.trimIndent()

        val result = service.parseSentenceFromResponse(payload, "撮る")

        requireNotNull(result)
        assertEquals("写真を撮るのが好きです。", result.japanese)
        assertEquals("I like taking pictures.", result.translation)
    }

    @Test
    fun parseSentenceFromResponse_returnsNullWhenNoResults() {
        val service = OnlineSentenceService()
        val payload = """
            { "results": [] }
        """.trimIndent()

        val result = service.parseSentenceFromResponse(payload, "撮る")

        assertNull(result)
    }
}
