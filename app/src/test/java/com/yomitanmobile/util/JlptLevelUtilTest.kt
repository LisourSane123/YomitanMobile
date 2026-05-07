package com.yomitanmobile.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class JlptLevelUtilTest {

    @Test
    fun testJMDictTagN1() {
        val result = JlptLevelUtil.getLevel("jlpt-1, verb")
        assertEquals(JlptLevelUtil.JlptLevel.N1, result)
    }

    @Test
    fun testJMDictTagN2() {
        val result = JlptLevelUtil.getLevel("jlpt-2, verb")
        assertEquals(JlptLevelUtil.JlptLevel.N2, result)
    }

    @Test
    fun testJMDictTagN3() {
        val result = JlptLevelUtil.getLevel("jlpt-3, verb")
        assertEquals(JlptLevelUtil.JlptLevel.N3, result)
    }

    @Test
    fun testJMDictTagN4() {
        val result = JlptLevelUtil.getLevel("jlpt-4, verb")
        assertEquals(JlptLevelUtil.JlptLevel.N4, result)
    }

    @Test
    fun testJMDictTagN5() {
        val result = JlptLevelUtil.getLevel("jlpt-5, verb")
        assertEquals(JlptLevelUtil.JlptLevel.N5, result)
    }

    @Test
    fun testJMDictTagWithPartsOfSpeech() {
        val result = JlptLevelUtil.getLevel("noun, verb, jlpt-1, ichidan")
        assertEquals(JlptLevelUtil.JlptLevel.N1, result)
    }

    @Test
    fun testTagCaseInsensitive() {
        val result = JlptLevelUtil.getLevel("JLPT-1,verb")
        assertEquals(JlptLevelUtil.JlptLevel.N1, result)
    }

    @Test
    fun testWhitespaceHandling() {
        val result = JlptLevelUtil.getLevel("  jlpt-3  ,  verb  ")
        assertEquals(JlptLevelUtil.JlptLevel.N3, result)
    }

    @Test
    fun testJMDictTagFormatJlptN1() {
        val result = JlptLevelUtil.getLevel("noun, jlpt-n1, common")
        assertEquals(JlptLevelUtil.JlptLevel.N1, result)
    }

    @Test
    fun testMultipleJlptTagsPrefersMostAdvanced() {
        val result = JlptLevelUtil.getLevel("jlpt-3, noun, jlpt-n1")
        assertEquals(JlptLevelUtil.JlptLevel.N1, result)
    }

    @Test
    fun testDoesNotTreatJlpt3000AsN3() {
        val result = JlptLevelUtil.getLevel("freq, jlpt-3000")
        assertNull(result)
    }

    @Test
    fun testSupportsSpacedFormatJlptN1() {
        val result = JlptLevelUtil.getLevel("jlpt n 1, noun")
        assertEquals(JlptLevelUtil.JlptLevel.N1, result)
    }

    @Test
    fun testStandaloneN1TagWithoutJlptPrefix() {
        val result = JlptLevelUtil.getLevel("noun, n1")
        assertEquals(JlptLevelUtil.JlptLevel.N1, result)
    }

    @Test
    fun testStandaloneN3TagWithoutJlptPrefix() {
        val result = JlptLevelUtil.getLevel("noun, n3, verb")
        assertEquals(JlptLevelUtil.JlptLevel.N3, result)
    }

    @Test
    fun testMixedJlptAndStandaloneTags() {
        val result = JlptLevelUtil.getLevel("noun, n3, jlpt-1")
        assertEquals(JlptLevelUtil.JlptLevel.N1, result)
    }

    @Test
    fun testNoJlptTagReturnsNull() {
        val result = JlptLevelUtil.getLevel("noun, verb")
        assertNull(result)
    }

    @Test
    fun testEmptyTagsReturnsNull() {
        val result = JlptLevelUtil.getLevel("")
        assertNull(result)
    }

    // fromDbValue — used by the UI after JLPT is stored as a dedicated column

    @Test
    fun testFromDbValueN1() {
        assertEquals(JlptLevelUtil.JlptLevel.N1, JlptLevelUtil.fromDbValue(1))
    }

    @Test
    fun testFromDbValueN5() {
        assertEquals(JlptLevelUtil.JlptLevel.N5, JlptLevelUtil.fromDbValue(5))
    }

    @Test
    fun testFromDbValueZeroReturnsNull() {
        assertNull(JlptLevelUtil.fromDbValue(0))
    }

    @Test
    fun testFromDbValueOutOfRangeReturnsNull() {
        assertNull(JlptLevelUtil.fromDbValue(6))
        assertNull(JlptLevelUtil.fromDbValue(-1))
    }

    // extractAsInt — used by the parser during dictionary import

    @Test
    fun testExtractAsIntJlptTag() {
        assertEquals(5, JlptLevelUtil.extractAsInt("ichi1 news1 jlpt-5"))
        assertEquals(1, JlptLevelUtil.extractAsInt("jlpt-1 adj-i"))
    }

    @Test
    fun testExtractAsIntNoFalsePositiveOnJlpt3000() {
        assertEquals(0, JlptLevelUtil.extractAsInt("freq jlpt-3000"))
    }

    @Test
    fun testExtractAsIntEmptyReturnsZero() {
        assertEquals(0, JlptLevelUtil.extractAsInt(""))
        assertEquals(0, JlptLevelUtil.extractAsInt("noun verb"))
    }

    @Test
    fun testExtractAsIntJlptNFormat() {
        assertEquals(2, JlptLevelUtil.extractAsInt("jlpt-n2 noun"))
    }
}
