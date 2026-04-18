package com.yomitanmobile.domain.usecase

import com.yomitanmobile.domain.model.WordEntry
import com.yomitanmobile.domain.repository.DictionaryRepository
import com.yomitanmobile.util.JapaneseDeconjugator
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.first
import javax.inject.Inject

class SearchDictionaryUseCase @Inject constructor(
    private val repository: DictionaryRepository
) {
    fun invokeJapaneseSmart(query: String): Flow<List<WordEntry>> {
        val normalized = query.trim()
        if (normalized.isBlank()) return flowOf(emptyList())

        val candidates = JapaneseDeconjugator.candidateForms(normalized)
        return invokeWithAlternatives(normalized, candidates)
    }

    fun invoke(query: String): Flow<List<WordEntry>> {
        if (query.isBlank()) return flowOf(emptyList())
        return repository.searchCombined(query)
    }

    fun invokeWithAlternatives(query: String, alternatives: List<String>): Flow<List<WordEntry>> {
        val normalized = query.trim()
        if (normalized.isBlank()) return flowOf(emptyList())

        val allQueries = linkedSetOf(normalized)
        alternatives.asSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .forEach { allQueries.add(it) }

        return flow {
            val mergedById = LinkedHashMap<Long, WordEntry>()

            allQueries.forEachIndexed { idx, q ->
                val results = repository.searchCombined(q).first()
                val boundedResults = if (idx == 0) results else results.take(20)
                boundedResults.forEach { entry ->
                    mergedById.putIfAbsent(entry.id, entry)
                }
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

    @OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
    fun invokeReactive(queryFlow: Flow<String>): Flow<List<WordEntry>> {
        return queryFlow
            .debounce(300L)
            .distinctUntilChanged()
            .flatMapLatest { query ->
                if (query.isBlank()) flowOf(emptyList())
                else repository.searchCombined(query)
            }
    }
}
