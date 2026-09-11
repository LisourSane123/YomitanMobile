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
        val firstOffset: Int = 0,
        /**
         * How often the word was followed by an honorific suffix — 池くん,
         * 平田さん. In a novel that is what a person's name looks like, and
         * it is the only signal there is for a name the dictionary also lists
         * as an ordinary word.
         */
        val honorificHits: Int = 0
    )

    /**
     * Suffixes that mark what precedes them as a person. 様 and 殿 are absent
     * on purpose: they attach to roles and objects too (神様, お客様).
     */
    private val HONORIFIC_SUFFIXES = listOf("くん", "君", "さん", "ちゃん", "先輩", "先生", "氏")

    /** The word list token boundaries are tested against. */
    fun interface Lexicon {
        fun contains(surface: String): Boolean

        /**
         * Whether a frequency list ranks this surface as a form people
         * actually use.
         *
         * The default says yes to everything, which turns the preference off
         * for callers that have no frequency data — including every test that
         * builds a lexicon out of a bare set.
         */
        fun isCommon(surface: String): Boolean = true
    }

    /**
     * Case particles that end a "word + particle" entry.
     *
     * JMdict lists これは, それを, 今日は (the greeting) and dozens more as
     * entries, and longest match takes them over the word plus its particle —
     * so 今日 lost 14 of its 27 occurrences in one novel to こんにちは, and
     * これは became a card. Splitting is right whenever what precedes the
     * particle is a word in its own right; こんにちは survives because こんにち
     * is not a spelling anything is filed under.
     */
    private const val CASE_PARTICLES = "はをがもへに"

    /**
     * Rank at which a form counts as one people use. Everything JPDB-class
     * lists reach past this — 今日は at 296 050, 急いで at 81 833 — is a
     * spelling the corpus barely sees, and loses to a common reading of the
     * same characters.
     */
    const val COMMON_RANK = 30_000

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
        "ですが", "ますが", "しれない",
        // The さ-stem of する. The deconjugator does reach する from される, but
        // it returns its candidates sorted, and さる ("to leave", offered by the
        // potential rule) sorts first and is a real dictionary word — so every
        // "…されています" in a novel became a card for 去る, 62 of them in one
        // book. Consumed whole here, exactly like だった.
        "される", "されて", "された", "されない", "されます", "されました",
        "されません", "させる", "させて", "させた", "させない", "させます"
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
                firstOffset = value.firstOffset,
                honorificHits = value.honorificHits
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
                    // A lone kanji sitting against another kanji is the tail of
                    // something the dictionary does not have — a name, a rare
                    // compound — not a word of its own. Without this, 朱音 (a
                    // character's name, 698 occurrences in one novel) becomes
                    // 698 cards for 朱 "unit of weight" and 708 for 音 "sound",
                    // the two commonest "words" in the deck.
                    for (len in maxLength downTo 1) {
                        if (!isSelfContained(sentence, i, len, runEnd)) continue
                        val candidate = sentence.substring(i, i + len)
                        if (isWordPlusParticle(candidate, lexicon)) continue
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
                    if (followedByHonorific(sentence, i + matchedLength)) {
                        entry.honorificHits++
                    }
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

    /**
     * Whether a match of [length] characters at [start] is a word in its own
     * right, rather than a piece broken off something longer.
     *
     * Two shapes are rejected, both of them "the dictionary did not have the
     * long thing, so longest match settled for a fragment of it":
     *
     * - a lone KANJI with a kanji neighbour. 朱音, a name JMdict does not
     *   list, otherwise became 699 cards for 朱 and 708 for 音.
     * - one or two KATAKANA with a katakana neighbour. アイツ is written in
     *   katakana for emphasis and is filed under あいつ, so the scan took アイ
     *   ("love") eleven times.
     *
     * A longer katakana match is left alone: ゲーム out of ゲームセンター is
     * still the word the reader needs, while アイ out of アイツ is not.
     */
    private fun isSelfContained(sentence: String, start: Int, length: Int, runEnd: Int): Boolean {
        val end = start + length
        val previous = sentence.getOrNull(start - 1)
        val next = if (end < runEnd) sentence[end] else null
        if (length == 1 && isKanji(sentence[start])) {
            val gluedToKanji = (previous != null && isKanji(previous)) ||
                (next != null && isKanji(next))
            if (gluedToKanji) return false
        }
        if (length <= 2 && (start until end).all { isKatakana(sentence[it]) }) {
            val gluedToKatakana = (previous != null && isKatakana(previous)) ||
                (next != null && isKatakana(next))
            if (gluedToKatakana) return false
        }
        return true
    }

    private fun followedByHonorific(sentence: String, at: Int): Boolean =
        HONORIFIC_SUFFIXES.any { sentence.startsWith(it, at) }

    private class MutableToken(
        val surface: String,
        val wasInflected: Boolean,
        val firstOffset: Int
    ) {
        var count: Int = 0
        var sentence: String = ""
        var honorificHits: Int = 0
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
        if (lexicon.contains(surface)) {
            // A dictionary entry that is really a frozen inflection — 急いで,
            // 頑張って, 知らない, 食べられる are all JMdict entries — swallows
            // the verb it was built from. When the corpus says the entry is
            // rare and the verb behind it is common, the verb is what the
            // text meant: err towards the form people actually use.
            if (!lexicon.isCommon(surface)) {
                commonDeconjugation(surface, lexicon)?.let { return it }
            }
            return surface
        }
        if (surface.length < 2) return null
        if (!isKana(surface.last())) return null
        // Among the deconjugations that are words, the common one wins.
        // 続けている offers 続けて (an adverb JMdict lists, ranked nowhere) one
        // step before 続ける (ranked 196), and taking the first hit spent ten
        // occurrences of the verb on the adverb. A rare candidate is still
        // used when nothing common matches.
        var fallback: String? = null
        for (candidate in JapaneseDeconjugator.candidateForms(surface)) {
            if (candidate == surface || !lexicon.contains(candidate)) continue
            if (lexicon.isCommon(candidate)) return candidate
            if (fallback == null) fallback = candidate
        }
        return fallback
    }

    /** The first deconjugation of [surface] that the frequency lists call common. */
    private fun commonDeconjugation(surface: String, lexicon: Lexicon): String? {
        // Two characters is enough: 来た is a JMdict entry of its own and was
        // taking 50 occurrences off 来る in one novel.
        if (surface.length < 2 || !isKana(surface.last())) return null
        for (candidate in JapaneseDeconjugator.candidateForms(surface)) {
            if (candidate == surface) continue
            if (lexicon.contains(candidate) && lexicon.isCommon(candidate)) return candidate
        }
        return null
    }

    /**
     * True when [candidate] is a word with a case particle stuck to it and the
     * word alone is in the dictionary — see [CASE_PARTICLES]. Such a match is
     * skipped so the loop falls through to the word itself, and the particle
     * is then read on its own (and dropped, being a single kana).
     */
    private fun isWordPlusParticle(candidate: String, lexicon: Lexicon): Boolean {
        if (candidate.length < 2) return false
        if (candidate.last() !in CASE_PARTICLES) return false
        val withoutParticle = candidate.dropLast(1)
        // A one-character remainder only counts when it is a kanji: 何を is 何
        // plus を, but なに must not come apart into な and に.
        if (withoutParticle.length == 1 && !isKanji(withoutParticle[0])) return false
        return lexicon.contains(withoutParticle)
    }

    /**
     * Drops the segmentation noise a card deck never wants: single hiragana
     * (は, が, を — all of them are dictionary entries), the prolonged-sound
     * mark, and bare repetition marks.
     */
    private fun isWorthCounting(base: String, surface: String): Boolean {
        if (base.isBlank()) return false
        // One kana is never a word worth a card — not in hiragana (は, が) and
        // not in katakana either, where the debris comes from names and
        // abbreviations chopped by longest match (シ, セ out of シセ).
        if (base.length == 1 && isKana(base[0])) return false
        if (base.length == 1 && base[0] in "ーヽヾゝゞ々〆") return false
        return surface.isNotBlank()
    }

    fun isJapanese(c: Char): Boolean =
        isKana(c) ||
            c in '一'..'鿿' || // CJK unified ideographs
            c in '㐀'..'䶿' || // CJK extension A
            c == '々' || c == '〆' || c == '〻'

    /** Kanji, plus the iteration mark that stands in for one (人々). */
    fun isKanji(c: Char): Boolean =
        c in '一'..'鿿' || c in '㐀'..'䶿' || c == '々'

    /** Katakana, including the prolonged-sound mark and its iteration marks. */
    fun isKatakana(c: Char): Boolean =
        c in 'ァ'..'ヺ' || c == 'ー' || c == 'ヽ' || c == 'ヾ'

    fun isKana(c: Char): Boolean =
        isHiragana(c) ||
            c in 'ァ'..'ヺ' || // katakana
            c == 'ー' || c == 'ヽ' || c == 'ヾ' || c == 'ヴ' || c == 'ヵ' || c == 'ヶ'

    fun isHiragana(c: Char): Boolean = c in 'ぁ'..'ゖ' || c == 'ゝ' || c == 'ゞ'
}
