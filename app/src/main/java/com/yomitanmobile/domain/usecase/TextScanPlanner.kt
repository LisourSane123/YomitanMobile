package com.yomitanmobile.domain.usecase

import com.yomitanmobile.domain.model.GrammarSource
import com.yomitanmobile.domain.model.GrammarUse
import com.yomitanmobile.domain.model.MergedWordEntry
import com.yomitanmobile.domain.model.ScanToken
import com.yomitanmobile.domain.model.ScannedWord
import com.yomitanmobile.domain.model.TextScanFilters
import com.yomitanmobile.domain.model.TextScanPlan
import com.yomitanmobile.domain.model.TextScanSkipReason
import com.yomitanmobile.domain.model.TextScanSource
import com.yomitanmobile.util.JapaneseTokenizer
import kotlin.math.ln

/**
 * Turns "every word this file contains" into the list of cards worth creating.
 *
 * Pure Kotlin, mirroring [JlptDeckPlanner]: the filters run in a fixed order
 * and the FIRST rule that rejects a word owns the skip counter, so
 * `selected + skipped == distinct words` exactly. The database, AnkiDroid and
 * the tokeniser all sit behind the inputs.
 */
object TextScanPlanner {

    /**
     * Grammatical scaffolding. Every one of these is a legitimate dictionary
     * entry sitting at the very top of every frequency list, so without this
     * list the first hundred cards of any scanned deck are は, です and ます —
     * words nobody reading Japanese subtitles needs a card for.
     *
     * It is only the first half of the answer: a literal list can never cover
     * what longest-match segmentation actually produces (それでも, どころか,
     * にとって are single dictionary entries, not は + です). The tag-based
     * [WordFilterRules.isFunctionWord] catches those; this list stays for the
     * words whose tags alone would not give them away (する, なる, 見る) and for
     * text that resolves to no dictionary entry at all.
     */
    val FUNCTION_WORDS = setOf(
        // particles and particle-like endings
        "は", "が", "を", "に", "へ", "と", "で", "も", "の", "や", "か", "ね", "よ",
        "な", "わ", "ぞ", "ぜ", "さ", "から", "まで", "より", "など", "ので", "のに",
        "けど", "けれど", "けれども", "しか", "だけ", "ほど", "ばかり", "でも", "ても",
        "とか", "なら", "ながら", "つつ", "ものの", "ものか", "こそ", "さえ", "すら",
        // copulas, auxiliaries and their bases
        "だ", "です", "である", "ます", "ました", "ません", "ない", "ぬ", "たい",
        "らしい", "そうだ", "ようだ", "みたい", "だろう", "でしょう", "ござる",
        // the workhorse verbs
        "する", "なる", "ある", "いる", "おる", "居る", "有る", "在る", "為る",
        "できる", "出来る", "くる", "来る", "いく", "行く", "いう", "言う",
        "しまう", "おく", "置く", "みる", "見る", "くれる", "あげる", "もらう",
        "いただく", "頂く", "下さる", "くださる",
        // demonstratives and pronouns
        "これ", "それ", "あれ", "どれ", "この", "その", "あの", "どの",
        "ここ", "そこ", "あそこ", "どこ", "こう", "そう", "ああ", "どう",
        "こんな", "そんな", "あんな", "どんな", "わたし", "私", "あなた", "君",
        "僕", "俺", "彼", "彼女", "誰", "何", "なに", "なん",
        // The kana spellings of the same pronouns. A narrator who writes
        // himself オレ rather than 俺 was getting a card seen 509 times in one
        // novel; katakana is folded to hiragana before the lookup, so one
        // hiragana entry covers both.
        "おれ", "ぼく", "あたし", "きみ", "おまえ", "あいつ", "こいつ", "そいつ",
        "どいつ", "だれ", "われ", "うぬ",
        // high-frequency connectives and fillers
        "そして", "でも", "しかし", "だから", "また", "まだ", "もう", "とても",
        "ちょっと", "はい", "ええ", "うん", "いや", "あの", "その", "えっと",
        // Nominalisers, sentence-enders and suffixes that the tag rule cannot
        // reach, because each of them also has an ordinary noun sense in the
        // same entry (こと/事, もの/物, ため/為, とき/時, よう/様) or mixes a
        // content tag into the grammar one (なんて is `prt adv`). Every item
        // below is essential grammar in Tae Kim's sense — the first pages of
        // any grammar guide — and every one of them was sitting in the first
        // hundred cards of a real novel scan.
        "こと", "もの", "とき", "ため", "よう", "ところ", "うち", "はず", "つもり",
        "のか", "んだ", "なんだ", "なの", "なのだ", "なんて", "にも", "とも",
        "として", "とする", "について", "によって", "における",
        "くらい", "ぐらい", "だって", "でしょ", "まま", "ほうがいい",
        "いけない", "ならない", "なんで", "んです", "のです", "お前",
        // Third pass over the same scan: what was left in the first two
        // hundred cards once the rules above had run. All of it is N5-N4
        // grammar in BunPro's ordering — common enough that meeting it twice a
        // page is the norm, which is the opposite of the rare construction
        // this filter is meant to let through.
        "ないか", "よりも", "しかない", "ように", "様に", "でもない", "なんか",
        "んで", "をして", "気がする", "という", "と言う", "じゃ", "としても",
        "ことになる", "事になる", "かしら",
        // Honorific and pluralising suffixes: grammar attached to a name, and
        // a card for "Mr" helps nobody.
        "さん", "ちゃん", "くん", "様", "さま", "たち", "達", "ら"
    ) + JapaneseTokenizer.GRAMMAR_FORMS // the copula/auxiliary chains the tokeniser keeps whole

