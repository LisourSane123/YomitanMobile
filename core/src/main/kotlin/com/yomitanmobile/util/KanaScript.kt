package com.yomitanmobile.util

/**
 * The other way of writing the same sounds.
 *
 * Japanese spells a word in hiragana or in katakana and the dictionary files
 * it under one of them: コーヒー, ベッド and ラーメン are katakana headwords,
 * こと and ください hiragana ones. A reader typing on a phone gets whichever
 * script the IME was in, so a search for こーひー found nothing until the user
 * converted the text to katakana by hand — which is the sort of work the app
 * is supposed to do for them.
 */
object KanaScript {

    /** Distance between a hiragana character and its katakana twin. */
    private const val SCRIPT_OFFSET = 0x60

    private val HIRAGANA = 'ぁ'..'ゖ'
    private val KATAKANA = 'ァ'..'ヶ'

    /** The vowel each kana ends on, for resolving a long vowel mark. */
    private val VOWEL_OF = buildMap {
        val rows = mapOf(
            'あ' to "あかさたなはまやらわがざだばぱぁゃゎ",
            'い' to "いきしちにひみりぎじぢびぴぃ",
            'う' to "うくすつぬふむゆるぐずづぶぷぅゅ",
            'え' to "えけせてねへめれげぜでべぺぇ",
            'お' to "おこそとのほもよろをごぞどぼぽぉょ"
        )
        for ((vowel, kana) in rows) for (ch in kana) put(ch, vowel)
    }

    fun isHiragana(ch: Char): Boolean = ch in HIRAGANA
    fun isKatakana(ch: Char): Boolean = ch in KATAKANA

    fun toKatakana(value: String): String = buildString(value.length) {
        for (ch in value) append(if (isHiragana(ch)) ch + SCRIPT_OFFSET else ch)
    }

    fun toHiragana(value: String): String = buildString(value.length) {
        for (ch in value) append(if (isKatakana(ch)) ch - SCRIPT_OFFSET else ch)
    }

    /**
     * Spellings of [query] worth searching besides the query itself.
     *
     * Three shapes, and never more than three queries in total:
     *
     * - the same text in the other script (こーひー → コーヒー, ベッド → べっど);
     * - a katakana form with repeated vowels folded into the long-vowel mark,
     *   because that is what romaji input produces: "koohii" becomes こおひい,
     *   whose katakana is コオヒイ, while the word is コーヒー;
     * - a hiragana form with ー spelled out as a vowel, for the reverse case.
     *
     * Text with no kana, or with both scripts already in it, is left alone —
     * there is nothing to flip, and a mixed 食べモノ is the user being precise.
     */
    fun spellingVariants(query: String): List<String> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return emptyList()
        // Kana and nothing else. 食べる is written the way it is written, and
        // タベル is not a word — only an all-kana query is a spelling choice.
        if (!trimmed.all { isHiragana(it) || isKatakana(it) || it in "ー・ｰ" }) return emptyList()
        val hasHiragana = trimmed.any { isHiragana(it) }
        val hasKatakana = trimmed.any { isKatakana(it) }
        if (hasHiragana == hasKatakana) return emptyList()

        val out = LinkedHashSet<String>(2)
        if (hasHiragana) {
            val katakana = toKatakana(trimmed)
            out += katakana
            out += foldLongVowels(katakana)
        } else {
            val hiragana = toHiragana(trimmed)
            out += hiragana
            out += expandLongVowels(hiragana)
        }
        return out.filter { it != trimmed }
    }

    /**
     * コオヒイ → コーヒー: a vowel kana repeating the vowel of the kana before
     * it is the long-vowel mark. おう is included (トウキョウ → トーキョー);
     * a word that really does spell the vowel out still matches through the
     * unfolded variant, which is searched alongside this one.
     */
    fun foldLongVowels(katakana: String): String = buildString(katakana.length) {
        for (ch in katakana) {
            val previous = lastOrNull()
            val vowel = previous?.let { VOWEL_OF[toHiragana(it.toString()).first()] }
            val isLong = vowel != null && (
                ch == toKatakana(vowel.toString()).first() ||
                    (vowel == 'お' && ch == 'ウ') ||
                    (vowel == 'え' && ch == 'イ')
                )
            append(if (isLong) 'ー' else ch)
        }
    }

    /** らーめん → らあめん, for a katakana word the user typed with a ー. */
    fun expandLongVowels(hiragana: String): String = buildString(hiragana.length) {
        for (ch in hiragana) {
            if (ch != 'ー') {
                append(ch)
                continue
            }
            val vowel = lastOrNull()?.let { VOWEL_OF[it] }
            if (vowel != null) append(vowel) else append(ch)
        }
    }
}
