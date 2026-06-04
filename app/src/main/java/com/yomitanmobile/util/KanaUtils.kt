package com.yomitanmobile.util

/**
 * Small helpers for classifying Japanese kana strings.
 */
object KanaUtils {

    /**
     * True when [text] is non-blank and made up entirely of hiragana
     * characters (the U+3040–U+309F Hiragana block — includes the small
     * kana and the voiced/semi-voiced marks, but NOT katakana, kanji, the
     * prolonged-sound mark ー, or latin/ascii).
     *
     * Used to decide whether to attach a front-context sentence to an Anki
     * card: kana-only words give the learner no kanji to recall, so the
     * surrounding sentence is the only disambiguating context worth showing
     * on the front. Words written with kanji are excluded.
     */
    fun isHiraganaOnly(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return false
        return trimmed.all { Character.UnicodeBlock.of(it) == Character.UnicodeBlock.HIRAGANA }
    }
}
