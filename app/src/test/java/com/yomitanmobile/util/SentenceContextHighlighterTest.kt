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
    fun highlightsInflectedIchidanForm() {
        val html = SentenceContextHighlighter.buildHighlightedSentenceHtml(
            sentence = "りんごをたべた。",
            preferredTokens = listOf("たべる")
        )

        assertTrue(html.contains("<strong class=\"context-highlight\">たべた</strong>"))
    }

    @Test
    fun highlightsInflectedGodanPastForm() {
        val html = SentenceContextHighlighter.buildHighlightedSentenceHtml(
            sentence = "おちゃをのんだ。",
            preferredTokens = listOf("のむ")
        )

        assertTrue(html.contains("<strong class=\"context-highlight\">のんだ</strong>"))
    }

    @Test
    fun prefersLongestOccurringInflection() {
        // The sentence contains the te-form; the base form is not present, so
        // the highlighter must pick the conjugated occurrence.
        val html = SentenceContextHighlighter.buildHighlightedSentenceHtml(
            sentence = "ごはんをたべている。",
            preferredTokens = listOf("たべる")
        )

        assertTrue(html.contains("<strong class=\"context-highlight\">たべている</strong>"))
    }

    @Test
    fun highlightsKatakanaSpellingOfKanaWord() {
        val html = SentenceContextHighlighter.buildHighlightedSentenceHtml(
            sentence = "そこでサボっていた。",
            preferredTokens = listOf("さぼる")
        )

        assertTrue(html.contains("<strong class=\"context-highlight\">サボっていた</strong>"))
    }

    @Test
    fun highlightsHiraganaSpellingOfKatakanaWord() {
        val html = SentenceContextHighlighter.buildHighlightedSentenceHtml(
            sentence = "まいにちこーひーをのむ。",
            preferredTokens = listOf("コーヒー")
        )

        assertTrue(html.contains("<strong class=\"context-highlight\">こーひー</strong>"))
    }

    @Test
    fun highlightsInflectionOutsideTheGeneratedSet() {
        // 食べさせられなかった is not in the forward-generated list; the
        // deconjugation scan has to recover it.
        val html = SentenceContextHighlighter.buildHighlightedSentenceHtml(
            sentence = "野菜を食べさせられなかった。",
            preferredTokens = listOf("食べる", "たべる")
        )

        assertTrue(html.contains("<strong class=\"context-highlight\">食べさせられなかった</strong>"))
    }

    @Test
    fun highlightsInflectedIAdjective() {
        val html = SentenceContextHighlighter.buildHighlightedSentenceHtml(
            sentence = "この本は高かった。",
            preferredTokens = listOf("高い", "たかい")
        )

        assertTrue(html.contains("<strong class=\"context-highlight\">高かった</strong>"))
    }

    @Test
    fun doesNotHighlightStemInsideUnrelatedCompound() {
        val html = SentenceContextHighlighter.buildHighlightedSentenceHtml(
            sentence = "食べ物を買う。",
            preferredTokens = listOf("食べる")
        )

        assertFalse(html.contains("context-highlight"))
    }

    @Test
    fun leavesSentenceIntactWhenWordIsAbsent() {
        val html = SentenceContextHighlighter.buildHighlightedSentenceHtml(
            sentence = "今日はいい天気ですね。",
            preferredTokens = listOf("食べる", "たべる")
        )

        assertFalse(html.contains("context-highlight"))
        assertTrue(html.contains("今日はいい天気ですね。"))
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
