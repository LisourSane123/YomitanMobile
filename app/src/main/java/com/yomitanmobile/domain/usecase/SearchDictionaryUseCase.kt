package com.yomitanmobile.domain.usecase

import com.yomitanmobile.domain.model.MergedWordEntry
import com.yomitanmobile.domain.model.WordEntry
import com.yomitanmobile.domain.repository.DictionaryRepository
import com.yomitanmobile.util.JapaneseTokenizer
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

        return flow {
            val results = coroutineScope {
                val perQuery = orderedQueries.mapIndexed { idx, q ->
                    async {
                        if (idx == 0) repository.searchCombined(q).first()
                        else repository.searchExact(q).first()
                    }
                }
                val substring = if (withSubstring) {
                    async { repository.searchContains(normalized).first() }
                } else {
                    null
                }
                perQuery.map { it.await() } + listOfNotNull(substring?.await())
            }

            val mergedById = LinkedHashMap<Long, WordEntry>()
            results.forEachIndexed { idx, entries ->
                val bounded = if (idx == 0) entries else entries.take(20)
                bounded.forEach { entry -> mergedById.putIfAbsent(entry.id, entry) }
            }
            emit(mergedById.values.toList())
        }.catch {
            emit(emptyList())
        }
    }

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
