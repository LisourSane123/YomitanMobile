package com.yomitanmobile.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PartsOfSpeechFormatterTest {

    @Test
    fun expandsIchidanTransitiveVerb() {
        val result = PartsOfSpeechFormatter.format("v1 vt")
        assertEquals("ichidan verb, transitive verb", result)
    }

    @Test
    fun expandsGodanWithReadableParadigm() {
        val result = PartsOfSpeechFormatter.format("v5u vt")
        assertEquals("godan verb (う), transitive verb", result)
    }

    @Test
    fun expandsIAdjective() {
        assertEquals("i-adjective", PartsOfSpeechFormatter.format("adj-i"))
    }

    @Test
    fun stripsFrequencyMarkers() {
        val result = PartsOfSpeechFormatter.format("v1 vt ichi1 news1 spec1")
        assertEquals("ichidan verb, transitive verb", result)
    }

    @Test
    fun stripsJlptTags() {
        val result = PartsOfSpeechFormatter.format("v1, jlpt-5, jlpt-n2")
        assertEquals("ichidan verb", result)
    }

    @Test
    fun stripsNfFrequencyTags() {
        val result = PartsOfSpeechFormatter.format("n nf12 nf48")
        assertEquals("noun", result)
    }

    @Test
    fun deduplicatesRepeatedTokens() {
        // The parser stores definitionTags + rules, both of which often contain v1.
        val result = PartsOfSpeechFormatter.format("v1 vt, v1, ichi1 news1")
        assertEquals("ichidan verb, transitive verb", result)
    }

    @Test
    fun unknownTokenPassesThroughUnchanged() {
        val result = PartsOfSpeechFormatter.format("v1 unknown-tag")
        assertTrue(result.contains("ichidan verb"))
        assertTrue(result.contains("unknown-tag"))
    }

    @Test
    fun emptyInputReturnsEmpty() {
        assertEquals("", PartsOfSpeechFormatter.format(""))
        assertEquals("", PartsOfSpeechFormatter.format("   "))
    }

    @Test
    fun onlyFrequencyTagsResolveToEmpty() {
        assertEquals("", PartsOfSpeechFormatter.format("ichi1 news1 jlpt-5"))
    }
}
