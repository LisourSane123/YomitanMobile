package com.yomitanmobile.domain.usecase

import com.yomitanmobile.domain.model.MergedWordEntry
import com.yomitanmobile.domain.model.WordEntry
import com.yomitanmobile.domain.repository.DictionaryRepository
import com.yomitanmobile.util.JapaneseTokenizer
import com.yomitanmobile.util.KanaScript
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.first
import javax.inject.Inject

class SearchDictionaryUseCase @Inject constructor(
    private val repository: DictionaryRepository
) {

    fun invoke(query: String): Flow<List<WordEntry>> {
        if (query.isBlank()) return flowOf(emptyList())
        return invokeWithAlternatives(query, emptyList())
    }

    /**
     * Search the user's literal query plus a list of deconjugation
     * alternatives, then merge by entry id with the original query taking
     * priority. The alternatives are queried in parallel via
     * [coroutineScope] + [async] — sequential `first()` calls were the
     * dominant cold-search latency for verbs with many inflection
     * candidates (audit issue (c)).
     *
     * The original query keeps every result; alternatives keep their first
     * 20 to avoid drowning out the canonical match.
     *
     * Search strategy differs by position: the literal query (index 0) goes
     * through the prefix-LIKE [DictionaryRepository.searchCombined] so the
     * user's partial typing matches. Deconjugation alternatives are base
     * forms and go through [DictionaryRepository.searchExact] — a prefix
     * match there would surface unrelated longer words that merely share
     * the base-form prefix (e.g. 見る → 見るに値する).
     *
     * The query is also searched in the other kana script (こーひー finds
     * コーヒー), which ranks with the literal query rather than behind the
     * deconjugations: it is the same word the user typed, only spelled the way
     * the dictionary files it.
     *
     * A substring pass ([DictionaryRepository.searchContains]) runs in the
     * same parallel batch and is appended LAST, so 欲 lists 欲しい/欲望 before
     * 食欲/意欲 — the word itself and what it starts still outrank the
     * compounds it merely appears in, but the compounds are no longer
     * invisible. See [shouldSearchSubstring] for when that pass is skipped.
     */
    fun invokeWithAlternatives(query: String, alternatives: List<String>): Flow<List<WordEntry>> {
        val normalized = query.trim()
        if (normalized.isBlank()) return flowOf(emptyList())

        val orderedQueries = linkedSetOf(normalized).also { set ->
            alternatives.asSequence()
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .forEach { set.add(it) }
        }.toList()
        val withSubstring = shouldSearchSubstring(normalized)

        // The same query in the other kana script. It is the user's own text,
        // not a guess about it, so it searches by prefix like the literal
        // query does and ranks with it — see [KanaScript.spellingVariants].
        val spellings = KanaScript.spellingVariants(normalized)

        return flow {
            val results = coroutineScope {
                val literalQuery = async { repository.searchCombined(orderedQueries.first()).first() }
                val perSpelling = spellings.map { async { repository.searchCombined(it).first() } }
                // Every deconjugation candidate in ONE query. They used to be
                // one query each — up to 24 cursors and 24 Room invalidation
                // observers per keystroke — and the merge below pools and
                // re-ranks them by frequency anyway, so there was never
                // anything to gain from keeping them apart.
                val baseForms = orderedQueries.drop(1)
                val alternatives = if (baseForms.isEmpty()) {
                    null
                } else {
                    async { repository.searchExactAll(baseForms) }
                }
                val substring = if (withSubstring) {
                    async { repository.searchContains(normalized).first() }
                } else {
                    null
                }
                listOf(literalQuery.await()) +
                    perSpelling.map { it.await() } +
                    listOfNotNull(alternatives?.await()) +
                    listOfNotNull(substring?.await())
            }
            val literalCount = 1 + spellings.size

            // What the user literally typed comes first, in the order the DAO
            // ranked it. Everything the deconjugator suggested comes next —
            // ordered by how common the word is, NOT by the order the rules
            // happened to produce. The candidate list is sorted by length, so
            // without this a two-character archaism (食ぶ, offered because
            // 食べる also looks like the potential form of a godan verb)
            // outranked the word the user was actually inflecting.
            val literal = LinkedHashMap<Long, WordEntry>()
            val alternatives = LinkedHashMap<Long, WordEntry>()
            results.forEachIndexed { idx, entries ->
                val isLiteral = idx < literalCount
                // The alternatives arrive as one pooled list now, so the cap
                // that used to be per candidate is one cap on the pool.
                val bounded = if (isLiteral) entries else entries.take(ALTERNATIVE_LIMIT)
                val target = if (isLiteral) literal else alternatives
                bounded.forEach { entry ->
                    if (entry.id !in literal) target.putIfAbsent(entry.id, entry)
                }
            }
            val rankedAlternatives = alternatives.values.sortedWith(
                compareBy(
                    { if (it.frequency > 0) 0 else 1 },
                    { if (it.frequency > 0) it.frequency else Int.MAX_VALUE },
                    { it.expression.length }
                )
            )
            emit(literal.values.toList() + rankedAlternatives)
        }.catch {
            emit(emptyList())
        }
    }

    /**
     * How many pooled deconjugation matches survive the merge. Twenty per
     * candidate was the old rule; the pool is re-ranked by frequency before it
     * is cut, so a generous single cap keeps the same words and costs one
     * query instead of twenty-four.
     */
    private val ALTERNATIVE_LIMIT = 120

    /**
     * Whether a query is worth a substring scan.
     *
     * Substring matching cannot use an index, so it is spent only where it
     * pays: a single kanji is exactly the case that needs it (欲 → 食欲), while
     * a single kana matches a large share of the dictionary and would cost a
     * full scan per keystroke to return noise. Two or more Japanese
     * characters are specific enough to be worth it. Latin input never
     * reaches here as a word query — English goes through the FTS definition
     * search, romaji is converted to kana first.
     */
    internal fun shouldSearchSubstring(query: String): Boolean {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return false
        if (!trimmed.any { JapaneseTokenizer.isJapanese(it) }) return false
        return trimmed.length >= 2 || MergedWordEntry.containsKanji(trimmed)
    }

    fun invokeEnglish(query: String): Flow<List<WordEntry>> {
        if (query.isBlank()) return flowOf(emptyList())
        return repository.searchByDefinition(query)
    }
}
