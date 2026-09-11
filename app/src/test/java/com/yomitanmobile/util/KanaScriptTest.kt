package com.yomitanmobile.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KanaScriptTest {

    @Test
    fun `a hiragana query is also searched in katakana`() {
        // The word is filed under コーヒー; the phone was in hiragana.
        assertTrue("コーヒー" in KanaScript.spellingVariants("こーひー"))
        assertTrue("ベッド" in KanaScript.spellingVariants("べっど"))
    }

    @Test
    fun `a katakana query is also searched in hiragana`() {
        assertTrue("べっど" in KanaScript.spellingVariants("ベッド"))
    }

    @Test
    fun `romaji spelling of a long vowel finds the word written with the mark`() {
        // "koohii" converts to こおひい, and the dictionary says コーヒー.
        assertTrue("コーヒー" in KanaScript.spellingVariants("こおひい"))
        assertTrue("ラーメン" in KanaScript.spellingVariants("らあめん"))
        // And the reverse, for a katakana word the user typed with the mark.
        assertTrue("らあめん" in KanaScript.spellingVariants("ラーメン"))
    }

    @Test
    fun `text with nothing to flip is left alone`() {
        // A word written with kanji is written that way: タベル is not a
        // spelling of 食べる, and flipping it would search for nothing.
        assertEquals(emptyList<String>(), KanaScript.spellingVariants("食べる"))
        assertEquals(emptyList<String>(), KanaScript.spellingVariants("食べモノ"))
        assertEquals(emptyList<String>(), KanaScript.spellingVariants("コーヒーが"))
        assertEquals(emptyList<String>(), KanaScript.spellingVariants(""))
    }

    @Test
    fun `the long-vowel mark resolves to the vowel of the kana before it`() {
        assertEquals("コーヒー", KanaScript.foldLongVowels("コオヒイ"))
        assertEquals("トーキョー", KanaScript.foldLongVowels("トオキョウ"))
        assertEquals("らあめん", KanaScript.expandLongVowels("らーめん"))
    }
}
