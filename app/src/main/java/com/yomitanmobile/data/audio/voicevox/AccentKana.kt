package com.yomitanmobile.data.audio.voicevox

import com.yomitanmobile.util.KanaScript

/**
 * A reading with its pitch accent written in, in the notation VOICEVOX reads
 * (AquesTalk-style kana: `'` after the accented mora).
 *
 * This is what makes a synthesised card say what the card prints. Given the
 * headword, a TTS picks a plausible reading and a plausible accent (行った,
 * 一日, 開く); given the dictionary's reading with the accent from the same
 * pitch dictionary that draws the card's pitch diagram, it has nothing left to
 * guess.
 */
object AccentKana {

    private const val SMALL = "ャュョァィゥェォヮ"

    /**
     * `ツキツケ'ル` for つきつける with pitch "4". Pitch is the pitch column as
     * the parser stores it — drop positions, comma-separated when a word has
     * several; the first is the one the card draws. Heiban (0) marks the last
     * mora: inside a single word that is how VOICEVOX spells "no fall".
     *
     * Null when the reading is not plain kana or the pitch is missing or does
     * not fit the word — the caller then lets VOICEVOX analyse the text itself.
     */
    fun accented(reading: String, pitch: String): String? {
        val kana = KanaScript.toKatakana(reading.trim())
        if (kana.isEmpty() || !kana.all { KanaScript.isKatakana(it) || it == 'ー' }) return null
        val position = pitch.split(',').firstOrNull()?.trim()?.toIntOrNull() ?: return null
        val morae = morae(kana)
        val drop = if (position == 0) morae.size else position
        if (drop !in 1..morae.size) return null
        return morae.take(drop).joinToString("") + "'" + morae.drop(drop).joinToString("")
    }

    /** Katakana split into morae: a small kana belongs to the one before it. */
    fun morae(kana: String): List<String> {
        val out = ArrayList<String>(kana.length)
        for (ch in kana) {
            if (ch in SMALL && out.isNotEmpty()) out[out.lastIndex] = out.last() + ch else out += ch.toString()
        }
        return out
    }
}
