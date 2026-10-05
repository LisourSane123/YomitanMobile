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

    /**
     * 様 names people too — エリス様 is how a servant says it — but it also
     * follows ordinary words (神様, 王子様, お客様), so it only counts after a
     * name-shaped token: two characters or more, or katakana. 神 stays.
     */
    private fun honorificSamaAt(sentence: String, at: Int, token: String): Boolean =
        sentence.startsWith("様", at) && (token.length >= 2 || isKatakana(token[0]))

    /** The word list token boundaries are tested against. */
    fun interface Lexicon {
        fun contains(surface: String): Boolean

        /**
         * Where the frequency lists put this surface; 0 when they do not rank
         * it at all.
         *
         * A rank, not a yes/no: 「〜があった」 offers あう (172), あつ and ある
         * (15) at the same derivation depth, all three real words, and only
         * the numbers say the text meant ある. The default of 0 means "no
         * frequency data", which turns every preference below off — including
         * for tests that build a lexicon out of a bare set.
         */
        fun rank(surface: String): Int = 0

        /** Ranked, and inside the band of forms people actually use. */
        fun isCommon(surface: String): Boolean = rank(surface) in 1..COMMON_RANK

        /**
         * Whether [rank] can answer at all. False with no frequency list
         * installed, and for the bare-set lexicons tests build — every rule
         * that reads a number has to fall back to "keep it" then, or a
         * frequency-less install would quietly lose words (ゲーム out of
         * ゲームセンター among them).
         */
        val ranksAvailable: Boolean get() = false
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
     *
     * ね and よ are here for the same reason one step further: いいよ is a
     * JMdict entry and took 17 occurrences off いい. か is deliberately absent,
     * since it would take なにか apart into なに and か.
     */
    private const val CASE_PARTICLES = "はをがもへにねよ"

    /**
     * Particles a short match may have swallowed from the FRONT, for
     * [losesToParticleSplit]. Wider than [CASE_PARTICLES]: の, か, で and と
     * cannot be split off the end of a word (なにか, どこか) but do open the
     * fragments a novel scan was full of — のみ out of 「クラスのみんな」,
     * かあ out of 「なにかあった」, といい out of 「しないといけない」.
     */
    private const val LEADING_PARTICLES = "はをがもへにねよのかでと"

    /** Longest match [losesToParticleSplit] second-guesses. */
    private const val MAX_PARTICLE_FRAGMENT = 3

    /**
     * Rank at which a form counts as one people use. Everything JPDB-class
     * lists reach past this — 今日は at 296 050, 急いで at 81 833 — is a
     * spelling the corpus barely sees, and loses to a common reading of the
     * same characters.
     */
    const val COMMON_RANK = 30_000

    /**
     * A kana spelling the frequency lists rank this well AS WRITTEN joins the
     * lexicon even when the dictionary does not call the word "usually kana".
     * Past it the kana form is an accident of the text, and letting it in
     * brings back the fragments the kana-reading rule removed — になう, ranked
     * 14 382 in kana, would read 「そうになった」 as 担う again.
     */
    const val KANA_WRITTEN_RANK = 10_000

    /**
     * A sentence this long with no kana at all is not Japanese. One volume of
     * a series shipped with a Chinese translation interleaved, and 同学, 所以
     * and 不知 became cards.
     */
    private const val KANALESS_SENTENCE_LENGTH = 8

    /**
     * The verbs that carry the language. Their kana forms collide with rarer
     * words at every turn — 「〜があった」 deconjugates to あう (会う, ranked
     * 172), あつ and ある, and the frequency lists cannot settle it because
     * they rank the KANJI spellings (在る at 4 553) while the text writes the
     * kana. Whenever one of these is among the readings, it is the reading.
     */
    private val CORE_VERBS = setOf(
        "ある", "いる", "する", "くる", "いく", "いう", "なる", "みる", "くれる",
        "おる", "しまう", "もらう", "あげる"
    )

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
        "されません", "させる", "させて", "させた", "させない", "させます",
        // は + する: 「反対はしない」, 「そんな気はしなかった」. Read as words
        // this is the particle は followed by 端 ("the end of a street"), which
        // is what one novel got 16 cards for.
        "はしない", "はしなかった", "はします", "はしません", "はしても"
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
            if (sentence.count { isJapanese(it) } >= KANALESS_SENTENCE_LENGTH && sentence.none { isKana(it) }) return
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
                        if (len == 1 && isCounterAfterDigit(sentence, i)) continue
                        // A lone kanji right after katakana is the tail of a
                        // compound, not a word: ミグルド族, パンク系, バス停.
                        if (len == 1 && i > 0 && isKatakana(sentence[i - 1])) continue
                        if (isRareKatakanaPiece(sentence, i, len, runEnd, lexicon)) continue
                        val candidate = sentence.substring(i, i + len)
                        if (isWordPlusParticle(candidate, lexicon)) continue
                        if (losesToParticleSplit(sentence, i, len, runEnd, lexicon)) continue
                        val hit = resolved.getOrPut(candidate) { resolve(candidate, lexicon) }
                        // Only a DERIVED reading is second-guessed: 行く in
                        // 「行くんだ」 is a word, 兄く in 「兄くん」 is not.
                        if (hit != null && hit != candidate && endsInsideHonorific(sentence, i + len)) continue
                        // A derived reading that ends by taking the だ of a
                        // grammar chain: 「言ってるだろう」 read 言ってるだ as
                        // 言う + copula and left ろう ("six") — 552 cards.
                        // A match that ends by taking the だ of a grammar chain:
                        // 「言ってるだろう」 read 言ってるだ as 言う + copula and
                        // 「なんだろうか」 read なんだ, both leaving ろう ("six")
                        // behind — 552 cards in one series, 41 in another.
                        if (hit != null && candidate.last() == 'だ' && candidate.length > 1 &&
                            grammarFormAt(sentence, i + len - 1, runEnd) != null
                        ) continue
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
                    if (followedByHonorific(sentence, i + matchedLength, surface)) {
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

    /**
     * True when a match ending at [end] takes the first part of an honorific
     * with it. 「兄くん」 matched 兄く, which the adverbial rule turned into 兄い
     * ("elder brother", archaic) — 628 cards in one series, from a text that
     * never writes 兄い at all.
     */
    private fun endsInsideHonorific(sentence: String, end: Int): Boolean {
        for (honorific in HONORIFIC_SUFFIXES) {
            for (split in 1 until honorific.length) {
                val start = end - split
                if (start > 0 && sentence.startsWith(honorific, start)) return true
            }
        }
        return false
    }

    /**
     * A counter standing right after a number — 「１位」, 「５秒」, 「１階」.
     * The digits are not Japanese characters, so the counter was left as a
     * one-character word of its own and became a card for "throne", "second"
     * and "storey". Kanji numerals are handled by NoiseRules.isBareNumber,
     * which never sees these because the digit is not part of the token.
     */
    /**
     * A piece of a longer katakana run that the corpus barely knows.
     *
     * Katakana is how a story writes its names, and longest match finds
     * dictionary words inside them: グレイラット gave 75 cards for グレイ and 75
     * for ラット, ルーデウス (the protagonist) gave 222 for デウス, シルフィエット
     * gave 169 for シルフ, フィリップ 89 for リップ. A piece that covers the
     * whole run is left alone — so is a common word inside a compound, because
     * ゲーム out of ゲームセンター is still the word the reader needs.
     */
    private fun isRareKatakanaPiece(
        sentence: String,
        start: Int,
        length: Int,
        runEnd: Int,
        lexicon: Lexicon
    ): Boolean {
        val end = start + length
        if (!isKatakana(sentence[start])) return false
        // Either the match is all katakana and sits inside a longer run, or it
        // simply STARTS inside one: シュンと out of 「バシュンという」, キッと out
        // of 「バキバキッと」, デンと out of the town ウィーデン — the same cut,
        // one kana later.
        val startsInsideRun = start > 0 && isKatakana(sentence[start - 1])
        val allKatakana = (start until end).all { isKatakana(sentence[it]) }
        val runsPastEnd = allKatakana && end < runEnd && isKatakana(sentence[end])
        if (!startsInsideRun && !runsPastEnd) return false
        if (!lexicon.ranksAvailable) return false
        return lexicon.rank(sentence.substring(start, end)) !in 1..KATAKANA_PIECE_RANK
    }

    /** How common a katakana word has to be to be read out of a longer run. */
    private const val KATAKANA_PIECE_RANK = 20_000

    private fun isCounterAfterDigit(sentence: String, start: Int): Boolean {
        val previous = sentence.getOrNull(start - 1)
        if (sentence[start] in COUNTER_KANJI && previous != null && isDigit(previous)) return true
        // The mirror image: a prefix in front of a number — 全20問, 各５点,
        // 第3話 — left 全, 各 and 第 standing as words of their own.
        val next = sentence.getOrNull(start + 1)
        return sentence[start] in NUMBER_PREFIX_KANJI && next != null && isDigit(next)
    }

    private fun isDigit(c: Char): Boolean = c.isDigit() || c in '０'..'９'

    private const val NUMBER_PREFIX_KANJI = "全各第約計総"

    private const val COUNTER_KANJI = "位秒階人年本枚冊回分時歳個匹台点件度杯頭羽話巻週番倍円"

    private fun followedByHonorific(sentence: String, at: Int, token: String): Boolean =
        HONORIFIC_SUFFIXES.any { sentence.startsWith(it, at) } || honorificSamaAt(sentence, at, token)

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
            stemOfCommonerVerb(surface, lexicon)?.let { return it }
            return surface
        }
        if (surface.length < 2) return null
        if (!isKana(surface.last())) return null
        // A verb left on its bare stem before a comma or another verb —
        // 「言われ、」, 「全貌を現し、」, 「出され」. Nothing matched it, so
        // longest match fell back to the lone kanji, and 言, 現 and 出 became
        // cards. Only for a form carrying a kanji: in kana the same shapes are
        // everywhere.
        if (surface.any { isKanji(it) }) {
            bestDeconjugation(surface, lexicon)?.let { return it }
            stemVerb(surface, lexicon)?.let { return it }
            return null
        }
        // Among the deconjugations that are words, the common one wins.
        // 続けている offers 続けて (an adverb JMdict lists, ranked nowhere) one
        // step before 続ける (ranked 196), and taking the first hit spent ten
        // occurrences of the verb on the adverb. A rare candidate is still
        // used when nothing common matches.
        return bestDeconjugation(surface, lexicon)
    }

    /**
     * The likeliest word [surface] is an inflection of.
     *
     * Ranked candidates beat unranked ones, because a form the corpus has
     * never seen is rarely what a sentence meant. Among ranked candidates the
     * shallowest derivation wins — 続けている reaches 続ける in two steps and
     * 続く in three, and it is the verb that was inflected — and at equal depth
     * the commonest one does: あった offers あう (172) and ある (15).
     */
    private fun bestDeconjugation(surface: String, lexicon: Lexicon): String? {
        var bestForm: String? = null
        var bestDepth = Int.MAX_VALUE
        var bestRank = Int.MAX_VALUE
        var fallback: String? = null
        for (candidate in JapaneseDeconjugator.analyze(surface)) {
            val form = candidate.baseForm
            if (form == surface || !lexicon.contains(form)) continue
            if (form in CORE_VERBS) return form
            val rank = lexicon.rank(form)
            if (rank <= 0) {
                if (fallback == null) fallback = form
                continue
            }
            val better = candidate.depth < bestDepth ||
                (candidate.depth == bestDepth && rank < bestRank)
            if (better) {
                bestForm = form
                bestDepth = candidate.depth
                bestRank = rank
            }
        }
        return bestForm ?: fallback
    }

    /**
     * The deconjugation to prefer over a rare entry of the same spelling. Two
     * characters is enough: 来た is a JMdict entry of its own and was taking 50
     * occurrences off 来る in one novel.
     */
    /**
     * The verb a bare stem stands for, when the stem is not a dictionary word:
     * a godan 連用形 (現し → 現す) or an ichidan stem (言われ → 言われる, and
     * on to 言う). The commoner candidate wins, the same as everywhere else.
     */
    private fun stemVerb(surface: String, lexicon: Lexicon): String? {
        // An ichidan stem ends on an e- or i-row kana (言われ, 見). Adding る
        // to anything else invents verbs: 失礼す + る made 失礼する swallow the
        // す of すぎる and left ぎる behind, ten cards of it.
        val ichidan = if (surface.last() in ICHIDAN_STEM_ENDINGS) listOf(surface + "る") else emptyList()
        // bareStemBases offers +る for any ending; only a godan stem (i-row,
        // same length: 現し → 現す) is taken from it.
        val godan = JapaneseDeconjugator.bareStemBases(surface).filter { it.length == surface.length }
        val candidates = godan + ichidan
        var best: String? = null
        var bestRank = Int.MAX_VALUE
        for (candidate in candidates) {
            // A derived form the dictionary happens to list (撃たれる, ranked
            // nowhere) gives way to the verb it was derived from when that
            // one is common.
            val derived = bestDeconjugation(candidate, lexicon)
            val base = when {
                lexicon.contains(candidate) && lexicon.isCommon(candidate) -> candidate
                derived != null && lexicon.isCommon(derived) -> derived
                lexicon.contains(candidate) -> candidate
                else -> derived ?: continue
            }
            val rank = lexicon.rank(base).takeIf { it > 0 } ?: (Int.MAX_VALUE - 1)
            if (best == null || rank < bestRank) {
                best = base
                bestRank = rank
            }
        }
        return best
    }

    /**
     * A 連用形 the dictionary lists as a noun of its own, met where the text
     * means the verb: はね上げ, 絡み合い, 取りに行く. The noun 上げ is ranked
     * 12 460 and the verb 上げる is common, so nine cards in one novel went to
     * a word the text never used. Only a clearly rarer noun gives way —
     * 終わり, 考え and 違い are words people use and keep their own reading.
     */
    private fun stemOfCommonerVerb(surface: String, lexicon: Lexicon): String? {
        val nounRank = lexicon.rank(surface)
        if (nounRank !in STEM_NOUN_RARE_RANK..COMMON_RANK) return null
        return JapaneseDeconjugator.bareStemBases(surface)
            .filter { lexicon.contains(it) }
            .minByOrNull { lexicon.rank(it).takeIf { r -> r > 0 } ?: Int.MAX_VALUE }
            ?.takeIf { verb -> lexicon.rank(verb) in 1 until nounRank / STEM_NOUN_RATIO }
    }

    private const val ICHIDAN_STEM_ENDINGS = "えけげせぜてでねへべぺめれいきぎしじちぢにひびぴみり"

    private const val STEM_NOUN_RARE_RANK = 5_000
    private const val STEM_NOUN_RATIO = 4

    private fun commonDeconjugation(surface: String, lexicon: Lexicon): String? {
        if (surface.length < 2 || !isKana(surface.last())) return null
        bestDeconjugation(surface, lexicon)?.takeIf { lexicon.isCommon(it) }?.let { return it }
        // A bare 連用形 the dictionary happens to list as a noun — 出し (出汁),
        // 置き — is the verb in running text.
        return JapaneseDeconjugator.bareStemBases(surface)
            .firstOrNull { lexicon.contains(it) && lexicon.isCommon(it) }
    }

    /**
     * True when a short match that STARTS with a case particle is beaten by
     * reading that particle on its own.
     *
     * 「これはしっかり」 matches はし (端, "the end of a street", ranked 2 039)
     * before it ever sees しっかり, and 「先輩はしなやか」, 「それはしんどい」,
     * 「反対はしない」 do the same — ten cards for 端 in one novel. Dropping the
     * particle and finding a LONGER word behind it settles it. はなし is safe:
     * it is three characters, so the rule never looks at it.
     */
    private fun losesToParticleSplit(
        sentence: String,
        start: Int,
        length: Int,
        runEnd: Int,
        lexicon: Lexicon
    ): Boolean {
        if (length > MAX_PARTICLE_FRAGMENT || sentence[start] !in LEADING_PARTICLES) return false
        val next = start + 1
        val maxLength = minOf(MAX_TOKEN_LENGTH, runEnd - next)
        // A word behind the particle that runs PAST the end of this match.
        // It used to have to be longer than the whole match, which let
        // 「ここにいる」 read にい (兄) over に + いる, since いる is no longer
        // than にい — only further along.
        for (len in maxLength downTo maxOf(2, length)) {
            if (resolve(sentence.substring(next, next + len), lexicon) != null) return true
        }
        // A grammar chain behind the particle counts the same: 「のだろう」 is
        // の + だろう, and reading のだ first left ろう ("six") behind — 552
        // cards of it in one series.
        grammarFormAt(sentence, next, runEnd)?.let { form ->
            if (next + form.length > start + length) return true
        }
        // …or exactly the rest of it, when the corpus settles which reading is
        // meant: the match is a form no list calls common and the rest is a
        // common word. 「真っ赤にして」 is に + して (する), not the rare
        // expression にして "at (a time)"; はなし stays whole, because it is
        // the common one of the two.
        val rest = length - 1
        if (rest >= 2 && !lexicon.isCommon(sentence.substring(start, start + length))) {
            val restBase = resolve(sentence.substring(next, next + rest), lexicon)
            if (restBase != null && lexicon.isCommon(restBase)) return true
        }
        return false
    }

    /**
     * True when [candidate] is a word with a case particle stuck to it and the
     * word alone is in the dictionary — see [CASE_PARTICLES]. Such a match is
     * skipped so the loop falls through to the word itself, and the particle
     * is then read on its own (and dropped, being a single kana).
     */
    private fun isWordPlusParticle(candidate: String, lexicon: Lexicon): Boolean {
        if (candidate.last() in CASE_PARTICLES && candidate.length >= 2) {
            val withoutParticle = candidate.dropLast(1)
            // A one-character remainder only counts when it is a kanji: 何を is
            // 何 plus を, but なに must not come apart into な and に.
            val usable = withoutParticle.length > 1 || isKanji(withoutParticle[0])
            // One kanji plus は that the corpus ranks as a word of its own is
            // that word: 実は is "actually", while 実 on its own is "truth" —
            // six cards of the wrong word per novel. The same for 最も
            // ("most"), 誰も ("nobody"). Not に: the ranked blends with に are
            // mostly a noun and its particle (本に, 横に).
            val frozen = withoutParticle.length == 1 && candidate.last() in FROZEN_AFTER_KANJI &&
                lexicon.isCommon(candidate)
            if (usable && !frozen && lexicon.contains(withoutParticle)) return true
        }
        // Particles of more than one character — 上から, 家まで. Only when the
        // blend is one the frequency lists do not know: これから is ranked 249
        // and is a word of its own, while 上から is ranked nowhere.
        if (lexicon.rank(candidate) > 0) return false
        // か and の cannot be split off a word that IS ranked (なにか, どこか),
        // but a blend no list knows is the word plus the particle: いるか
        // ("dolphin") out of 「ヤツがいるか」 is いる + か.
        if (candidate.length >= 3 && candidate.last() in UNRANKED_SPLIT_PARTICLES &&
            lexicon.contains(candidate.dropLast(1))
        ) return true
        for (particle in LONG_PARTICLES) {
            if (!candidate.endsWith(particle) || candidate.length <= particle.length) continue
            val stem = candidate.dropLast(particle.length)
            if (stem.length > 1 || isKanji(stem[0])) {
                if (lexicon.contains(stem)) return true
            }
        }
        return false
    }

    private const val UNRANKED_SPLIT_PARTICLES = "かの"

    private const val FROZEN_AFTER_KANJI = "はも"

    /** Particles that trail a noun and are longer than one character. */
    private val LONG_PARTICLES = listOf("から", "まで", "より", "など", "だけ", "ほど", "ばかり")

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
