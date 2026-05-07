package com.yomitanmobile.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class JlptVocabularyTest {

    @Test
    fun taberuIsN5() {
        assertEquals(5, JlptVocabulary.getLevel("食べる", "たべる"))
    }

    @Test
    fun nomuIsN5() {
        assertEquals(5, JlptVocabulary.getLevel("飲む", "のむ"))
    }

    @Test
    fun kanaOnlyKanaWordResolves() {
        assertEquals(5, JlptVocabulary.getLevel("ありがとう", "ありがとう"))
    }

    @Test
    fun unknownWordReturnsZero() {
        assertEquals(0, JlptVocabulary.getLevel("外字外語", "がいじがいご"))
    }

    @Test
    fun blankReturnsZero() {
        assertEquals(0, JlptVocabulary.getLevel("", ""))
    }

    @Test
    fun n4WordResolves() {
        assertEquals(4, JlptVocabulary.getLevel("急ぐ", "いそぐ"))
    }

    @Test
    fun n3WordResolves() {
        assertEquals(3, JlptVocabulary.getLevel("確認", "かくにん"))
    }

    @Test
    fun n2WordResolves() {
        assertEquals(2, JlptVocabulary.getLevel("影響", "えいきょう"))
    }

    @Test
    fun n1WordResolves() {
        assertEquals(1, JlptVocabulary.getLevel("顧客", "こきゃく"))
    }

    // The crux of the fix: words sharing a reading must NOT inherit each
    // other's levels. 聞く is N5, but 効く / 利く / 菊 / 規矩 also read きく
    // and are at much higher levels. Reading-only fallback used to falsely
    // tag them all as N5.

    @Test
    fun kikuListenIsN5() {
        assertEquals(5, JlptVocabulary.getLevel("聞く", "きく"))
    }

    @Test
    fun kikuTakeEffectIsN3NotN5() {
        // 効く is N3, not N5 — the homophone bug used to return 5 here.
        val level = JlptVocabulary.getLevel("効く", "きく")
        assertEquals(3, level)
        assertNotEquals(5, level)
    }

    @Test
    fun kikuFunctionIsNotN5() {
        // 利く should not pick up 聞く's N5 by sharing the reading.
        val level = JlptVocabulary.getLevel("利く", "きく")
        assertNotEquals(5, level)
    }

    @Test
    fun kikuChrysanthemumIsNotN5() {
        // 菊 should not pick up 聞く's N5 by sharing the reading.
        val level = JlptVocabulary.getLevel("菊", "きく")
        assertNotEquals(5, level)
    }

    @Test
    fun unknownKanjiWordWithSharedReadingReturnsZero() {
        // A word with kanji that's NOT in our list, even with a reading that
        // matches an N5 entry, must return 0 — never the N5 from the homophone.
        val level = JlptVocabulary.getLevel("起句", "きく")
        assertEquals(0, level)
    }

    @Test
    fun lookupWithBlankReadingFallsBackToExpression() {
        // For kana-only words, a missing reading is OK because expression == reading.
        assertEquals(5, JlptVocabulary.getLevel("ありがとう", ""))
    }

    @Test
    fun emptyReadingForKanjiWordDoesNotMatch() {
        // 食べる with empty reading shouldn't match because the data has
        // ("食べる", "たべる"), not ("食べる", "食べる"), and the kana-only
        // fallback is gated on the expression being kana-only.
        assertEquals(0, JlptVocabulary.getLevel("食べる", ""))
    }
}
