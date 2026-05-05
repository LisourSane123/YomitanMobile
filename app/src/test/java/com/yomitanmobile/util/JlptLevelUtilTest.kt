package com.yomitanmobile.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class JlptLevelUtilTest {

    @Test
    fun testgetLevelWithCuratedWord() {
        // Test N5 word from curated list
        val result = JlptLevelUtil.getLevel("食べる")
        assertEquals(JlptLevelUtil.JlptLevel.N5, result)
    }

    @Test
    fun testGetLevelWithFrequencyN5() {
        // Test frequency-based N5 assignment
        val result = JlptLevelUtil.getLevel("testWord", frequency = 300)
        assertEquals(JlptLevelUtil.JlptLevel.N5, result)
    }

    @Test
    fun testGetLevelWithFrequencyN4() {
        // Test frequency-based N4 assignment
        val result = JlptLevelUtil.getLevel("testWord", frequency = 1500)
        assertEquals(JlptLevelUtil.JlptLevel.N4, result)
    }

    @Test
    fun testGetLevelWithFrequencyN3() {
        // Test frequency-based N3 assignment
        val result = JlptLevelUtil.getLevel("testWord", frequency = 5000)
        assertEquals(JlptLevelUtil.JlptLevel.N3, result)
    }

    @Test
    fun testGetLevelWithFrequencyN2() {
        // Test frequency-based N2 assignment
        val result = JlptLevelUtil.getLevel("testWord", frequency = 10000)
        assertEquals(JlptLevelUtil.JlptLevel.N2, result)
    }

    @Test
    fun testGetLevelWithFrequencyN1() {
        // Test frequency-based N1 assignment
        val result = JlptLevelUtil.getLevel("testWord", frequency = 30000)
        assertEquals(JlptLevelUtil.JlptLevel.N1, result)
    }

    @Test
    fun testGetLevelNoDataReturnsNull() {
        // Test that unknown word with no frequency returns null
        val result = JlptLevelUtil.getLevel("unknownWord999", frequency = 0)
        assertNull(result)
    }

    @Test
    fun testGetLevelFrequencyOutOfRangeReturnsNull() {
        // Test that very high frequency (out of range) returns null
        val result = JlptLevelUtil.getLevel("unknownWord", frequency = 100000)
        assertNull(result)
    }

    @Test
    fun testGetLevelWithFallbackPrimaryMatch() {
        // Test fallback returns primary when it matches
        val result = JlptLevelUtil.getLevelWithFallback(
            primaryExpression = "食べる",
            alternativeExpressions = emptyList(),
            frequency = 0
        )
        assertEquals(JlptLevelUtil.JlptLevel.N5, result)
    }

    @Test
    fun testGetLevelWithFallbackAlternativeMatch() {
        // Test fallback returns alternative when primary doesn't match
        val result = JlptLevelUtil.getLevelWithFallback(
            primaryExpression = "unknownWord",
            alternativeExpressions = listOf("飲む"),
            frequency = 0
        )
        assertEquals(JlptLevelUtil.JlptLevel.N5, result)
    }

    @Test
    fun testGetLevelWithFallbackFrequencyFallback() {
        // Test fallback uses frequency when neither primary nor alternatives match
        val result = JlptLevelUtil.getLevelWithFallback(
            primaryExpression = "unknownWord1",
            alternativeExpressions = listOf("unknownWord2", "unknownWord3"),
            frequency = 2000
        )
        assertEquals(JlptLevelUtil.JlptLevel.N4, result)
    }

    @Test
    fun testGetLevelWithFallbackAllFail() {
        // Test fallback returns null when nothing matches
        val result = JlptLevelUtil.getLevelWithFallback(
            primaryExpression = "unknownWord1",
            alternativeExpressions = listOf("unknownWord2"),
            frequency = 0
        )
        assertNull(result)
    }

    @Test
    fun testHiraganaVariantN5() {
        // Test hiragana variant for N5
        val result = JlptLevelUtil.getLevel("たべる")
        assertEquals(JlptLevelUtil.JlptLevel.N5, result)
    }

    @Test
    fun testHiraganaVariantN4() {
        // Test hiragana variant for N4
        val result = JlptLevelUtil.getLevel("とどける")
        assertEquals(JlptLevelUtil.JlptLevel.N4, result)
    }

    @Test
    fun testFrequencyThresholdsAreCorrect() {
        // Verify exact threshold boundaries
        assertEquals(JlptLevelUtil.JlptLevel.N5, JlptLevelUtil.getLevel("test", 500))
        assertEquals(JlptLevelUtil.JlptLevel.N4, JlptLevelUtil.getLevel("test", 501))
        assertEquals(JlptLevelUtil.JlptLevel.N4, JlptLevelUtil.getLevel("test", 2000))
        assertEquals(JlptLevelUtil.JlptLevel.N3, JlptLevelUtil.getLevel("test", 2001))
    }
}
