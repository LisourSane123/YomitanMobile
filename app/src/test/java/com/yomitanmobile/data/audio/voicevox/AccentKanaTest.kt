package com.yomitanmobile.data.audio.voicevox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AccentKanaTest {

    @Test
    fun `drop position marks the mora it falls after`() {
        assertEquals("ツキツケ'ル", AccentKana.accented("つきつける", "4"))
        assertEquals("ハ'シ", AccentKana.accented("はし", "1"))
    }

    @Test
    fun `heiban marks the last mora`() {
        assertEquals("シンゾク'", AccentKana.accented("しんぞく", "0"))
        assertEquals("ハシ'", AccentKana.accented("はし", "0"))
    }

    @Test
    fun `small kana belong to the mora before them`() {
        assertEquals("キョ'ウダイ", AccentKana.accented("きょうだい", "1"))
        assertEquals(listOf("シュ", "ッ", "セ", "キ"), AccentKana.morae("シュッセキ"))
    }

    @Test
    fun `the first of several accents is the one the card draws`() {
        assertEquals("ハ'シ", AccentKana.accented("はし", "1,0"))
    }

    /**
     * The notation is a closed set of morae, not katakana: the engine refuses
     * ー, ヂ, ヅ, ヲ, ヰ and ヱ outright, and a refusal used to mean a card with
     * no recording at all — 続く and every loanword with a long vowel. Each is
     * written as the mora that says the same thing, and the mora COUNT does not
     * change, so the accent still falls where the pitch dictionary put it.
     */
    @Test
    fun `long vowels are written out as the vowel they lengthen`() {
        assertEquals("コオヒ'イ", AccentKana.accented("コーヒー", "3"))
        assertEquals("ラ'アメン", AccentKana.accented("ラーメン", "1"))
        // ー is a mora of its own, so キャーキャー is four and a drop of 4 is final.
        assertEquals("キャアキャア'", AccentKana.accented("キャーキャー", "4"))
        assertEquals("キャ'アキャア", AccentKana.accented("キャーキャー", "1"))
    }

    @Test
    fun `the kana the notation does not know become the ones it does`() {
        assertEquals("ツズク'", AccentKana.accented("つづく", "0"))
        assertEquals("チジコマ'ル", AccentKana.accented("ちぢこまる", "4"))
        assertEquals("オ'ンナ", AccentKana.accented("ヲンナ", "1"))
        assertEquals("イ'ナカ", AccentKana.accented("ヰナカ", "1"))
    }

    @Test
    fun `a mora count that survives the rewriting keeps the accent in place`() {
        // Four morae before and after: the drop still falls on ヒ/ヒイ's vowel.
        assertEquals(4, AccentKana.morae(AccentKana.spellable("コーヒー")!!).size)
        assertEquals(4, AccentKana.morae("コーヒー").size)
    }

    @Test
    fun `no usable accent gives no notation`() {
        assertNull(AccentKana.accented("はし", ""))
        assertNull(AccentKana.accented("はし", "5"))
        assertNull(AccentKana.accented("食べる", "2"))
        assertNull(AccentKana.accented("", "0"))
    }
}
