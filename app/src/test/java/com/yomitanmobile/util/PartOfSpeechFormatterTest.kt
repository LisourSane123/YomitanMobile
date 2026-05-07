package com.yomitanmobile.util

import org.junit.Assert.assertEquals
import org.junit.Test

class PartOfSpeechFormatterTest {

    @Test
    fun formatTags_mapsCommonGrammarLabels() {
        val labels = PartOfSpeechFormatter.formatTags(
            listOf("noun v1 vt vs adj-i")
        )

        assertEquals(
            listOf(
                "noun",
                "1-dan verb",
                "transitive verb",
                "suru verb",
                "i-adjective"
            ),
            labels
        )
    }

    @Test
    fun formatTags_supportsWhitespaceSeparatedJitendexTags() {
        val labels = PartOfSpeechFormatter.formatTags(
            listOf("v1 vt suru translative jlpt-n5")
        )

        assertEquals(
            listOf(
                "1-dan verb",
                "transitive verb",
                "suru verb"
            ),
            labels
        )
    }
}
