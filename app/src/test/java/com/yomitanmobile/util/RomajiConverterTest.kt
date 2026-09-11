package com.yomitanmobile.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Romaji is one of the three ways into the dictionary, and a wrong reading is
 * indistinguishable from "no such word" — the search simply comes back empty.
 * The two cases that were wrong are the first two tests.
 */
class RomajiConverterTest {

    @Test
    fun `nn keeps the na-row mora that follows it`() {
        // "nn" + vowel is ん plus a な-row mora; consuming both n's turned
        // konnichiwa into こんいちわ, annai into あんい — neither is a word.
        assertEquals("こんにちわ", RomajiConverter.toHiragana("konnichiwa"))
        assertEquals("あんない", RomajiConverter.toHiragana("annai"))
        assertEquals("さんねん", RomajiConverter.toHiragana("sannen"))
        assertEquals("おんなのこ", RomajiConverter.toHiragana("onnanoko"))
    }

    @Test
    fun `nn before a consonant is a plain n`() {
        assertEquals("しんぶん", RomajiConverter.toHiragana("shinnbun"))
        assertEquals("こん", RomajiConverter.toHiragana("konn"))
    }

    @Test
    fun `macrons are long vowels, not letters`() {
        assertEquals("がっこう", RomajiConverter.toHiragana("gakkō"))
        assertEquals("とうきょう", RomajiConverter.toHiragana("tōkyō"))
        assertEquals("りょうり", RomajiConverter.toHiragana("ryōri"))
        assertTrue(RomajiConverter.isRomaji("tōkyō"))
    }

    @Test
    fun `the ordinary cases still hold`() {
        assertEquals("たべます", RomajiConverter.toHiragana("tabemasu"))
        assertEquals("しんぶん", RomajiConverter.toHiragana("shinbun"))
        assertEquals("きっぷ", RomajiConverter.toHiragana("kippu"))
        assertEquals("ちょっと", RomajiConverter.toHiragana("chotto"))
        assertEquals("ん", RomajiConverter.toHiragana("n"))
        assertEquals("にほん", RomajiConverter.toHiragana("nihon"))
    }
}