    /**
     * Rank at which a grammar word stops being scaffolding and starts being
     * something worth a card.
     *
     * Dropping everything the tags call grammar is wrong in one direction: a
     * particle ranked 15 is met on every page and needs no card, but a
     * construction ranked 8000 is met twice a book, which is exactly the
     * situation in which the reader does NOT know it. Frequency is the only
     * thing that separates the two, so the tag rule is gated on it and the
     * literal [FUNCTION_WORDS] list stays as the override for basics the
     * frequency lists do not rank at all.
     */
    const val GRAMMAR_KNOWN_RANK = 3000

    /**
     * How often an UNRANKED grammar word has to appear before it counts as
     * scaffolding — see [isEverydayGrammar]. Low on purpose: a blend like
     * これは shows up twenty times a book, while a construction the reader has
     * genuinely never met shows up once or twice.
     */
    const val GRAMMAR_UNRANKED_OCCURRENCES = 4

    /**
     * The same idea for grammar the lists DO rank, only further out: 様な at
     * rank 9 634 is still grammar, and twenty uses in one book means the
     * reader meets it constantly.
     */
    const val GRAMMAR_RARE_OCCURRENCES = 20

    /**
     * How many times a word has to be followed by an honorific before the
     * text is taken to be naming somebody with it. Three is enough to rule out
     * a coincidence (「お兄ちゃん」 after a noun) and low enough to catch a
     * side character.
     */
    const val NAME_HONORIFIC_HITS = 3

    /**
     * A one- or two-kana "word" that no frequency list ranks at all.
     *
     * Longest-match segmentation reaches for the longest dictionary entry at
     * each position, and JMdict contains short kana entries that are really
     * redirects or interjections — があ ("⟶ガー") swallowed the が of 必要がある
     * 65 times in one novel. Every genuinely useful two-kana word (こと, もの,
     * いい, やる) is ranked in the top few thousand, so "kana, tiny, and
     * unranked" is noise with no counterexamples.
     */
    private fun isSegmentationNoise(word: String, entry: MergedWordEntry): Boolean =
        word.length <= 2 &&
            word.none { JapaneseTokenizer.isKanji(it) } &&
            entry.frequency <= 0

    /**
     * Grammar the reader necessarily already lives with: tagged as a function
     * word AND ranked inside [GRAMMAR_KNOWN_RANK]. Unranked or rarer grammar
     * becomes a card.
     */
    private fun isEverydayGrammar(entry: MergedWordEntry, occurrences: Int): Boolean {
        if (!WordFilterRules.isFunctionWord(entry)) return false
        if (entry.frequency in 1..GRAMMAR_KNOWN_RANK) return true
        // An unranked grammar word is NOT a rare one. The lists rank とはいえ at
        // 248 980 and 〜ごとく at 4 750 — real constructions, however uncommon,
        // do get ranked. Rank 0 means the corpus has no such word at all,
        // which is what a word-plus-particle blend (これは, それを) or a frozen
        // inflection (知らない, 食べられる) is. How often THIS text uses it is
        // then the only signal left, and a blend used four times is
        // scaffolding while a construction met twice may well be new.
        if (entry.frequency == 0) return occurrences >= GRAMMAR_UNRANKED_OCCURRENCES
        // Ranked, but past the everyday band: 様な (ような written with the
        // kanji) sits at 9 634 and was used 75 times in one novel. A
        // construction the reader has genuinely not met does not come up
        // twenty times in one book — とはいえ, ranked far worse at 248 980,
        // appeared twelve.
        return occurrences >= GRAMMAR_RARE_OCCURRENCES
    }

