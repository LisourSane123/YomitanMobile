package com.yomitanmobile.util

/**
 * Splits running Japanese text into dictionary words.
 *
 * Japanese has no spaces, so "which words does this file contain" is a
 * segmentation problem. Rather than shipping a morphological analyser
 * (Kuromoji's IPADIC alone is ~5 MB and duplicates data the app already has),
 * this walks the text left to right taking the **longest match against the
 * installed dictionaries** — the same word list the search screen queries —
 * and falls back to [JapaneseDeconjugator] so 食べました resolves to 食べる
 * instead of being chopped into 食 + noise.
 *
 * The trade-off versus a real analyser: no part-of-speech context, so a
 * genuinely ambiguous boundary is resolved by "longest wins". For building a
 * vocabulary list that is the right bias — it prefers 東京都 over 東京 + 都 and
 * never invents a word that is not in the dictionary.
 */
object JapaneseTokenizer {

    /** One distinct word found in the text. */
    data class Token(
        /** Dictionary form — what a card would be made for. */
        val baseForm: String,
        /** Surface form as it first appeared in the text (食べました). */
        val surface: String,
        /** How many times it occurred. */
        val count: Int,
        /** True when the surface had to be deconjugated to reach the base form. */
        val wasInflected: Boolean
    )

    /** The word list token boundaries are tested against. */
    fun interface Lexicon {
        fun contains(surface: String): Boolean
    }

    /**
     * Longest match tried at a position. Long enough for compounds and set
     * phrases (取り返しのつかない), short enough that the per-position scan stays
     * cheap on a novel-sized text.
     */
    private const val MAX_TOKEN_LENGTH = 12

    fun tokenize(text: String, lexicon: Lexicon): List<Token> {
        if (text.isEmpty()) return emptyList()

        val counts = LinkedHashMap<String, MutableToken>()
        // Resolution is by far the hot path and text repeats heavily
        // (particles, names, the same verb in the same form), so every
        // surface → base decision is memoised for the whole document.
        val resolved = HashMap<String, String?>()

        var i = 0
        val length = text.length
        while (i < length) {
            if (!isJapanese(text[i])) {
                i++
                continue
            }
            // Never let a match run past the end of the Japanese stretch.
            var runEnd = i
            while (runEnd < length && isJapanese(text[runEnd])) runEnd++
            val maxLength = minOf(MAX_TOKEN_LENGTH, runEnd - i)

            var matchedLength = 0
            var base: String? = null
            var surface = ""
            for (len in maxLength downTo 1) {
                val candidate = text.substring(i, i + len)
                val hit = resolved.getOrPut(candidate) { resolve(candidate, lexicon) }
                if (hit != null) {
                    matchedLength = len
                    base = hit
                    surface = candidate
                    break
                }
            }

            if (base == null) {
                // Nothing in the dictionary starts here (a name, a typo, an
                // emoji-adjacent character): skip one character and retry.
                i++
                continue
            }

            if (isWorthCounting(base, surface)) {
                val entry = counts.getOrPut(base) { MutableToken(surface, base != surface) }
                entry.count++
            }
            i += matchedLength
        }

        return counts.map { (base, value) ->
            Token(
                baseForm = base,
                surface = value.surface,
                count = value.count,
                wasInflected = value.wasInflected
            )
        }
    }

    private class MutableToken(val surface: String, val wasInflected: Boolean) {
        var count: Int = 0
    }

    /**
     * Base form for a surface, or null when it is not a word.
     * Direct hit first; deconjugation only for forms that could be inflected
     * at all (2+ characters ending in kana), because [JapaneseDeconjugator] is
     * orders of magnitude more expensive than a hash lookup.
     */
    private fun resolve(surface: String, lexicon: Lexicon): String? {
        if (lexicon.contains(surface)) return surface
        if (surface.length < 2) return null
        if (!isKana(surface.last())) return null
        for (candidate in JapaneseDeconjugator.candidateForms(surface)) {
            if (candidate != surface && lexicon.contains(candidate)) return candidate
        }
        return null
    }

    /**
     * Drops the segmentation noise a card deck never wants: single hiragana
     * (は, が, を — all of them are dictionary entries), the prolonged-sound
     * mark, and bare repetition marks.
     */
    private fun isWorthCounting(base: String, surface: String): Boolean {
        if (base.isBlank()) return false
        if (base.length == 1 && isHiragana(base[0])) return false
        if (base.length == 1 && base[0] in "ーヽヾゝゞ々〆") return false
        return surface.isNotBlank()
    }

    fun isJapanese(c: Char): Boolean =
        isKana(c) ||
            c in '一'..'鿿' || // CJK unified ideographs
            c in '㐀'..'䶿' || // CJK extension A
            c == '々' || c == '〆' || c == '〻'

    fun isKana(c: Char): Boolean =
        isHiragana(c) ||
            c in 'ァ'..'ヺ' || // katakana
            c == 'ー' || c == 'ヽ' || c == 'ヾ' || c == 'ヴ' || c == 'ヵ' || c == 'ヶ'

    fun isHiragana(c: Char): Boolean = c in 'ぁ'..'ゖ' || c == 'ゝ' || c == 'ゞ'
}
