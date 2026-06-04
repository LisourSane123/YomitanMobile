package com.yomitanmobile.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KanaUtilsTest {

    @Test
    fun `pure hiragana word is hiragana-only`() {
        assertTrue(KanaUtils.isHiraganaOnly("たべる"))
        assertTrue(KanaUtils.isHiraganaOnly("ありがとう"))
    }

    @Test
    fun `small kana and voiced marks count as hiragana`() {
        assertTrue(KanaUtils.isHiraganaOnly("きょう"))   // small ょ
        assertTrue(KanaUtils.isHiraganaOnly("がっこう")) // small っ + voiced が
    }

    @Test
    fun `word with kanji is not hiragana-only`() {
        assertFalse(KanaUtils.isHiraganaOnly("食べる"))
        assertFalse(KanaUtils.isHiraganaOnly("学校"))
    }

    @Test
    fun `katakana is not hiragana-only`() {
        assertFalse(KanaUtils.isHiraganaOnly("テレビ"))
        // prolonged-sound mark lives in the katakana block, so even an
        // otherwise-hiragana string containing it is rejected.
        assertFalse(KanaUtils.isHiraganaOnly("らーめん"))
    }

    @Test
    fun `latin and blank are not hiragana-only`() {
        assertFalse(KanaUtils.isHiraganaOnly("taberu"))
        assertFalse(KanaUtils.isHiraganaOnly(""))
        assertFalse(KanaUtils.isHiraganaOnly("   "))
    }

    @Test
    fun `surrounding whitespace is trimmed before checking`() {
        assertTrue(KanaUtils.isHiraganaOnly("  たべる  "))
    }
}
