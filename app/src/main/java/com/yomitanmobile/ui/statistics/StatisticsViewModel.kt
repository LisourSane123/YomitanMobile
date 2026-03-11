package com.yomitanmobile.ui.statistics

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yomitanmobile.MainActivity
import com.yomitanmobile.data.local.dao.DictionaryDao
import com.yomitanmobile.data.local.dao.DictionaryInfoDao
import com.yomitanmobile.data.local.dao.ExportedWordDao
import com.yomitanmobile.data.local.dao.SearchHistoryDao
import com.yomitanmobile.dataStore
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.Calendar
import javax.inject.Inject

data class DailyCount(
    val dayLabel: String,      // e.g. "03.01"
    val count: Int,
    val dayTimestamp: Long      // start of that day
)

data class StatisticsState(
    val totalEntries: Int = 0,
    val dictionaryCount: Int = 0,
    val exportedCount: Int = 0,
    val searchHistoryCount: Int = 0,
    val streak: Int = 0,
    val dailyGoal: Int = 0,
    val dailyCounts: List<DailyCount> = emptyList(),
    val isLoading: Boolean = true
)

@HiltViewModel
class StatisticsViewModel @Inject constructor(
    private val dictionaryDao: DictionaryDao,
    private val dictionaryInfoDao: DictionaryInfoDao,
    private val exportedWordDao: ExportedWordDao,
    private val searchHistoryDao: SearchHistoryDao,
    @ApplicationContext private val context: Context
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

            // Load daily goal
            val prefs = context.dataStore.data.first()
            val dailyGoal = prefs[MainActivity.DAILY_GOAL_COUNT] ?: 0

            // Compute daily counts for chart (last 30 days)
            val allDates = exportedWordDao.getAllExportDates()
            val dailyCounts = computeDailyCounts(allDates, 30)

            // Compute streak
            val streak = if (dailyGoal > 0) computeStreak(dailyCounts, dailyGoal) else 0

            _state.value = StatisticsState(
                totalEntries = totalEntries,
                dictionaryCount = dictionaries.size,
                exportedCount = exportedCount,
                searchHistoryCount = searchCount,
                streak = streak,
                dailyGoal = dailyGoal,
                dailyCounts = dailyCounts,
                isLoading = false
            )
        }
    }

    private fun computeDailyCounts(exportDates: List<Long>, days: Int): List<DailyCount> {
        val cal = Calendar.getInstance()
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)

        // Build list of last N days
        val result = mutableListOf<DailyCount>()
        for (d in (days - 1) downTo 0) {
            val dayCal = Calendar.getInstance()
            dayCal.timeInMillis = cal.timeInMillis
            dayCal.add(Calendar.DAY_OF_YEAR, -d)
            val dayStart = dayCal.timeInMillis
            val dayEnd = dayStart + 24 * 60 * 60 * 1000L

            val count = exportDates.count { it in dayStart until dayEnd }
            val label = "${String.format("%02d", dayCal.get(Calendar.DAY_OF_MONTH))}.${String.format("%02d", dayCal.get(Calendar.MONTH) + 1)}"
            result.add(DailyCount(dayLabel = label, count = count, dayTimestamp = dayStart))
        }
        return result
    }

    private fun computeStreak(dailyCounts: List<DailyCount>, dailyGoal: Int): Int {
        if (dailyCounts.isEmpty() || dailyGoal <= 0) return 0

        // Start from yesterday (or today if goal met), go backwards
        var streak = 0
        val reversed = dailyCounts.reversed()
        // Check if today's goal is met
        val today = reversed.firstOrNull() ?: return 0
        val startIdx = if (today.count >= dailyGoal) 0 else 1

        for (i in startIdx until reversed.size) {
            if (reversed[i].count >= dailyGoal) {
                streak++
            } else {
                break
            }
        }
        return streak
    }

    fun refresh() {
        _state.value = _state.value.copy(isLoading = true)
        loadStatistics()
    }
}
