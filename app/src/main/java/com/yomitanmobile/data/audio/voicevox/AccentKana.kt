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
     * The notation is not katakana: it is a closed set of morae, and what is
     * outside it is rejected outright — `ParseKanaException`, no audio, which
     * is how 続く (ツヅク) and every loanword with a long vowel (コーヒー) lost
     * their recording in silence. Probed against the engine, the gaps are the
     * long-vowel mark, the two yotsugana and the three kana modern Japanese
     * stopped writing; each has a spelling the parser does take and that says
     * the same word out loud. ヴ, ッ, ン and the small-kana combinations are
     * accepted as they are.
     */
    private val SUBSTITUTES = mapOf('ヂ' to 'ジ', 'ヅ' to 'ズ', 'ヲ' to 'オ', 'ヰ' to 'イ', 'ヱ' to 'エ')

    /** Which vowel each mora ends on, for writing ー out as that vowel. */
    private val VOWELS: Map<Char, Char> = buildMap {
        for ((vowel, row) in listOf(
            'ア' to "アカサタナハマヤラワガザダバパァャヮヷ",
            'イ' to "イキシチニヒミリヰギジヂビピィヸ",
            'ウ' to "ウクスツヌフムユルグズヅブプゥュヴ",
            'エ' to "エケセテネヘメレヱゲゼデベペェ",
            'オ' to "オコソトノホモヨロヲゴゾドボポォョヺ"
        )) for (ch in row) put(ch, vowel)
    }

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
        val morae = morae(spellable(kana) ?: return null)
        val drop = if (position == 0) morae.size else position
        if (drop !in 1..morae.size) return null
        return morae.take(drop).joinToString("") + "'" + morae.drop(drop).joinToString("")
    }

    /**
     * The same word in the morae the notation knows. ー becomes the vowel the
     * mora before it ends on — one mora either way, so the accent position it
     * is about to be written into still points at the same sound. Null when
     * something is left that the parser would reject anyway, so the caller
     * falls back to plain text rather than sending it a word it will refuse.
     */
    fun spellable(kana: String): String? {
        val out = StringBuilder(kana.length)
        for (ch in kana) {
            val mapped = when {
                ch == 'ー' -> VOWELS[out.lastOrNull() ?: return null] ?: return null
                else -> SUBSTITUTES[ch] ?: ch
            }
            out.append(mapped)
        }
        return out.toString()
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