    /**
     * A word this text uses as somebody's NAME and that is not a word
     * anywhere else.
     *
     * Novels are full of both kinds. 池 is a classmate in one book and a pond
     * in the language — the frequency lists rank it 3 770, the reader will
     * meet that word outside this novel, and the card stays. 平田 is a
     * classmate and nothing else: no list ranks it, and it exists here only as
     * a person. The honorific is what tells them apart from ordinary
     * vocabulary, since both are dictionary entries.
     *
     * Deliberately NOT "unranked and frequent": 指導室 and 敷地内 are unranked
     * compounds used ten times each in the same book and are perfectly good
     * cards.
     */
    private fun isNameOnly(token: ScanToken, entry: MergedWordEntry): Boolean =
        token.honorificHits >= NAME_HONORIFIC_HITS && entry.frequency <= 0

    /**
     * Whether the stoplist covers this word — the WORD, not the spelling it
     * happens to wear here.
     *
     * 俺 is on the list and オレ was not, so a novel whose narrator writes
     * himself in katakana produced a card seen 509 times. Katakana is folded
     * to hiragana and the entry's other written forms are checked too, which
     * also covers ワタシ, ボク and キミ.
     */
    private fun isStoplisted(word: String, entry: MergedWordEntry?): Boolean {
        if (word in FUNCTION_WORDS || word.katakanaToHiragana() in FUNCTION_WORDS) return true
        if (entry == null) return false
        if (entry.primaryExpression in FUNCTION_WORDS) return true
        return entry.alternativeExpressions.any { it in FUNCTION_WORDS }
    }

    private fun String.katakanaToHiragana(): String = buildString(length) {
        for (ch in this@katakanaToHiragana) {
            append(if (ch in 'ァ'..'ヶ') ch - 0x60 else ch)
        }
    }

    /**
     * Adds a word to the grammar counter if it is grammar at all.
     *
     * Three sources, kept apart on purpose: the literal stoplist, the tag rule,
     * and the grammar that survived because it is rare. Seeing which bucket a
     * structure landed in is the whole point — a construction in the third
     * bucket with 80 occurrences means [GRAMMAR_KNOWN_RANK] is drawn too low
     * for this reader, and one in the first with two occurrences means the
     * stoplist is eating something worth learning.
     */
    private fun recordGrammar(
        out: MutableList<GrammarUse>,
        word: String,
        occurrences: Int,
        entry: MergedWordEntry?
    ) {
        @Suppress("NAME_SHADOWING")
        val rank = entry?.frequency ?: 0
        val source = when {
            isStoplisted(word, entry) -> GrammarSource.STOPLIST
            entry == null -> return
            !WordFilterRules.isFunctionWord(entry) -> return
            isEverydayGrammar(entry, occurrences) -> GrammarSource.TAG_RULE
            else -> GrammarSource.KEPT
        }
        out += GrammarUse(word, occurrences, rank, source)
    }

