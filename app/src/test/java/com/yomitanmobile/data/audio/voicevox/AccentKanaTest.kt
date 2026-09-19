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

    @Test
    fun `katakana readings and long vowels are kept`() {
        assertEquals("コーヒ'ー", AccentKana.accented("コーヒー", "3"))
    }

    @Test
    fun `no usable accent gives no notation`() {
        assertNull(AccentKana.accented("はし", ""))
        assertNull(AccentKana.accented("はし", "5"))
        assertNull(AccentKana.accented("食べる", "2"))
        assertNull(AccentKana.accented("", "0"))
    }
}
