package com.yomitanmobile.ui.ankiscan

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yomitanmobile.data.anki.AnkiCollectionStore
import com.yomitanmobile.data.local.dao.AnkiSourceCount
import com.yomitanmobile.data.local.entity.AnkiCollectionWord
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Outcome of a restyle or a refresh, for the screen to put into words. */
sealed interface RefreshReport {
    data class Restyled(val restyled: Int, val refused: Int) : RefreshReport
    data class Refreshed(val updated: Int, val missing: Int, val refused: Int) : RefreshReport
    object Failed : RefreshReport
}

/**
 * Drives the "what does my AnkiDroid collection already contain?" screen.
 *
 * The word list is the diagnostic: the scan reads the collection through a
 * content provider whose behaviour differs across AnkiDroid versions, so
 * seeing the actual words (and which note type they came from) is the only
 * reliable way to tell a working scan from one that silently matched nothing.
 */
@HiltViewModel
class AnkiScanViewModel @Inject constructor(
    private val store: AnkiCollectionStore,
    private val refresher: com.yomitanmobile.data.anki.AnkiNoteRefresher
) : ViewModel() {

    private val logTag = "AnkiScanViewModel"

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private val _summary = MutableStateFlow<AnkiCollectionStore.ScanSummary?>(null)
    val summary: StateFlow<AnkiCollectionStore.ScanSummary?> = _summary.asStateFlow()

    private val _storedWordCount = MutableStateFlow(0)
    val storedWordCount: StateFlow<Int> = _storedWordCount.asStateFlow()

    private val _sources = MutableStateFlow<List<AnkiSourceCount>>(emptyList())
    val sources: StateFlow<List<AnkiSourceCount>> = _sources.asStateFlow()

    private val _words = MutableStateFlow<List<AnkiCollectionWord>>(emptyList())
    val words: StateFlow<List<AnkiCollectionWord>> = _words.asStateFlow()

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private var searchJob: Job? = null

    // ── Refreshing cards this app wrote earlier ──────────────────────────

    private val _refreshSurvey =
        MutableStateFlow<com.yomitanmobile.data.anki.AnkiNoteRefresher.Survey?>(null)
    val refreshSurvey: StateFlow<com.yomitanmobile.data.anki.AnkiNoteRefresher.Survey?> =
        _refreshSurvey.asStateFlow()

    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    /** Word being rewritten, with its position, while a refresh runs. */
    private val _refreshProgress = MutableStateFlow<Triple<Int, Int, String>?>(null)
    val refreshProgress: StateFlow<Triple<Int, Int, String>?> = _refreshProgress.asStateFlow()

    private val _refreshReport = MutableStateFlow<RefreshReport?>(null)
    val refreshReport: StateFlow<RefreshReport?> = _refreshReport.asStateFlow()

    private var refreshJob: Job? = null

    init {
        reloadStored()
        surveyRefresh()
    }

    /** Counts our note types and notes, writing nothing. */
    fun surveyRefresh() {
        viewModelScope.launch {
            _refreshSurvey.value = runCatching { refresher.survey() }
                .getOrElse {
                    Log.w(logTag, "Survey failed", it)
                    null
                }
        }
    }

    /**
     * Rewrites templates and CSS on every note type of ours.
     *
     * Touches no note, so it needs no confirmation: the worst case is that a
     * card renders with a layout the user then changes again.
     */
    fun restyleNoteTypes() {
        if (_refreshing.value) return
        _refreshing.value = true
        refreshJob = viewModelScope.launch {
            val result = runCatching { refresher.restyleAll() }.getOrNull()
            _refreshing.value = false
            _refreshReport.value = result
                ?.let { RefreshReport.Restyled(it.restyled, it.refused) }
                ?: RefreshReport.Failed
        }
    }

    /** Rebuilds the FIELDS of our notes. Writes into the collection. */
    fun refreshNotes(audioWanted: Boolean) {
        if (_refreshing.value) return
        _refreshing.value = true
        refreshJob = viewModelScope.launch {
            val result = runCatching {
                refresher.refreshAll(audioWanted) { done, total, word ->
                    _refreshProgress.value = Triple(done, total, word)
                }
            }.getOrNull()
            _refreshing.value = false
            _refreshProgress.value = null
            _refreshReport.value = result
                ?.let { RefreshReport.Refreshed(it.updated, it.notInDictionary, it.refused) }
                ?: RefreshReport.Failed
        }
    }

    fun cancelRefresh() {
        refreshJob?.cancel()
        _refreshing.value = false
        _refreshProgress.value = null
    }

    fun clearRefreshReport() {
        _refreshReport.value = null
    }

    fun setQuery(value: String) {
        // Stored raw, escaped only on the way into the LIKE (see [loadWords]):
        // escaping here put the backslashes into the text field the user is
        // typing in, so a typed % turned into a visible \\%.
        _query.value = value
        searchJob?.cancel()
        searchJob = viewModelScope.launch { loadWords() }
    }

    /** Runs a fresh provider sweep and replaces the stored scan. */
    fun scan() {
        if (_isScanning.value) return
        _isScanning.value = true
        _error.value = null
        viewModelScope.launch {
            try {
                val result = store.refresh()
                _summary.value = result
                if (!result.available) {
                    _error.value = "unavailable"
                }
                reloadStoredNow()
            } catch (e: Exception) {
                Log.e(logTag, "Collection scan failed", e)
                _error.value = e.message ?: "unknown error"
            } finally {
                _isScanning.value = false
            }
        }
    }

    fun clear() {
        viewModelScope.launch {
            store.clear()
            _summary.value = null
            reloadStoredNow()
        }
    }

    /** Plain-text dump of the stored words, for sharing or saving to a file. */
    suspend fun exportText(): String {
        val all = buildList {
            var offset = 0
            while (true) {
                val page = store.page(PAGE, offset)
                if (page.isEmpty()) break
                addAll(page)
                offset += page.size
                if (page.size < PAGE) break
            }
        }
        return buildString {
            append("# ").append(all.size).append(" words found in the AnkiDroid collection\n")
            for (row in all) {
                append(row.word)
                if (row.source.isNotBlank()) append('\t').append(row.source)
                append('\n')
            }
        }
    }

    private fun reloadStored() {
        viewModelScope.launch { reloadStoredNow() }
    }

    private suspend fun reloadStoredNow() {
        _sources.value = store.countsBySource()
        _storedWordCount.value = _sources.value.sumOf { it.wordCount }
        loadWords()
    }

    private suspend fun loadWords() {
        val q = _query.value.trim()
        // Without escaping, typing % listed the whole collection and _ matched
        // any single character.
        _words.value = if (q.isEmpty()) {
            store.page(PAGE, 0)
        } else {
            store.search(com.yomitanmobile.util.InputSanitizer.sanitizeLikeQuery(q), PAGE)
        }
    }

    private companion object {
        /** The list is a sample for verification, not a browsable database. */
        const val PAGE = 500
    }
}