    /**
     * One word, one card — spelled the way the book spells it.
     *
     * Two tokens can resolve to the same dictionary entry: the text writes
     * both 去る and さる, or both 持って来る and 持ってくる, and each reaches the
     * same headword. Left alone that is two cards for one word, and the deck
     * teaches the same thing twice.
     *
     * The surviving spelling is the one the TEXT used most, not the
     * dictionary's headword: the reader will meet the word again on the next
     * page in the book's spelling, and a card front they never see in the wild
     * is a card they will not recognise. The entry keeps everything else —
     * reading, senses, frequency — so only the front changes.
     *
     * Counts add up; the sentence and the earliness come from whichever
     * spelling appeared first.
     */
    private fun mergeByEntry(
        words: List<ScanToken>,
        entries: Map<String, MergedWordEntry>
    ): Pair<List<ScanToken>, Map<String, MergedWordEntry>> {
        val groups = LinkedHashMap<String, MutableList<ScanToken>>()
        val entryOf = HashMap<String, MergedWordEntry>()
        val unresolved = mutableListOf<ScanToken>()
        for (token in words) {
            val entry = entries[token.baseForm]
            if (entry == null) {
                unresolved += token
                continue
            }
            val key = entry.primaryExpression + "\u0000" + entry.reading
            groups.getOrPut(key) { mutableListOf() }.add(token)
            entryOf[key] = entry
        }

        val outWords = ArrayList<ScanToken>(groups.size + unresolved.size)
        val outEntries = HashMap<String, MergedWordEntry>(groups.size)
        for ((key, tokens) in groups) {
            val entry = entryOf.getValue(key)
            if (tokens.size == 1) {
                outWords += tokens[0]
                outEntries[tokens[0].baseForm] = entry
                continue
            }
            // The book's own spelling: most occurrences wins, ties go to the
            // form carrying kanji, so 去る beats さる at 3 occurrences each.
            val winner = tokens.maxWith(
                compareBy<ScanToken> { it.occurrences }
                    .thenBy { token -> token.baseForm.count { JapaneseTokenizer.isKanji(it) } }
            )
            val earliest = tokens.maxByOrNull { it.earliness } ?: winner
            outWords += ScanToken(
                baseForm = winner.baseForm,
                occurrences = tokens.sumOf { it.occurrences },
                sentence = earliest.sentence.ifBlank { winner.sentence },
                earliness = earliest.earliness,
                honorificHits = tokens.sumOf { it.honorificHits }
            )
            outEntries[winner.baseForm] = entry.copy(primaryExpression = winner.baseForm)
        }
        outWords += unresolved
        return outWords to outEntries
    }

    /**
     * @param words distinct words found in the text, in any order
     * @param entries dictionary entries resolved for those words, keyed by the
     *   base form the tokeniser produced. A missing key means the word is not
     *   in any installed dictionary.
     */
    fun plan(
        sources: List<TextScanSource>,
        words: List<ScanToken>,
        entries: Map<String, MergedWordEntry>,
        filters: TextScanFilters,
        totalTokenCount: Int,
        isInAnki: (MergedWordEntry) -> Boolean = { false },
        isMined: (MergedWordEntry) -> Boolean = { false },
        ankiScanUnavailable: Boolean = false
    ): TextScanPlan {
        val skipped = linkedMapOf<TextScanSkipReason, Int>()
        var knownTokens = 0
        fun reject(reason: TextScanSkipReason, occurrences: Int) {
            skipped[reason] = (skipped[reason] ?: 0) + 1
            if (reason == TextScanSkipReason.ALREADY_IN_ANKI ||
                reason == TextScanSkipReason.ALREADY_MINED ||
                reason == TextScanSkipReason.FUNCTION_WORD ||
                reason == TextScanSkipReason.ASSUMED_KNOWN
            ) {
                knownTokens += occurrences
            }
        }

        val merged = mergeByEntry(words, entries)
        val maxOccurrences = merged.first.maxOfOrNull { it.occurrences } ?: 1
        val mergedWords = merged.first
        val mergedEntries = merged.second

        val kept = mutableListOf<ScannedWord>()
        val grammar = mutableListOf<GrammarUse>()
        for (token in mergedWords) {
            val word = token.baseForm
            val occurrences = token.occurrences
            val entry = mergedEntries[word]
            // Counted before the filters run, so the tally is the same whether
            // or not the user has the grammar filter switched on.
            recordGrammar(grammar, word, occurrences, entry)
            when {
                filters.skipFunctionWords && isStoplisted(word, entry) ->
                    reject(TextScanSkipReason.FUNCTION_WORD, occurrences)
                entry == null ->
                    reject(TextScanSkipReason.NOT_IN_DICTIONARY, occurrences)
                isSegmentationNoise(word, entry) ->
                    reject(TextScanSkipReason.UNRANKED, occurrences)
                // Same reason, second source of truth: the dictionary's own
                // part-of-speech tags. Runs right after the lookup so a word
                // rejected as grammar is counted as grammar and not as, say,
                // "too few occurrences".
                filters.skipFunctionWords && isEverydayGrammar(entry, occurrences) ->
                    reject(TextScanSkipReason.FUNCTION_WORD, occurrences)
                occurrences < filters.minOccurrences ->
                    reject(TextScanSkipReason.TOO_FEW_OCCURRENCES, occurrences)
                entry.definitions.none { it.isNotBlank() } ->
                    reject(TextScanSkipReason.NO_DEFINITION, occurrences)
                filters.skipProperNames &&
                    (WordFilterRules.isProperName(entry) || isNameOnly(token, entry)) ->
                    reject(TextScanSkipReason.PROPER_NAME, occurrences)
                entry.frequency <= 0 && !filters.includeUnranked ->
                    reject(TextScanSkipReason.UNRANKED, occurrences)
                entry.frequency in 1..filters.assumeKnownTopRank ->
                    reject(TextScanSkipReason.ASSUMED_KNOWN, occurrences)
                filters.tier.maxRank > 0 && entry.frequency > filters.tier.maxRank ->
                    reject(TextScanSkipReason.TOO_RARE, occurrences)
                filters.skipArchaic && WordFilterRules.isArchaic(entry) ->
                    reject(TextScanSkipReason.ARCHAIC, occurrences)
                filters.skipAlreadyInAnki && isInAnki(entry) ->
                    reject(TextScanSkipReason.ALREADY_IN_ANKI, occurrences)
                filters.skipAlreadyMined && isMined(entry) ->
                    reject(TextScanSkipReason.ALREADY_MINED, occurrences)
                else -> kept += ScannedWord(
                    entry = entry,
                    occurrences = occurrences,
                    sentence = token.sentence,
                    score = studyScore(entry.frequency, occurrences, maxOccurrences, token.earliness)
                )
            }
        }

        // Best first by the combined score, ties broken deterministically so
        // two runs over the same files produce the same deck order.
        kept.sortWith(
            compareByDescending<ScannedWord> { it.score }
                .thenByDescending { it.occurrences }
                .thenBy { it.entry.displayText() }
        )

        val selected = if (filters.maxWords > 0 && kept.size > filters.maxWords) {
            skipped[TextScanSkipReason.OVER_LIMIT] = kept.size - filters.maxWords
            kept.take(filters.maxWords)
        } else {
            kept
        }

        val selectedForms = selected.mapTo(HashSet()) { it.entry.primaryExpression }

        return TextScanPlan(
            sources = sources,
            distinctWordCount = mergedWords.size,
            totalTokenCount = totalTokenCount,
            selected = selected,
            skipped = skipped,
            knownTokenCount = knownTokens,
            ankiScanUnavailable = ankiScanUnavailable,
            // Whether a structure ended up in the deck is decided by the
            // whole rule chain, not by the grammar rules alone: 今日は passes
            // them and is then dropped for being ranked 296 050. Saying so
            // here keeps the counter from reading worse than it is.
            grammarUses = grammar
                .map { it.copy(becameCard = it.form in selectedForms) }
                .sortedWith(compareByDescending<GrammarUse> { it.occurrences }.thenBy { it.form })
        )
    }

