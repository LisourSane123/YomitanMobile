package com.yomitanmobile.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SentenceContextHighlighterTest {

    @Test
    fun highlightsExpressionWhenPresent() {
        val html = SentenceContextHighlighter.buildHighlightedSentenceHtml(
            sentence = "毎日野菜を食べる。",
            preferredTokens = listOf("食べる", "たべる")
        )

        assertTrue(html.contains("<strong class=\"context-highlight\">食べる</strong>"))
    }

    @Test
    fun fallsBackToReadingWhenExpressionMissing() {
        val html = SentenceContextHighlighter.buildHighlightedSentenceHtml(
            sentence = "毎日たべる。",
            preferredTokens = listOf("食べる", "たべる")
        )

        assertTrue(html.contains("<strong class=\"context-highlight\">たべる</strong>"))
    }

    @Test
    fun keepsHtmlEscaped() {
        val html = SentenceContextHighlighter.buildHighlightedSentenceHtml(
            sentence = "<script>alert(1)</script> 食べる",
            preferredTokens = listOf("食べる")
        )

        assertFalse(html.contains("<script>"))
        assertTrue(html.contains("&lt;script&gt;alert(1)&lt;/script&gt;"))
    }
}
