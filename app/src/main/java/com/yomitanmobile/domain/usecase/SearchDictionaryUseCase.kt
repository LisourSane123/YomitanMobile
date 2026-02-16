package com.yomitanmobile.domain.usecase

import com.yomitanmobile.domain.model.WordEntry
import com.yomitanmobile.domain.repository.DictionaryRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import javax.inject.Inject

class SearchDictionaryUseCase @Inject constructor(
    private val repository: DictionaryRepository
) {
    fun invoke(query: String): Flow<List<WordEntry>> {
        if (query.isBlank()) return flowOf(emptyList())
        return repository.searchCombined(query)
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