    // ------------------------------------------------------------ study order

    /**
     * Weights of the three signals. AnkiDroid introduces new cards in the order
     * they were added, so the order this produces IS the study order (as long
     * as the deck keeps the default "new card sort: order added").
     *
     * Global frequency dominates because a word that is common across all
     * Japanese media pays off outside this one book too. How often *this* text
     * uses it comes second — it is what makes the deck feel relevant to what
     * the user is watching. Earliness is the tie-breaker that matters when a
     * whole series is loaded at once: of two otherwise equal words, learn the
     * one from volume 1 before the one from volume 9.
     */
    private const val WEIGHT_GLOBAL = 0.5f
    private const val WEIGHT_IN_TEXT = 0.35f
    private const val WEIGHT_EARLY = 0.15f

    /**
     * Score for words with no frequency data at all. Middling on purpose: with
     * no frequency dictionary installed every word scores the same and the
     * order falls back to how the text itself uses the word, which is the only
     * signal left.
     */
    private const val UNRANKED_GLOBAL_SCORE = 0.35f

    /** Rank beyond which a word counts as "not common in the language". */
    private const val RANK_FLOOR = 100_000f

    internal fun studyScore(
        frequencyRank: Int,
        occurrences: Int,
        maxOccurrences: Int,
        earliness: Float
    ): Float {
        val global = if (frequencyRank <= 0) {
            UNRANKED_GLOBAL_SCORE
        } else {
            // Log scale: the gap between rank 100 and 1 000 matters far more
            // than the one between 40 000 and 41 000.
            (1f - ln(frequencyRank.toFloat()) / ln(RANK_FLOOR)).coerceIn(0f, 1f)
        }
        val inText = if (maxOccurrences <= 1) {
            if (occurrences > 0) 1f else 0f
        } else {
            (ln(1f + occurrences) / ln(1f + maxOccurrences)).coerceIn(0f, 1f)
        }
        return WEIGHT_GLOBAL * global +
            WEIGHT_IN_TEXT * inText +
            WEIGHT_EARLY * earliness.coerceIn(0f, 1f)
    }
}
