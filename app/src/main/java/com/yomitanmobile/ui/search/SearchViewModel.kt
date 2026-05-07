package com.yomitanmobile.ui.search

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yomitanmobile.MainActivity
import com.yomitanmobile.data.local.dao.DictionaryDao
import com.yomitanmobile.data.local.dao.ExportedWordDao
import com.yomitanmobile.data.local.dao.SentenceDao
import com.yomitanmobile.data.local.dao.SearchHistoryDao
import com.yomitanmobile.data.local.entity.SearchHistory
import com.yomitanmobile.dataStore
import com.yomitanmobile.domain.model.MergedWordEntry
import com.yomitanmobile.domain.model.WordEntry
import com.yomitanmobile.domain.usecase.SearchDictionaryUseCase
import com.yomitanmobile.util.DeconjugationCandidate
import com.yomitanmobile.util.JapaneseDeconjugator
import com.yomitanmobile.util.RomajiConverter
import com.yomitanmobile.util.WordCategoryClassifier
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

/**
 * Daily goal progress state.
 */
data class DailyGoalState(
    val goalCount: Int = 0,        // 0 = disabled
    val todayCount: Int = 0
) {
    val isEnabled: Boolean get() = goalCount > 0
    val isCompleted: Boolean get() = isEnabled && todayCount >= goalCount
    val progress: Float get() = if (goalCount > 0) (todayCount.toFloat() / goalCount).coerceIn(0f, 1f) else 0f
}

data class SearchCategoryStat(
    val code: String,
    val count: Int
)

enum class SearchMode(val label: String) {
    JAPANESE("JP"),
    ENGLISH("EN"),
    ROMAJI("RM")
}

