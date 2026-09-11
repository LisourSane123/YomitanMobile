package com.yomitanmobile.util

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A pitch-accent number counts morae, so the split has to agree with Japanese
 * phonology or the diagram draws the drop in the wrong place. The three cases
 * that used to be wrong — long vowel, sokuon and their combination — are the
 * first three tests.
 */
class JapaneseMoraTest {

    @Test
    fun `the long vowel mark is a mora of its own`() {
        // コーヒー is 4 morae with accent [3]; folded into コー・ヒー it was 2,
        // and a drop after the third mora could not be drawn at all.
        assertEquals(listOf("コ", "ー", "ヒ", "ー"), JapaneseMora.split("コーヒー"))
        assertEquals(listOf("ラ", "ー", "メ", "ン"), JapaneseMora.split("ラーメン"))
    }

    @Test
    fun `the sokuon is a mora of its own`() {
        assertEquals(listOf("が", "っ", "こ", "う"), JapaneseMora.split("がっこう"))
        assertEquals(listOf("い", "っ", "ぱ", "い"), JapaneseMora.split("いっぱい"))
        assertEquals(listOf("サ", "ッ", "カ", "ー"), JapaneseMora.split("サッカー"))
    }

    @Test
    fun `yoon attaches to the kana in front of it`() {
        assertEquals(listOf("きょ", "う"), JapaneseMora.split("きょう"))
        assertEquals(listOf("しゃ", "し", "ん"), JapaneseMora.split("しゃしん"))
        assertEquals(listOf("と", "う", "きょ", "う"), JapaneseMora.split("とうきょう"))
    }

    @Test
    fun `n is its own mora`() {
        assertEquals(listOf("せ", "ん", "せ", "い"), JapaneseMora.split("せんせい"))
        assertEquals(listOf("に", "ほ", "ん"), JapaneseMora.split("にほん"))
    }

    @Test
    fun `plain readings and edge cases`() {
        assertEquals(listOf("た", "べ", "る"), JapaneseMora.split("たべる"))
        assertEquals(emptyList<String>(), JapaneseMora.split(""))
    }
}
