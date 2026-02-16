package com.yomitanmobile.ui.statistics

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yomitanmobile.data.local.dao.DictionaryDao
import com.yomitanmobile.data.local.dao.DictionaryInfoDao
import com.yomitanmobile.data.local.dao.ExportedWordDao
import com.yomitanmobile.data.local.dao.SearchHistoryDao
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

data class StatisticsState(
    val totalEntries: Int = 0,
    val dictionaryCount: Int = 0,
    val exportedCount: Int = 0,
    val searchHistoryCount: Int = 0,
    val isLoading: Boolean = true
)

@HiltViewModel
class StatisticsViewModel @Inject constructor(
    private val dictionaryDao: DictionaryDao,
    private val dictionaryInfoDao: DictionaryInfoDao,
    private val exportedWordDao: ExportedWordDao,
    private val searchHistoryDao: SearchHistoryDao
) : ViewModel() {

    private val _state = MutableStateFlow(StatisticsState())
    val state: StateFlow<StatisticsState> = _state.asStateFlow()

    init {
        loadStatistics()
    }

    private fun loadStatistics() {
        viewModelScope.launch {
            val totalEntries = dictionaryDao.getEntryCount()
            val dictionaries = dictionaryInfoDao.getAllDictionaries().first()
            val exportedCount = exportedWordDao.getExportedCount()
            val searchCount = searchHistoryDao.getCount()

            _state.value = StatisticsState(
                totalEntries = totalEntries,
                dictionaryCount = dictionaries.size,
                exportedCount = exportedCount,
                searchHistoryCount = searchCount,
                isLoading = false
            )
        }
    }

    fun refresh() {
        _state.value = _state.value.copy(isLoading = true)
        loadStatistics()
    }
}
