package com.yomitanmobile.util

import com.yomitanmobile.domain.model.FuriganaSegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FuriganaGeneratorTest {

    // ---- distributeFurigana (Yomitan port) ----

    @Test
    fun distributesOkuriganaVerb() {
        val segs = FuriganaGenerator.distributeFurigana("食べる", "たべる")
        assertEquals(
            listOf(FuriganaSegment("食", "た"), FuriganaSegment("べる", "")),
            segs
        )
    }

    @Test
    fun distributesCompoundWithLeadingAndTrailingKana() {
        // お茶 → おちゃ : leading kana お anchors, 茶 gets ちゃ.
        val segs = FuriganaGenerator.distributeFurigana("お茶", "おちゃ")
        assertEquals(
            listOf(FuriganaSegment("お", ""), FuriganaSegment("茶", "ちゃ")),
            segs
        )
    }

    @Test
    fun multiKanjiRunCollapsesToWholeReading() {
        // No kana anchor between the two kanji, so the run stays whole.
        val segs = FuriganaGenerator.distributeFurigana("果物", "くだもの")
        assertEquals(listOf(FuriganaSegment("果物", "くだもの")), segs)
    }

    @Test
    fun irregularReadingFallsBackToWholeWord() {
        val segs = FuriganaGenerator.distributeFurigana("今日", "きょう")
        assertEquals(listOf(FuriganaSegment("今日", "きょう")), segs)
    }

    @Test
    fun kanaOnlySurfaceGetsNoFurigana() {
        val segs = FuriganaGenerator.distributeFurigana("たべる", "たべる")
        assertEquals(listOf(FuriganaSegment("たべる", "")), segs)
    }

    // ---- distributeFuriganaInflected (Yomitan port) ----

    @Test
    fun inflectedPastVerbKeepsStemReadingAndPlainTail() {
        // 食べた (surface) from 食べる/たべる : 食→た, then べた plain.
        val segs = FuriganaGenerator.distributeFuriganaInflected("食べる", "たべる", "食べた")
        assertEquals("食べた", segs.joinToString("") { it.text })
        assertTrue("食→た expected: $segs", segs.any { it.text == "食" && it.reading == "た" })
        assertTrue("tail must be plain: $segs", segs.none { it.text.contains("べた") && it.reading.isNotEmpty() })
    }

    @Test
    fun inflectedIrregularGoVerb() {
        // 行った from 行く/いく : 行→い, った plain.
        val segs = FuriganaGenerator.distributeFuriganaInflected("行く", "いく", "行った")
        assertEquals("行った", segs.joinToString("") { it.text })
        assertTrue("行→い expected: $segs", segs.any { it.text == "行" && it.reading == "い" })
    }

    @Test
    fun inflectedIAdjective() {
        // 高かった from 高い/たかい : 高→たか, かった plain.
        val segs = FuriganaGenerator.distributeFuriganaInflected("高い", "たかい", "高かった")
        assertEquals("高かった", segs.joinToString("") { it.text })
        assertTrue("高→たか expected: $segs", segs.any { it.text == "高" && it.reading == "たか" })
    }

    // ---- generate: sentence tokenisation ----

    @Test
    fun generateTokenisesSentenceAndReproducesText() {
        val sentence = "もっと果物を食べる。"
        val readings = mapOf("果物" to "くだもの", "食べる" to "たべる")
        val segs = FuriganaGenerator.generate(sentence, readings)

        assertEquals(sentence, segs.joinToString("") { it.text })
        assertTrue(segs.any { it.text == "果物" && it.reading == "くだもの" })
        assertTrue(segs.any { it.text == "食" && it.reading == "た" })
        assertTrue(segs.none { it.text.isEmpty() })
    }

    @Test
    fun generateAnnotatesConjugatedVerbViaDeconjugation() {
        // The key regression: a CONJUGATED verb in a no-ruby sentence still
        // gets furigana because the tokeniser deconjugates 食べた → 食べる,
        // looks up its reading, then distributes over the inflected surface.
        val sentence = "私は寿司を食べた。"
        val readings = mapOf("食べる" to "たべる", "寿司" to "すし", "私" to "わたし")
        val segs = FuriganaGenerator.generate(sentence, readings)

        assertEquals(sentence, segs.joinToString("") { it.text })
        assertTrue("食→た expected (conjugated): $segs", segs.any { it.text == "食" && it.reading == "た" })
        assertTrue("寿司→すし expected: $segs", segs.any { it.text == "寿司" && it.reading == "すし" })
        assertTrue("私→わたし expected: $segs", segs.any { it.text == "私" && it.reading == "わたし" })
    }

    @Test
    fun synthesisesFuriganaForRealNoRubySentence() {
        val sentence = "郵便局はどちらでしょうか。"
        val readings = mapOf("郵便局" to "ゆうびんきょく")
        val segs = FuriganaGenerator.generate(sentence, readings)

        assertEquals(sentence, segs.joinToString("") { it.text })
        assertTrue(
            "郵便局 should become a tappable segment: $segs",
            segs.any { it.text == "郵便局" && it.reading == "ゆうびんきょく" }
        )
    }

    @Test
    fun realJitendexNoRubySentenceWithConjugation() {
        // "赤ん坊を膝の上で眠った。" — the kind of sentence Jitendex ships with no
        // ruby. Uninflected kanji nouns AND the conjugated verb 眠った (← 眠る)
        // all become tappable through the dictionary + deconjugation.
        val sentence = "赤ん坊が膝の上で眠った。"
        val readings = mapOf(
            "赤ん坊" to "あかんぼう",
            "膝" to "ひざ",
            "上" to "うえ",
            "眠る" to "ねむる"
        )
        val segs = FuriganaGenerator.generate(sentence, readings)

        assertEquals(sentence, segs.joinToString("") { it.text })
        // Yomitan distributes readings per kanji group, so 赤ん坊 → 赤(あか)ん坊(ぼう).
        assertTrue("赤→あか: $segs", segs.any { it.text == "赤" && it.reading == "あか" })
        assertTrue("坊→ぼう: $segs", segs.any { it.text == "坊" && it.reading == "ぼう" })
        assertTrue("膝→ひざ: $segs", segs.any { it.text == "膝" && it.reading == "ひざ" })
        assertTrue("眠→ねむ (conjugated 眠った): $segs", segs.any { it.text == "眠" && it.reading == "ねむ" })
    }

    @Test
    fun candidateExpressionsCoverKanjiWordsAndDeconjugatedForms() {
        val candidates = FuriganaGenerator.candidateExpressions("食べた")
        assertTrue("surface substrings present", "食べた" in candidates)
        assertTrue("deconjugated dictionary form present", "食べる" in candidates)
        assertTrue(
            "every candidate contains a kanji",
            candidates.all { com.yomitanmobile.domain.model.MergedWordEntry.containsKanji(it) }
        )
    }
}