@HiltViewModel
class SearchViewModel @Inject constructor(
    private val searchDictionaryUseCase: SearchDictionaryUseCase,
    private val dictionaryDao: DictionaryDao,
    private val sentenceDao: SentenceDao,
    private val searchHistoryDao: SearchHistoryDao,
    private val exportedWordDao: ExportedWordDao,
    @ApplicationContext private val appContext: Context
) : ViewModel() {

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _searchMode = MutableStateFlow(SearchMode.JAPANESE)
    val searchMode: StateFlow<SearchMode> = _searchMode.asStateFlow()

    private val _deconjugationCandidates = MutableStateFlow<List<DeconjugationCandidate>>(emptyList())
    val deconjugationCandidates: StateFlow<List<DeconjugationCandidate>> = _deconjugationCandidates.asStateFlow()

    private var lastInjectedExternalQuery: String? = null

    val searchHistory: StateFlow<List<SearchHistory>> = searchHistoryDao
        .getRecentSearches(20)
        .catch { emit(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _dailyGoal = MutableStateFlow(DailyGoalState())
    val dailyGoal: StateFlow<DailyGoalState> = _dailyGoal.asStateFlow()

    private val _importedWordsCount = MutableStateFlow(0)
    val importedWordsCount: StateFlow<Int> = _importedWordsCount.asStateFlow()

    val categoryStats: StateFlow<List<SearchCategoryStat>> = exportedWordDao
        .getCategoryActivityAll()
        .map { rows ->
            rows
                .map { row ->
                    SearchCategoryStat(
                        code = row.category.trim().ifBlank { WordCategoryClassifier.CATEGORY_OTHER },
                        count = row.count
                    )
                }
                .sortedByDescending { it.count }
        }
        .catch { emit(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        refreshDailyGoal()
        refreshQuickStats()
    }

    fun refreshQuickStats() {
        viewModelScope.launch {
            try {
                _importedWordsCount.value = dictionaryDao.getEntryCount()
            } catch (_: Exception) { }
        }
    }

    fun refreshDailyGoal() {
        viewModelScope.launch {
            try {
                val prefs = appContext.dataStore.data.first()
                val goalCount = prefs[MainActivity.DAILY_GOAL_COUNT] ?: 0
                val zoneId = ZoneId.systemDefault()
                val startOfDay = LocalDate.now(zoneId)
                    .atStartOfDay(zoneId)
                    .toInstant()
                    .toEpochMilli()
                val todayCount = exportedWordDao.getExportedCountSince(startOfDay)
                _dailyGoal.value = DailyGoalState(goalCount = goalCount, todayCount = todayCount)
            } catch (_: Exception) { }
        }
    }

    @OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
    val searchResults: StateFlow<List<MergedWordEntry>> = kotlinx.coroutines.flow.combine(_query, _searchMode) { q, mode -> q to mode }
        .debounce(100L)
        .distinctUntilChanged()
        .flatMapLatest { (q, mode) ->
            if (q.isBlank()) {
                _isSearching.value = false
                _deconjugationCandidates.value = emptyList()
                flowOf(emptyList())
            } else {
                _isSearching.value = true
                val searchFlow = when (mode) {
                    SearchMode.JAPANESE -> {
                        val candidates = JapaneseDeconjugator.analyze(q)
                        _deconjugationCandidates.value = candidates
                        searchDictionaryUseCase.invokeWithAlternatives(
                            query = q,
                            alternatives = candidates.map { it.baseForm }
                        )
                    }
                    SearchMode.ENGLISH -> {
                        _deconjugationCandidates.value = emptyList()
                        val romajiCandidate = romajiQueryToHiragana(q)
                        searchDictionaryUseCase.invokeEnglish(q)
                            .map { englishResults ->
                                if (romajiCandidate != null) {
                                    val romajiResults = if (shouldUseRomajiFallback(q, romajiCandidate)) {
                                        searchDictionaryUseCase.invoke(romajiCandidate).first()
                                    } else {
                                        emptyList()
                                    }

                                    if (romajiResults.isNotEmpty()) {
                                        val mergedById = LinkedHashMap<Long, WordEntry>()
                                        romajiResults.forEach { mergedById.putIfAbsent(it.id, it) }
                                        englishResults.forEach { mergedById.putIfAbsent(it.id, it) }
                                        mergedById.values.toList()
                                    } else {
                                        englishResults
                                    }
                                } else if (englishResults.isNotEmpty()) {
                                    englishResults
                                } else {
                                    val romajiConverted = RomajiConverter.toHiragana(q)
                                    if (shouldUseRomajiFallback(q, romajiConverted)) {
                                        searchDictionaryUseCase.invoke(romajiConverted).first()
                                    } else {
                                        emptyList()
                                    }
                                }
                            }
                    }
                    SearchMode.ROMAJI -> {
                        val hiragana = romajiQueryToHiragana(q) ?: RomajiConverter.toHiragana(q)
                        _deconjugationCandidates.value = emptyList()
                        if (hiragana.isNotBlank()) {
                            searchDictionaryUseCase.invoke(hiragana)
                                .flatMapLatest { kanaResults ->
                                    if (kanaResults.isNotEmpty()) {
                                        flowOf(kanaResults)
                                    } else {
                                        searchDictionaryUseCase.invokeEnglish(q)
                                    }
                                }
                        } else {
                            flowOf(emptyList())
                        }
                    }
                }
                searchFlow
                    .catch { _ ->
                        _isSearching.value = false
                        emit(emptyList())
                    }
                    .map { results ->
                        _isSearching.value = false
                        val merged = MergedWordEntry.mergeEntries(results)
                        val enriched = enrichWithExampleSentences(merged)
                        val romajiCandidate = romajiQueryToHiragana(q)
                        if (romajiCandidate != null) {
                            sortRomajiExactFirst(enriched, romajiCandidate)
                        } else if (mode == SearchMode.ENGLISH && q.isNotBlank()) {
                            // Sort by how early the query appears in the definitions list
                            val queryLower = q.lowercase()
                            enriched.sortedWith(
                                compareBy<MergedWordEntry> { entry ->
                                    val idx = entry.definitions.indexOfFirst {
                                        it.lowercase().contains(queryLower)
                                    }
                                    if (idx < 0) Int.MAX_VALUE else idx
                                }.thenBy { if (it.frequency > 0) it.frequency else Int.MAX_VALUE }
                            )
                        } else {
                            enriched
                        }
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
        applyAutoSearchModeIfNeeded(newQuery)
        _query.value = newQuery
    }

    fun applyExternalQuery(sharedQuery: String) {
        val normalized = sharedQuery.trim()
            .lineSequence()
            .firstOrNull()
            ?.trim()
            .orEmpty()
        if (normalized.isBlank()) return
        if (normalized == lastInjectedExternalQuery && normalized == _query.value) return

        lastInjectedExternalQuery = normalized
        applyAutoSearchModeIfNeeded(normalized)
        _query.value = normalized
    }

    fun clearQuery() {
        _query.value = ""
        _searchMode.value = SearchMode.JAPANESE
        _isSearching.value = false
    }

    fun toggleSearchMode() {
        _searchMode.value = when (_searchMode.value) {
            SearchMode.JAPANESE -> SearchMode.ENGLISH
            SearchMode.ENGLISH -> SearchMode.ROMAJI
            SearchMode.ROMAJI -> SearchMode.JAPANESE
        }
    }

    private fun applyAutoSearchModeIfNeeded(query: String) {
        _searchMode.value = detectSearchMode(query)
    }

    private fun romajiQueryToHiragana(query: String): String? {
        val converted = RomajiConverter.toHiragana(query).trim()
        if (converted.isBlank()) return null
        val hasLatin = converted.any { it.isLetter() && it.code < 128 }
        return if (hasLatin) null else converted
    }

    private fun sortRomajiExactFirst(
        entries: List<MergedWordEntry>,
        hiraganaQuery: String
    ): List<MergedWordEntry> {
        val katakanaQuery = toKatakana(hiraganaQuery)
        return entries.sortedWith(
            compareBy<MergedWordEntry> { entry ->
                if (isRomajiExactMatch(entry, hiraganaQuery, katakanaQuery)) 0 else 1
            }.thenBy { entry ->
                if (entry.frequency > 0) entry.frequency else Int.MAX_VALUE
            }
        )
    }

    private suspend fun enrichWithExampleSentences(entries: List<MergedWordEntry>): List<MergedWordEntry> {
        return entries.mapIndexed { index, entry ->
            if (index >= 15 || entry.exampleSentence.isNotBlank()) {
                entry
            } else {
                val lookupExpression = entry.primaryExpression.ifBlank { entry.reading }
                val lookupReading = entry.reading.ifBlank { lookupExpression }
                if (lookupExpression.isBlank() && lookupReading.isBlank()) {
                    entry
                } else {
                    val sentence = runCatching {
                        sentenceDao.getSentencesByExpressionOrReading(
                            expression = lookupExpression,
                            reading = lookupReading
                        ).firstOrNull()
                    }.getOrNull()

                    if (sentence != null) {
                        entry.copy(
                            exampleSentence = sentence.sentenceJapanese,
                            exampleSentenceTranslation = sentence.sentenceEnglish
                        )
                    } else {
                        entry
                    }
                }
            }
        }
    }

    private fun isRomajiExactMatch(
        entry: MergedWordEntry,
        hiraganaQuery: String,
        katakanaQuery: String
    ): Boolean {
        val reading = entry.reading.trim()
        val expression = entry.primaryExpression.trim()
        if (reading == hiraganaQuery || reading == katakanaQuery) return true
        if (expression == hiraganaQuery || expression == katakanaQuery) return true
        return entry.alternativeExpressions.any {
            val alt = it.trim()
            alt == hiraganaQuery || alt == katakanaQuery
        }
    }

    private fun toKatakana(input: String): String {
        if (input.isBlank()) return input
        val sb = StringBuilder(input.length)
        for (ch in input) {
            val code = ch.code
            if (code in 0x3041..0x3096) {
                sb.append((code + 0x60).toChar())
            } else {
                sb.append(ch)
            }
        }
        return sb.toString()
    }

    companion object {
        internal fun detectSearchMode(query: String): SearchMode {
            val normalized = query.trim()
            if (normalized.isBlank()) return SearchMode.JAPANESE

            return if (containsJapaneseScript(normalized)) {
                SearchMode.JAPANESE
            } else {
                SearchMode.ENGLISH
            }
        }

        internal fun shouldUseRomajiFallback(query: String, romajiConverted: String): Boolean {
            val normalizedQuery = query.trim().lowercase()
            val normalizedConverted = romajiConverted.trim()
            if (normalizedQuery.isBlank() || normalizedConverted.isBlank()) return false
            return normalizedConverted != normalizedQuery
        }

        private fun containsJapaneseScript(text: String): Boolean {
            return text.any {
                when (Character.UnicodeScript.of(it.code)) {
                    Character.UnicodeScript.HAN,
                    Character.UnicodeScript.HIRAGANA,
                    Character.UnicodeScript.KATAKANA -> true
                    else -> false
                }
            }
        }
    }

    /**
     * Called when the user clicks on a word in search results.
     * Only saves the clicked word's expression to history.
     */
    fun onWordClicked(entry: MergedWordEntry) {
        val expression = entry.displayText()
        if (expression.isNotBlank()) {
            viewModelScope.launch {
                try {
                    searchHistoryDao.insert(SearchHistory(query = expression))
                } catch (_: Exception) { }
            }
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
