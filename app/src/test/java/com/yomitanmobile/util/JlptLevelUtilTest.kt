package com.yomitanmobile.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class JlptLevelUtilTest {

    @Test
    fun testJMDictTagN1() {
        // Test extraction of N1 from JMDict tag "jlpt-1"
        val result = JlptLevelUtil.getLevel("jlpt-1, verb", frequency = 0)
        assertEquals(JlptLevelUtil.JlptLevel.N1, result)
    }

    @Test
    fun testJMDictTagN2() {
        // Test extraction of N2 from JMDict tag "jlpt-2"
        val result = JlptLevelUtil.getLevel("jlpt-2, verb", frequency = 0)
        assertEquals(JlptLevelUtil.JlptLevel.N2, result)
    }

    @Test
    fun testJMDictTagN3() {
        // Test extraction of N3 from JMDict tag "jlpt-3"
        val result = JlptLevelUtil.getLevel("jlpt-3, verb", frequency = 0)
        assertEquals(JlptLevelUtil.JlptLevel.N3, result)
    }

    @Test
    fun testJMDictTagN4() {
        // Test extraction of N4 from JMDict tag "jlpt-4"
        val result = JlptLevelUtil.getLevel("jlpt-4, verb", frequency = 0)
        assertEquals(JlptLevelUtil.JlptLevel.N4, result)
    }

    @Test
    fun testJMDictTagN5() {
        // Test extraction of N5 from JMDict tag "jlpt-5"
        val result = JlptLevelUtil.getLevel("jlpt-5, verb", frequency = 0)
        assertEquals(JlptLevelUtil.JlptLevel.N5, result)
    }

    @Test
    fun testJMDictTagWithPartsOfSpeech() {
        // Test tag extraction when mixed with parts of speech
        val result = JlptLevelUtil.getLevel("noun, verb, jlpt-1, ichidan", frequency = 0)
        assertEquals(JlptLevelUtil.JlptLevel.N1, result)
    }

    @Test
    fun testFrequencyFallbackN5() {
        // Test frequency-based N5 assignment when no JMDict tag
        val result = JlptLevelUtil.getLevel("verb", frequency = 300)
        assertEquals(JlptLevelUtil.JlptLevel.N5, result)
    }

    @Test
    fun testFrequencyFallbackN4() {
        // Test frequency-based N4 assignment when no JMDict tag
        val result = JlptLevelUtil.getLevel("verb", frequency = 1500)
        assertEquals(JlptLevelUtil.JlptLevel.N4, result)
    }

    @Test
    fun testFrequencyFallbackN3() {
        // Test frequency-based N3 assignment when no JMDict tag
        val result = JlptLevelUtil.getLevel("verb", frequency = 5000)
        assertEquals(JlptLevelUtil.JlptLevel.N3, result)
    }

    @Test
    fun testFrequencyFallbackN2() {
        // Test frequency-based N2 assignment when no JMDict tag
        val result = JlptLevelUtil.getLevel("verb", frequency = 10000)
        assertEquals(JlptLevelUtil.JlptLevel.N2, result)
    }

    @Test
    fun testFrequencyFallbackN1() {
        // Test frequency-based N1 assignment when no JMDict tag
        val result = JlptLevelUtil.getLevel("verb", frequency = 30000)
        assertEquals(JlptLevelUtil.JlptLevel.N1, result)
    }

    @Test
    fun testJMDictTagOverridesFrequency() {
        // Test that JMDict tag takes priority over frequency
        val result = JlptLevelUtil.getLevel("jlpt-5, verb", frequency = 50000)
        assertEquals(JlptLevelUtil.JlptLevel.N5, result)
    }

    @Test
    fun testEmptyTagsWithFrequency() {
        // Test fallback to frequency when tags string is empty
        val result = JlptLevelUtil.getLevel("", frequency = 2000)
        assertEquals(JlptLevelUtil.JlptLevel.N4, result)
    }

    @Test
    fun testNoDataReturnsNull() {
        // Test that unknown word with no frequency returns null
        val result = JlptLevelUtil.getLevel("verb", frequency = 0)
        assertNull(result)
    }

    @Test
    fun testFrequencyOutOfRangeReturnsNull() {
        // Test that very high frequency (out of range) returns null
        val result = JlptLevelUtil.getLevel("verb", frequency = 100000)
        assertNull(result)
    }

    @Test
    fun testFrequencyThresholdsAreCorrect() {
        // Verify exact threshold boundaries
        assertEquals(JlptLevelUtil.JlptLevel.N5, JlptLevelUtil.getLevel("verb", 500))
        assertEquals(JlptLevelUtil.JlptLevel.N4, JlptLevelUtil.getLevel("verb", 501))
        assertEquals(JlptLevelUtil.JlptLevel.N4, JlptLevelUtil.getLevel("verb", 2000))
        assertEquals(JlptLevelUtil.JlptLevel.N3, JlptLevelUtil.getLevel("verb", 2001))
    }

    @Test
    fun testTagCaseInsensitive() {
        // Test that tag matching is case-insensitive
        val result = JlptLevelUtil.getLevel("JLPT-1,verb", frequency = 0)
        assertEquals(JlptLevelUtil.JlptLevel.N1, result)
    }

    @Test
    fun testWhitespaceHandling() {
        // Test that whitespace in tags is handled correctly
        val result = JlptLevelUtil.getLevel("  jlpt-3  ,  verb  ", frequency = 0)
        assertEquals(JlptLevelUtil.JlptLevel.N3, result)
    }
}
