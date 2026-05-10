package com.yomitanmobile.domain.model

import org.junit.Assert.assertEquals
import org.junit.Test

class CardSectionTest {

    @Test
    fun defaultOrderRoundTrips() {
        val order = CardSection.defaultOrder()
        val encoded = CardSection.encode(order)
        assertEquals(order, CardSection.decode(encoded))
    }

    @Test
    fun decodeRestoresExactOrder() {
        val custom = listOf(
            CardSection.MEANING,
            CardSection.PITCH,
            CardSection.KANJI,
            CardSection.SENTENCE,
            CardSection.SUMMARY,
            CardSection.AUDIO
        )
        val encoded = CardSection.encode(custom)
        assertEquals(custom, CardSection.decode(encoded))
    }

    @Test
    fun decodeAppendsMissingSections() {
        // An older save with only some sections — all current sections
        // should still be present after decode, with missing ones at the
        // tail so an upgrade doesn't silently hide them.
        val partial = "meaning,pitch"
        val decoded = CardSection.decode(partial)
        assertEquals(CardSection.MEANING, decoded[0])
        assertEquals(CardSection.PITCH, decoded[1])
        assertEquals(
            CardSection.values().toSet(),
            decoded.toSet()
        )
    }

    @Test
    fun decodeIgnoresUnknownTokens() {
        val withGarbage = "meaning,bogus,pitch,bogus2"
        val decoded = CardSection.decode(withGarbage)
        assertEquals(CardSection.MEANING, decoded[0])
        assertEquals(CardSection.PITCH, decoded[1])
        // All the rest are appended.
        assertEquals(CardSection.values().size, decoded.size)
    }

    @Test
    fun decodeDeduplicates() {
        val withDupes = "meaning,pitch,meaning,pitch"
        val decoded = CardSection.decode(withDupes)
        assertEquals(CardSection.MEANING, decoded[0])
        assertEquals(CardSection.PITCH, decoded[1])
        assertEquals(CardSection.values().size, decoded.size)
    }

    @Test
    fun decodeBlankReturnsDefault() {
        assertEquals(CardSection.defaultOrder(), CardSection.decode(null))
        assertEquals(CardSection.defaultOrder(), CardSection.decode(""))
        assertEquals(CardSection.defaultOrder(), CardSection.decode("   "))
    }
}
