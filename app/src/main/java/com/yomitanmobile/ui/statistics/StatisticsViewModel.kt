package com.yomitanmobile.ui.statistics

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yomitanmobile.MainActivity
import com.yomitanmobile.data.local.dao.CategoryActivityCount
import com.yomitanmobile.data.local.dao.DictionaryDao
import com.yomitanmobile.data.local.dao.DictionaryInfoDao
import com.yomitanmobile.data.local.dao.ExportedWordDao
import com.yomitanmobile.data.local.dao.HourlyActivityCount
import com.yomitanmobile.data.local.dao.SearchHistoryDao
import com.yomitanmobile.data.local.entity.ExportedWord
import com.yomitanmobile.dataStore
import com.yomitanmobile.util.WordCategoryClassifier
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

data class WeeklyLearnedWord(
    val expression: String,
    val reading: String,
    val exportDate: Long,
    val exportCategory: String = WordCategoryClassifier.CATEGORY_OTHER
)

data class HourlyActivity(
    val hour: Int,
    val count: Int
)

data class CategoryActivity(
    val categoryCode: String,
    val categoryLabel: String,
    val count: Int
)

data class StatisticsState(
    val totalEntries: Int = 0,
    val dictionaryCount: Int = 0,
    val exportedCount: Int = 0,
    val searchHistoryCount: Int = 0,
    val streak: Int = 0,
    val dailyGoal: Int = 0,
    val dailyCounts: List<DailyCount> = emptyList(),
    val weeklyLearnedWords: List<WeeklyLearnedWord> = emptyList(),
    val hourlyActivity: List<HourlyActivity> = emptyList(),
    val mostActiveHour: Int? = null,
    val mostActiveHourCount: Int = 0,
    val categoryActivity: List<CategoryActivity> = emptyList(),
    val mostActiveCategory: String? = null,
    val mostActiveCategoryCount: Int = 0,
    val bunproIntegrationEnabled: Boolean = false,
    val bunproVocabularyCount: Int = 0,
    val bunproKanjiLearned: List<String> = emptyList(),
    val bunproError: String? = null,
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

    private val logTag = "StatisticsViewModel"

    companion object {
        private const val WEEK_IN_MILLIS = 7L * 24L * 60L * 60L * 1000L

        internal fun toWeeklyLearnedWords(exports: List<ExportedWord>): List<WeeklyLearnedWord> {
            return exports
                .sortedByDescending { it.exportDate }
                .distinctBy { "${it.expression.trim()}|${it.reading.trim()}" }
                .map {
                    val categoryCode = it.exportCategory.trim().ifBlank {
                        WordCategoryClassifier.CATEGORY_OTHER
                    }
                    WeeklyLearnedWord(
                        expression = it.expression.trim(),
                        reading = it.reading.trim(),
                        exportDate = it.exportDate,
                        exportCategory = categoryCode
                    )
                }
        }

        internal fun buildWeeklyLearnedWordsCopyText(words: List<WeeklyLearnedWord>): String {
            if (words.isEmpty()) return ""

            val header = "Słowa z ostatnich 7 dni (${words.size})"
            val body = words.mapIndexed { index, word ->
                val readingPart = when {
                    word.reading.isBlank() -> ""
                    word.reading == word.expression -> ""
                    else -> " (${word.reading})"
                }
                val category = categoryLabel(word.exportCategory)
                "${index + 1}. ${word.expression}$readingPart - $category"
            }

            return (listOf(header) + body).joinToString("\n")
        }

        internal fun buildWeeklyLearnedWordsCopyText(
            words: List<WeeklyLearnedWord>,
            isEnglish: Boolean
        ): String {
            if (words.isEmpty()) return ""

            val header = if (isEnglish) {
                "Words from last 7 days (${words.size})"
            } else {
                "Słowa z ostatnich 7 dni (${words.size})"
            }
            val body = words.mapIndexed { index, word ->
                val readingPart = when {
                    word.reading.isBlank() -> ""
                    word.reading == word.expression -> ""
                    else -> " (${word.reading})"
                }
                val category = categoryLabel(word.exportCategory, isEnglish)
                "${index + 1}. ${word.expression}$readingPart - $category"
            }

            return (listOf(header) + body).joinToString("\n")
        }

        internal fun categoryLabel(categoryCode: String, isEnglish: Boolean = false): String {
            return WordCategoryClassifier.displayName(categoryCode, isEnglish)
        }

        internal fun toHourlyActivity(items: List<HourlyActivityCount>): List<HourlyActivity> {
            return items
                .filter { it.hour in 0..23 }
                .map { HourlyActivity(hour = it.hour, count = it.count) }
                .sortedBy { it.hour }
        }

        internal fun findMostActiveHour(activity: List<HourlyActivity>): HourlyActivity? {
            val positive = activity.filter { it.count > 0 }
            if (positive.isEmpty()) return null
            val maxCount = positive.maxOf { it.count }
            return positive
                .filter { it.count == maxCount }
                .minByOrNull { it.hour }
        }

        internal fun hourRangeLabel(hour: Int): String {
            val normalized = hour.coerceIn(0, 23)
            return String.format("%02d:00-%02d:59", normalized, normalized)
        }

        internal fun toCategoryActivity(
            items: List<CategoryActivityCount>,
            isEnglish: Boolean = false
        ): List<CategoryActivity> {
            return items
                .map {
                    val code = it.category.trim().ifBlank { WordCategoryClassifier.CATEGORY_OTHER }
                    CategoryActivity(
                        categoryCode = code,
                        categoryLabel = categoryLabel(code, isEnglish),
                        count = it.count
                    )
                }
                .sortedWith(compareByDescending<CategoryActivity> { it.count }.thenBy { it.categoryLabel })
        }

        internal fun findMostActiveCategory(activity: List<CategoryActivity>): CategoryActivity? {
            val positive = activity.filter { it.count > 0 }
            if (positive.isEmpty()) return null
            val maxCount = positive.maxOf { it.count }
            return positive
                .filter { it.count == maxCount }
                .minByOrNull { it.categoryLabel }
        }
    }

    private val _state = MutableStateFlow(StatisticsState())
    val state: StateFlow<StatisticsState> = _state.asStateFlow()

    init {
        loadStatistics()
    }

    private fun loadStatistics() {
        viewModelScope.launch {
            try {
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

                val oneWeekAgo = System.currentTimeMillis() - WEEK_IN_MILLIS
                val weeklyLearnedWords = runCatching {
                    toWeeklyLearnedWords(exportedWordDao.getExportsSince(oneWeekAgo))
                }.getOrElse {
                    Log.e(logTag, "Failed to load weekly learned words", it)
                    emptyList()
                }
                val hourlyActivity = runCatching {
                    toHourlyActivity(exportedWordDao.getHourlyActivitySince(oneWeekAgo))
                }.getOrElse {
                    Log.e(logTag, "Failed to load hourly activity", it)
                    emptyList()
                }
                val mostActiveHour = findMostActiveHour(hourlyActivity)
                val categoryActivity = runCatching {
                    toCategoryActivity(exportedWordDao.getCategoryActivitySince(oneWeekAgo))
                }.getOrElse {
                    Log.e(logTag, "Failed to load category activity", it)
                    emptyList()
                }
                val mostActiveCategory = findMostActiveCategory(categoryActivity)

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
                    weeklyLearnedWords = weeklyLearnedWords,
                    hourlyActivity = hourlyActivity,
                    mostActiveHour = mostActiveHour?.hour,
                    mostActiveHourCount = mostActiveHour?.count ?: 0,
                    categoryActivity = categoryActivity,
                    mostActiveCategory = mostActiveCategory?.categoryLabel,
                    mostActiveCategoryCount = mostActiveCategory?.count ?: 0,
                    isLoading = false
                )
            } catch (exception: Exception) {
                Log.e(logTag, "Failed to load statistics", exception)
                _state.value = _state.value.copy(
                    isLoading = false,
                    bunproError = "Blad ladowania statystyk"
                )
            }
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
