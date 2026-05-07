package com.yomitanmobile.util

import org.junit.Assert.assertEquals
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
    fun lookupByExpressionAlone() {
        // Reading-only lookup still resolves
        assertEquals(5, JlptVocabulary.getLevel("食べる", ""))
    }

    @Test
    fun lookupByReadingOnlyForKanaWord() {
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
}
