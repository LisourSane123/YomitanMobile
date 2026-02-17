package com.yomitanmobile.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yomitanmobile.data.local.dao.SearchHistoryDao
import com.yomitanmobile.data.local.entity.SearchHistory
import com.yomitanmobile.domain.model.MergedWordEntry
import com.yomitanmobile.domain.usecase.SearchDictionaryUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SearchViewModel @Inject constructor(
    private val searchDictionaryUseCase: SearchDictionaryUseCase,
    private val searchHistoryDao: SearchHistoryDao
) : ViewModel() {

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    val searchHistory: StateFlow<List<SearchHistory>> = searchHistoryDao
        .getRecentSearches(20)
        .catch { emit(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    @OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
    val searchResults: StateFlow<List<MergedWordEntry>> = _query
        .debounce(300L)
        .distinctUntilChanged()
        .flatMapLatest { q ->
            if (q.isBlank()) {
                _isSearching.value = false
                flowOf(emptyList())
            } else {
                _isSearching.value = true
                searchDictionaryUseCase.invoke(q)
                    .catch { _ ->
                        _isSearching.value = false
                        emit(emptyList())
                    }
                    .map { results ->
                        _isSearching.value = false
                        // Save to search history if results found and query is meaningful
                        if (results.isNotEmpty() && q.length >= 2) {
                            saveSearchQuery(q)
                        }
                        // Merge/consolidate results by reading
                        MergedWordEntry.mergeEntries(results)
                    }
            }
        }
        .catch { _ ->
            emit(emptyList())
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _isSearching = MutableStateFlow(false)
    val isSearching: StateFlow<Boolean> = _isSearching.asStateFlow()

    fun onQueryChange(newQuery: String) {
        _query.value = newQuery
    }

    fun clearQuery() {
        _query.value = ""
        _isSearching.value = false
    }

    private fun saveSearchQuery(query: String) {
        viewModelScope.launch {
            try {
                searchHistoryDao.insert(SearchHistory(query = query))
            } catch (_: Exception) { }
        }
    }

    fun deleteHistoryItem(id: Long) {
        viewModelScope.launch {
            searchHistoryDao.deleteById(id)
        }
    }

    fun clearHistory() {
        viewModelScope.launch {
            searchHistoryDao.deleteAll()
        }
    }
}
