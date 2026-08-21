package com.yomitanmobile.domain.usecase

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
        // high-frequency connectives and fillers
        "そして", "でも", "しかし", "だから", "また", "まだ", "もう", "とても",
        "ちょっと", "はい", "ええ", "うん", "いや", "あの", "その", "えっと"
    ) + JapaneseTokenizer.GRAMMAR_FORMS // the copula/auxiliary chains the tokeniser keeps whole

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

        val maxOccurrences = words.maxOfOrNull { it.occurrences } ?: 1

        val kept = mutableListOf<ScannedWord>()
        for (token in words) {
            val word = token.baseForm
            val occurrences = token.occurrences
            val entry = entries[word]
            when {
                filters.skipFunctionWords && word in FUNCTION_WORDS ->
                    reject(TextScanSkipReason.FUNCTION_WORD, occurrences)
                entry == null ->
                    reject(TextScanSkipReason.NOT_IN_DICTIONARY, occurrences)
                // Same reason, second source of truth: the dictionary's own
                // part-of-speech tags. Runs right after the lookup so a word
                // rejected as grammar is counted as grammar and not as, say,
                // "too few occurrences".
                filters.skipFunctionWords && WordFilterRules.isFunctionWord(entry) ->
                    reject(TextScanSkipReason.FUNCTION_WORD, occurrences)
                occurrences < filters.minOccurrences ->
                    reject(TextScanSkipReason.TOO_FEW_OCCURRENCES, occurrences)
                entry.definitions.none { it.isNotBlank() } ->
                    reject(TextScanSkipReason.NO_DEFINITION, occurrences)
                filters.skipProperNames && WordFilterRules.isProperName(entry) ->
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

        return TextScanPlan(
            sources = sources,
            distinctWordCount = words.size,
            totalTokenCount = totalTokenCount,
            selected = selected,
            skipped = skipped,
            knownTokenCount = knownTokens,
            ankiScanUnavailable = ankiScanUnavailable
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
