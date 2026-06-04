package com.yomitanmobile.domain.usecase

import com.yomitanmobile.domain.model.WordEntry
import com.yomitanmobile.domain.repository.DictionaryRepository
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
        return repository.searchCombined(query)
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

        return flow {
            val results = coroutineScope {
                orderedQueries.mapIndexed { idx, q ->
                    async {
                        if (idx == 0) repository.searchCombined(q).first()
                        else repository.searchExact(q).first()
                    }
                }.map { it.await() }
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

    fun invokeEnglish(query: String): Flow<List<WordEntry>> {
        if (query.isBlank()) return flowOf(emptyList())
        return repository.searchByDefinition(query)
    }
}
