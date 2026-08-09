package com.yomitanmobile.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class JapaneseTokenizerTest {

    private fun lexiconOf(vararg words: String): JapaneseTokenizer.Lexicon {
        val set = words.toSet()
        return JapaneseTokenizer.Lexicon { it in set }
    }

    private fun baseForms(text: String, lexicon: JapaneseTokenizer.Lexicon): List<String> =
        JapaneseTokenizer.tokenize(text, lexicon).map { it.baseForm }

    @Test
    fun `longest match wins over shorter words inside it`() {
        val lexicon = lexiconOf("東京", "都", "東京都", "行く")
        assertEquals(listOf("東京都"), baseForms("東京都", lexicon))
    }

    @Test
    fun `inflected verbs resolve to their dictionary form`() {
        val lexicon = lexiconOf("食べる", "食", "寿司", "を")
        val tokens = JapaneseTokenizer.tokenize("寿司を食べました。", lexicon)
        val byBase = tokens.associateBy { it.baseForm }

        assertTrue("食べる missing: ${tokens.map { it.baseForm }}", "食べる" in byBase)
        assertEquals("食べました", byBase.getValue("食べる").surface)
        assertTrue(byBase.getValue("食べる").wasInflected)
        // 食 must NOT be counted separately — it was consumed by 食べました.
        assertFalse("食" in byBase)
    }

    @Test
    fun `occurrences are counted across inflections`() {
        val lexicon = lexiconOf("走る")
        val tokens = JapaneseTokenizer.tokenize("走る、走った、走ります", lexicon)
        assertEquals(1, tokens.size)
        assertEquals(3, tokens.first().count)
    }

    @Test
    fun `non-japanese text and unknown words are skipped`() {
        val lexicon = lexiconOf("猫")
        val tokens = JapaneseTokenizer.tokenize("Hello, 世界! 猫 123", lexicon)
        assertEquals(listOf("猫"), tokens.map { it.baseForm })
    }

    @Test
    fun `single hiragana particles are not counted as words`() {
        // They are genuine dictionary entries, so the lexicon matches them —
        // the tokeniser has to drop them itself or every deck starts with は.
        val lexicon = lexiconOf("は", "が", "を", "猫")
        assertEquals(listOf("猫"), baseForms("猫は", lexicon))
    }

    @Test
    fun `a match never runs past the end of a japanese stretch`() {
        val lexicon = lexiconOf("犬", "猫", "犬猫")
        // "犬 猫" separated by a space must not merge into 犬猫.
        assertEquals(listOf("犬", "猫"), baseForms("犬 猫", lexicon))
    }

    @Test
    fun `kana spelling of a kanji word is found through the reading`() {
        // The lexicon holds readings too, so a text writing みる resolves.
        val lexicon = lexiconOf("見る", "みる")
        assertEquals(listOf("みる"), baseForms("みるだけ", lexicon))
    }

    @Test
    fun `empty text yields nothing`() {
        assertTrue(JapaneseTokenizer.tokenize("", lexiconOf("猫")).isEmpty())
    }
}
