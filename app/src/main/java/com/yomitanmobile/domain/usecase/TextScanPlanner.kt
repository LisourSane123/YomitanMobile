package com.yomitanmobile.domain.usecase

import com.yomitanmobile.domain.model.MergedWordEntry
import com.yomitanmobile.domain.model.ScannedWord
import com.yomitanmobile.domain.model.TextScanFilters
import com.yomitanmobile.domain.model.TextScanPlan
import com.yomitanmobile.domain.model.TextScanSkipReason
import com.yomitanmobile.domain.model.TextScanSource

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
    )

    /**
     * @param words distinct words found in the text, with occurrence counts
     * @param entries dictionary entries resolved for those words, keyed by the
     *   base form the tokeniser produced. A missing key means the word is not
     *   in any installed dictionary.
     */
    fun plan(
        source: TextScanSource,
        words: Map<String, Int>,
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
                reason == TextScanSkipReason.FUNCTION_WORD
            ) {
                knownTokens += occurrences
            }
        }

        val kept = mutableListOf<ScannedWord>()
        for ((word, occurrences) in words) {
            val entry = entries[word]
            when {
                filters.skipFunctionWords && word in FUNCTION_WORDS ->
                    reject(TextScanSkipReason.FUNCTION_WORD, occurrences)
                entry == null ->
                    reject(TextScanSkipReason.NOT_IN_DICTIONARY, occurrences)
                occurrences < filters.minOccurrences ->
                    reject(TextScanSkipReason.TOO_FEW_OCCURRENCES, occurrences)
                entry.definitions.none { it.isNotBlank() } ->
                    reject(TextScanSkipReason.NO_DEFINITION, occurrences)
                filters.skipProperNames && WordFilterRules.isProperName(entry) ->
                    reject(TextScanSkipReason.PROPER_NAME, occurrences)
                entry.frequency <= 0 && !filters.includeUnranked ->
                    reject(TextScanSkipReason.UNRANKED, occurrences)
                filters.tier.maxRank > 0 && entry.frequency > filters.tier.maxRank ->
                    reject(TextScanSkipReason.TOO_RARE, occurrences)
                filters.skipArchaic && WordFilterRules.isArchaic(entry) ->
                    reject(TextScanSkipReason.ARCHAIC, occurrences)
                filters.skipAlreadyInAnki && isInAnki(entry) ->
                    reject(TextScanSkipReason.ALREADY_IN_ANKI, occurrences)
                filters.skipAlreadyMined && isMined(entry) ->
                    reject(TextScanSkipReason.ALREADY_MINED, occurrences)
                else -> kept += ScannedWord(entry, occurrences)
            }
        }

        // Most useful first: what the text uses most, then what the frequency
        // lists rank highest. A capped deck keeps exactly the words that pay
        // off soonest in *this* text.
        kept.sortWith(
            compareByDescending<ScannedWord> { it.occurrences }
                .thenBy { if (it.entry.frequency > 0) 0 else 1 }
                .thenBy { if (it.entry.frequency > 0) it.entry.frequency else Int.MAX_VALUE }
                .thenBy { it.entry.displayText() }
        )

        val selected = if (filters.maxWords > 0 && kept.size > filters.maxWords) {
            skipped[TextScanSkipReason.OVER_LIMIT] = kept.size - filters.maxWords
            kept.take(filters.maxWords)
        } else {
            kept
        }

        return TextScanPlan(
            source = source,
            distinctWordCount = words.size,
            totalTokenCount = totalTokenCount,
            selected = selected,
            skipped = skipped,
            knownTokenCount = knownTokens,
            ankiScanUnavailable = ankiScanUnavailable
        )
    }
}
