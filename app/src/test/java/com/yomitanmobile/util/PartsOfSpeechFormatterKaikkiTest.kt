package com.yomitanmobile.util

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The coarse part-of-speech codes the Wiktionary conversions use.
 *
 * They are what every English and Spanish card carries, and an unmapped code
 * is printed verbatim — so a missing entry here shows up as a bare "v" or
 * "prep" above the meaning.
 */
class PartsOfSpeechFormatterKaikkiTest {

    @Test
    fun `kaikki codes render as words, not codes`() {
        assertEquals("verb", PartsOfSpeechFormatter.format("v", english = true))
        assertEquals("adjective", PartsOfSpeechFormatter.format("adj", english = true))
        assertEquals("preposition", PartsOfSpeechFormatter.format("prep", english = true))
        assertEquals("czasownik", PartsOfSpeechFormatter.format("v", english = false))
        assertEquals("przymiotnik", PartsOfSpeechFormatter.format("adj", english = false))
    }

    @Test
    fun `the unknown placeholder is dropped rather than printed`() {
        assertEquals("", PartsOfSpeechFormatter.format("unknown", english = true))
        assertEquals("noun", PartsOfSpeechFormatter.format("unknown, n", english = true))
    }

    @Test
    fun `japanese codes are untouched`() {
        assertEquals("ichidan verb", PartsOfSpeechFormatter.format("v1", english = true))
        assertEquals("noun", PartsOfSpeechFormatter.format("n", english = true))
    }
}
