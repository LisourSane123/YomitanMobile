package com.yomitanmobile.util

import org.junit.Assert.assertTrue
import org.junit.Test

class SentenceRubyFormatterTest {

    @Test
    fun buildRubyHtml_highlightsInflectedStem() {
        val html = SentenceRubyFormatter.buildRubyHtml(
            sentence = "私は毎日食べます。",
            targetExpression = "食べる",
            targetReading = "たべる"
        )

        assertTrue(html.contains("<ruby>食べ<rt>たべる</rt></ruby>"))
        assertTrue(html.startsWith("私は毎日"))
        assertTrue(html.endsWith("ます。"))
    }

    @Test
    fun buildRubyHtml_returnsPlainSentenceWhenNoMatch() {
        val html = SentenceRubyFormatter.buildRubyHtml(
            sentence = "これはテストです。",
            targetExpression = "食べる",
            targetReading = "たべる"
        )

        assertTrue(html.contains("これはテストです。"))
    }
}
