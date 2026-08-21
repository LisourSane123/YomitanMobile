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
 *
 * Text is walked sentence by sentence so every word can carry the sentence it
 * was first seen in: that sentence goes on the front of the card, which is the
 * whole point of mining from material you actually watched or read.
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
        val wasInflected: Boolean,
        /**
         * Sentence the word was first met in, ready for the card front.
         * Empty when no sentence of a usable length contained it.
         */
        val sentence: String = "",
        /**
         * Character offset of the first occurrence. Drives the "a word that
         * shows up in chapter 1 is worth learning before one that shows up in
         * the last volume" half of the card ordering.
         */
        val firstOffset: Int = 0
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

    /**
     * Copula and auxiliary chains, matched before the dictionary.
     *
     * [JapaneseDeconjugator] cannot reach these: it refuses candidates shorter
     * than two characters (deliberately — the search screen would drown in
     * one-kana hits), so だった never reduces to だ. What it does instead is
     * apply the godan ~った rule and offer だつ, which some dictionary really
     * does list, and the scan quietly proposes a card for it.
     *
     * Every form here is at least three characters and cannot open a content
     * word, so consuming it whole is safe — たい and ない are deliberately
     * absent, since they would eat the front of たいへん and ないよう.
     *
     * They are still counted, not dropped: the planner rejects them as
     * [com.yomitanmobile.domain.usecase.TextScanPlanner.FUNCTION_WORDS], which
     * keeps them in the "grammar" skip bucket and in the known-coverage figure
     * where they belong.
     */
    val GRAMMAR_FORMS = setOf(
        "だった", "だったら", "だろう", "であった", "である", "でした", "でしょう",
        "じゃない", "じゃなかった", "ではない", "ではなかった", "じゃなくて",
        "ました", "ません", "ませんでした", "なかった", "なければ", "なくて",
        "かもしれない", "かもしれません", "ということ", "というのは", "だけど",
        "ですが", "ますが", "しれない"
    )

    private val MAX_GRAMMAR_LENGTH = GRAMMAR_FORMS.maxOf { it.length }

    /** The longest grammar form starting at [start], or null. */
    private fun grammarFormAt(text: String, start: Int, end: Int): String? {
        for (len in minOf(MAX_GRAMMAR_LENGTH, end - start) downTo 3) {
            val candidate = text.substring(start, start + len)
            if (candidate in GRAMMAR_FORMS) return candidate
        }
        return null
    }

    /**
     * A sentence worth putting on a card front. Shorter than this is usually
     * an interjection ("はい。"), longer is a wall of text on a flashcard.
     */
    private const val MIN_SENTENCE_LENGTH = 6
    private const val MAX_SENTENCE_LENGTH = 90

    /** Sentence terminators, Japanese and Latin, plus the line break. */
    private const val SENTENCE_BREAKS = "。！？!?\n"

    fun tokenize(text: String, lexicon: Lexicon): List<Token> {
        val accumulator = Accumulator()
        accumulator.add(text, lexicon, offsetBase = 0)
        return accumulator.tokens()
    }

    /**
     * Collects tokens across several documents (a season of subtitles, a
     * series of EPUBs) into one word list.
     *
     * Counts add up, while the sentence and the first-occurrence offset come
     * from the earliest document the word appears in — feed the files in the
     * order they are meant to be watched or read.
     */
    class Accumulator {
        private val counts = LinkedHashMap<String, MutableToken>()
        private var consumed = 0

        /** Total characters handed to the accumulator so far. */
        val totalLength: Int get() = consumed

        fun add(text: String, lexicon: Lexicon, offsetBase: Int = consumed) {
            // Resolution is by far the hot path and text repeats heavily
            // (particles, names, the same verb in the same form), so every
            // surface → base decision is memoised across the whole document.
            val resolved = HashMap<String, String?>()
            forEachSentence(text) { sentence, sentenceStart ->
                scanSentence(sentence, sentenceStart + offsetBase, lexicon, resolved)
            }
            consumed = offsetBase + text.length
        }

        fun tokens(): List<Token> = counts.map { (base, value) ->
            Token(
                baseForm = base,
                surface = value.surface,
                count = value.count,
                wasInflected = value.wasInflected,
                sentence = value.sentence,
                firstOffset = value.firstOffset
            )
        }

        private fun scanSentence(
            sentence: String,
            sentenceOffset: Int,
            lexicon: Lexicon,
            resolved: HashMap<String, String?>
        ) {
            val usableSentence = sentence.takeIf {
                it.length in MIN_SENTENCE_LENGTH..MAX_SENTENCE_LENGTH
            }.orEmpty()

            var i = 0
            val length = sentence.length
            while (i < length) {
                if (!isJapanese(sentence[i])) {
                    i++
                    continue
                }
                // Never let a match run past the end of the Japanese stretch.
                var runEnd = i
                while (runEnd < length && isJapanese(sentence[runEnd])) runEnd++
                val maxLength = minOf(MAX_TOKEN_LENGTH, runEnd - i)

                var matchedLength = 0
                var base: String? = null
                var surface = ""
                // Copula and auxiliary chains first: they must be consumed
                // whole, or longest-match hands them to whatever entry happens
                // to share their letters (だった deconjugates to だつ, and a
                // card for 脱つ is worse than no card).
                val grammar = grammarFormAt(sentence, i, runEnd)
                if (grammar != null) {
                    matchedLength = grammar.length
                    base = grammar
                    surface = grammar
                } else {
                    for (len in maxLength downTo 1) {
                        val candidate = sentence.substring(i, i + len)
                        val hit = resolved.getOrPut(candidate) { resolve(candidate, lexicon) }
                        if (hit != null) {
                            matchedLength = len
                            base = hit
                            surface = candidate
                            break
                        }
                    }
                }

                if (base == null) {
                    // Nothing in the dictionary starts here (a name, a typo, an
                    // emoji-adjacent character): skip one character and retry.
                    i++
                    continue
                }

                if (isWorthCounting(base, surface)) {
                    val entry = counts.getOrPut(base) {
                        MutableToken(surface, base != surface, sentenceOffset + i)
                    }
                    entry.count++
                    // A word first met in a too-long or too-short sentence still
                    // deserves a usable one, so the first suitable sentence wins
                    // even if it is not the first occurrence.
                    if (entry.sentence.isEmpty() && usableSentence.isNotEmpty()) {
                        entry.sentence = usableSentence
                    }
                }
                i += matchedLength
            }
        }
    }

    private class MutableToken(
        val surface: String,
        val wasInflected: Boolean,
        val firstOffset: Int
    ) {
        var count: Int = 0
        var sentence: String = ""
    }

    /**
     * Splits on sentence terminators, handing each sentence to [block] with its
     * offset. Closing quotes and brackets stay with the sentence they end, so a
     * line of dialogue keeps its 」.
     */
    private inline fun forEachSentence(text: String, block: (String, Int) -> Unit) {
        var start = 0
        var i = 0
        while (i < text.length) {
            if (text[i] in SENTENCE_BREAKS) {
                var end = i + 1
                while (end < text.length && text[end] in TRAILING_CHARS) end++
                block(text.substring(start, end).trim(), start)
                i = end
                start = end
            } else {
                i++
            }
        }
        if (start < text.length) block(text.substring(start).trim(), start)
    }

    private const val TRAILING_CHARS = "」』）\")〉》】"

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
