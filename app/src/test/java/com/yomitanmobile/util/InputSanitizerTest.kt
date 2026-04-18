package com.yomitanmobile.util

import org.junit.Assert.assertEquals
import org.junit.Test

class InputSanitizerTest {

    @Test
    fun escapeHtml_escapesSpecialCharacters() {
        val raw = "<b>test & \"quote\" 'single'</b>"

        val escaped = InputSanitizer.escapeHtml(raw)
        val expected = """&lt;b&gt;test &amp; &quot;quote&quot; &#x27;single&#x27;&lt;/b&gt;"""

        assertEquals(expected, escaped)
    }

    @Test
    fun sanitizeDeckName_removesDangerousCharactersAndDefaultsOnBlank() {
        val sanitized = InputSanitizer.sanitizeDeckName("  Mining<script>::Deck|Name  ")

        assertEquals("Miningscript::DeckName", sanitized)
        assertEquals("Mining Deck", InputSanitizer.sanitizeDeckName("   "))
    }
}
