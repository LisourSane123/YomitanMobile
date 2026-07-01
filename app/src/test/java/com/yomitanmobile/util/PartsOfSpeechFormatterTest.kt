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

    @Test
    fun polishLabelsWhenNotEnglish() {
        assertEquals(
            "czasownik ichidan, czasownik przechodni",
            PartsOfSpeechFormatter.format("v1 vt", english = false)
        )
        assertEquals("rzeczownik", PartsOfSpeechFormatter.format("n", english = false))
        assertEquals("przymiotnik (i)", PartsOfSpeechFormatter.format("adj-i", english = false))
        assertEquals("przymiotnik (na)", PartsOfSpeechFormatter.format("adj-na", english = false))
    }

    @Test
    fun polishFallsBackToRawForUnknownCode() {
        val result = PartsOfSpeechFormatter.format("n unknown-tag", english = false)
        assertTrue(result.contains("rzeczownik"))
        assertTrue(result.contains("unknown-tag"))
    }

    @Test
    fun localizeUsageTagTranslatesKnownLabels() {
        assertEquals("kolokwializm", PartsOfSpeechFormatter.localizeUsageTag("colloq.", english = false))
        assertEquals("archaizm", PartsOfSpeechFormatter.localizeUsageTag("archaic", english = false))
        assertEquals("zwykle kaną", PartsOfSpeechFormatter.localizeUsageTag("usually kana", english = false))
    }

    @Test
    fun localizeUsageTagKeepsEnglishAndUnknowns() {
        assertEquals("colloq.", PartsOfSpeechFormatter.localizeUsageTag("colloq.", english = true))
        assertEquals("something-else", PartsOfSpeechFormatter.localizeUsageTag("something-else", english = false))
    }
}
