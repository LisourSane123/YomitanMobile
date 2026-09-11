package com.yomitanmobile.util

/**
 * Splits a kana reading into morae — the unit a pitch-accent number counts in.
 *
 * Only the yōon small kana (ゃゅょ and the small vowels) attach to the kana in
 * front of them. The sokuon っ, the syllabic ん and the long-vowel mark ー are
 * each a mora of their own: コーヒー is コ・ー・ヒ・ー, four morae, which is the
 * only way an accent of [3] can mean anything at all. Folding っ and ー into
 * the previous kana — as both copies of this code used to — left がっこう with
 * three morae instead of four and コーヒー with two, so the diagram drew the
 * drop in the wrong place, or could not draw it.
 *
 * One implementation for the exported card and the detail screen: two copies
 * of a phonology rule drift, and the reader has no way to tell which of the two
 * diagrams is the honest one.
 */
object JapaneseMora {

    private val ATTACHING_KANA = setOf(
        'ゃ', 'ゅ', 'ょ', 'ぁ', 'ぃ', 'ぅ', 'ぇ', 'ぉ',
        'ャ', 'ュ', 'ョ', 'ァ', 'ィ', 'ゥ', 'ェ', 'ォ',
        'ゎ', 'ヮ'
    )

    fun split(reading: String): List<String> {
        val result = mutableListOf<String>()
        var i = 0
        while (i < reading.length) {
            val sb = StringBuilder()
            sb.append(reading[i])
            i++
            while (i < reading.length && reading[i] in ATTACHING_KANA) {
                sb.append(reading[i])
                i++
            }
            result.add(sb.toString())
        }
        return result
    }
}
