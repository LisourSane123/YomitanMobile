package com.yomitanmobile.ui.jlptdeck

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yomitanmobile.data.anki.AnkiCardCreator
import com.yomitanmobile.data.anki.AnkiCollectionIndex
import com.yomitanmobile.data.audio.AudioPlayer
import com.yomitanmobile.data.local.dao.ExportedWordDao
import com.yomitanmobile.data.local.dao.JlptTagDao
import com.yomitanmobile.data.settings.readCardStylePreferences
import com.yomitanmobile.dataStore
import com.yomitanmobile.domain.model.JlptDeckFilters
import com.yomitanmobile.domain.model.JlptDeckPlan
import com.yomitanmobile.domain.model.JlptDeckProgress
import com.yomitanmobile.domain.model.JlptDeckResult
import com.yomitanmobile.domain.model.MergedWordEntry
import com.yomitanmobile.domain.model.WordEntry
import com.yomitanmobile.domain.repository.DictionaryRepository
import com.yomitanmobile.domain.usecase.JlptDeckPlanner
import com.yomitanmobile.util.JlptVocabulary
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed class JlptDeckEvent {
    data class Finished(val result: JlptDeckResult) : JlptDeckEvent()
    data class Error(val message: String) : JlptDeckEvent()
    object PermissionRequired : JlptDeckEvent()
    object AnkiNotInstalled : JlptDeckEvent()
    object Cancelled : JlptDeckEvent()
}

/**
 * Drives the bulk "make me a deck for JLPT level N" flow:
 * analyse (dry run) → review the plan → write to AnkiDroid.
 *
 * Generated cards are intentionally NOT recorded in `exported_words`. They
 * are not mined words, so counting them would swamp the mining statistics;
 * the "don't create what I already have" guarantee comes from scanning the
 * AnkiDroid collection instead, which also covers Core / Kaishi decks and
 * previously generated levels.
 */
@HiltViewModel
class JlptDeckViewModel @Inject constructor(
    private val repository: DictionaryRepository,
    private val ankiCardCreator: AnkiCardCreator,
    private val ankiCollectionIndex: AnkiCollectionIndex,
    private val exportedWordDao: ExportedWordDao,
    private val jlptTagDao: JlptTagDao,
    private val audioPlayer: AudioPlayer,
    @ApplicationContext private val appContext: Context
) : ViewModel() {

    private val logTag = "JlptDeckViewModel"

    private val _level = MutableStateFlow(5)
    val level: StateFlow<Int> = _level.asStateFlow()

    private val _filters = MutableStateFlow(JlptDeckFilters())
    val filters: StateFlow<JlptDeckFilters> = _filters.asStateFlow()

    private val _deckName = MutableStateFlow(defaultDeckName(5))
    val deckName: StateFlow<String> = _deckName.asStateFlow()

    private val _isAnalyzing = MutableStateFlow(false)
    val isAnalyzing: StateFlow<Boolean> = _isAnalyzing.asStateFlow()

    private val _plan = MutableStateFlow<JlptDeckPlan?>(null)
    val plan: StateFlow<JlptDeckPlan?> = _plan.asStateFlow()

    private val _progress = MutableStateFlow<JlptDeckProgress?>(null)
    val progress: StateFlow<JlptDeckProgress?> = _progress.asStateFlow()

    private val _availableDecks = MutableStateFlow<List<String>>(emptyList())
    val availableDecks: StateFlow<List<String>> = _availableDecks.asStateFlow()

    private val _events = MutableSharedFlow<JlptDeckEvent>()
    val events: SharedFlow<JlptDeckEvent> = _events.asSharedFlow()

    /**
     * How many words the installed dictionaries actually tag with the selected
     * level. Zero means the deck can only be built from the small curated
     * built-in list — the single biggest reason a generated deck comes out
     * far shorter than the real JLPT vocabulary — so the UI says so upfront
     * instead of only after an empty analysis.
     */
    private val _taggedWordCount = MutableStateFlow<Int?>(null)
    val taggedWordCount: StateFlow<Int?> = _taggedWordCount.asStateFlow()

    private var generationJob: Job? = null

    init {
        viewModelScope.launch {
            _availableDecks.value = runCatching { ankiCardCreator.getAvailableDecks() }
                .getOrDefault(emptyList())
        }
        refreshTaggedWordCount(_level.value)
    }

    private fun refreshTaggedWordCount(level: Int) {
        viewModelScope.launch {
            _taggedWordCount.value = runCatching { jlptTagDao.countForLevel(level) }
                .getOrElse {
                    Log.w(logTag, "Counting JLPT tags failed", it)
                    null
                }
        }
    }

    fun setLevel(level: Int) {
        if (level == _level.value) return
        _level.value = level
        // Keep the deck name in sync as long as the user hasn't renamed it.
        if (_deckName.value == defaultDeckName(_level.value) || _deckName.value.isBlank() ||
            LEVELS.any { _deckName.value == defaultDeckName(it) }
        ) {
            _deckName.value = defaultDeckName(level)
        }
        _plan.value = null
        refreshTaggedWordCount(level)
    }

    fun setDeckName(name: String) {
        _deckName.value = name
    }

    fun updateFilters(transform: (JlptDeckFilters) -> JlptDeckFilters) {
        _filters.value = transform(_filters.value)
        // Any filter change invalidates the dry run.
        _plan.value = null
    }

    /**
     * Dry run: resolve the level's word list against the installed
     * dictionaries, scan AnkiDroid, apply the filters and report what would
     * be created. Nothing is written.
     */
    fun analyze() {
        if (_isAnalyzing.value) return
        _isAnalyzing.value = true
        viewModelScope.launch {
            try {
                val level = _level.value
                val filters = _filters.value
                val candidates = collectCandidates(level)

                val index = if (filters.skipAlreadyInAnki) {
                    ankiCollectionIndex.build()
                } else {
                    AnkiCollectionIndex.Index.EMPTY
                }

                val minedKeys: Set<String> = if (filters.skipAlreadyMined) {
                    runCatching {
                        exportedWordDao.getAllExports()
                            .mapTo(HashSet()) { matchKey(it.expression, it.reading) }
                    }.getOrElse {
                        Log.w(logTag, "Reading exported words failed", it)
                        emptySet()
                    }
                } else {
                    emptySet()
                }

                _plan.value = JlptDeckPlanner.plan(
                    level = level,
                    candidates = candidates,
                    filters = filters,
                    isInAnki = { index.contains(it.primaryExpression, it.reading) },
                    isMined = { matchKey(it.primaryExpression, it.reading) in minedKeys },
                    ankiScanUnavailable = filters.skipAlreadyInAnki && !index.available,
                    scannedNoteCount = index.noteCount
                )
            } catch (e: Exception) {
                Log.e(logTag, "Analyze failed", e)
                _events.emit(JlptDeckEvent.Error(e.message ?: "unknown error"))
            } finally {
                _isAnalyzing.value = false
            }
        }
    }

    /** Writes the planned cards to AnkiDroid. */
    fun generate() {
        val plan = _plan.value ?: return
        if (plan.selected.isEmpty()) return
        if (generationJob?.isActive == true) return

        generationJob = viewModelScope.launch {
            _progress.value = JlptDeckProgress(0, plan.selectedCount)
            try {
                val stylePrefs = readCardStylePreferences(appContext.dataStore.data.first())
                val tts = if (_filters.value.generateAudio) audioPlayer.getTts() else null
                val deck = _deckName.value.trim().ifBlank { defaultDeckName(plan.level) }

                val result = ankiCardCreator.exportBatchToAnki(
                    entries = plan.selected.map { it.toWordEntry() },
                    deckName = deck,
                    stylePrefs = stylePrefs,
                    kanjiProvider = { kanji -> repository.getKanjis(kanji) },
                    tts = tts,
                    tags = tagsForLevel(plan.level),
                    onProgress = { done, total, word ->
                        _progress.value = JlptDeckProgress(done, total, word)
                    }
                )

                result.fold(
                    onSuccess = { batch ->
                        _events.emit(
                            JlptDeckEvent.Finished(
                                JlptDeckResult(
                                    deckName = deck,
                                    added = batch.added,
                                    failed = batch.failed
                                )
                            )
                        )
                        // The freshly created cards are now part of the
                        // collection, so a re-run must see them: drop the plan.
                        _plan.value = null
                    },
                    onFailure = { error ->
                        when (error) {
                            is SecurityException -> _events.emit(JlptDeckEvent.PermissionRequired)
                            is IllegalStateException ->
                                if (error.message?.contains("not installed") == true) {
                                    _events.emit(JlptDeckEvent.AnkiNotInstalled)
                                } else {
                                    _events.emit(JlptDeckEvent.Error(error.message.orEmpty()))
                                }
                            else -> _events.emit(JlptDeckEvent.Error(error.message ?: "unknown error"))
                        }
                    }
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                _events.emit(JlptDeckEvent.Cancelled)
                throw e
            } catch (e: Exception) {
                Log.e(logTag, "Deck generation failed", e)
                _events.emit(JlptDeckEvent.Error(e.message ?: "unknown error"))
            } finally {
                _progress.value = null
            }
        }
    }

    fun cancelGeneration() {
        generationJob?.cancel()
    }

    /**
     * Words considered for the level: everything the installed dictionaries
     * tagged themselves, plus the built-in curated list resolved against those
     * dictionaries. The union matters — a plain JMdict install has no JLPT
     * tags at all and would otherwise yield nothing, while Jitendex covers far
     * more words than the built-in list.
     */
    private suspend fun collectCandidates(level: Int): List<MergedWordEntry> {
        val tagged = repository.getEntriesByJlptLevel(level)

        val builtIn = JlptVocabulary.wordsForLevel(level)
        val alreadyCovered = tagged.mapTo(HashSet()) { matchKey(it.expression, it.reading) }
        val missing = builtIn.filterNot { (expression, reading) ->
            matchKey(expression, reading) in alreadyCovered
        }

        val resolved: List<WordEntry> = if (missing.isEmpty()) {
            emptyList()
        } else {
            val wanted = missing.mapTo(HashSet()) { matchKey(it.first, it.second) }
            // Kana-only list entries store the same string in both columns;
            // matching on the pair keeps homophones from sneaking in.
            repository.getEntriesForExpressions(missing.map { it.first })
                .filter { matchKey(it.expression, it.reading) in wanted }
        }

        return MergedWordEntry.mergeEntries(tagged + resolved)
    }

    /**
     * (expression, reading) identity used for every set lookup here: lining
     * the built-in word list up with the dictionary rows, and matching
     * candidates against already-mined words. NUL separates the two halves so
     * no expression/reading combination can collide with another.
     *
     * The reading is folded to hiragana first. The curated list and the
     * `exported_words` log write readings in hiragana while dictionaries store
     * plenty of them in katakana, and a strict comparison quietly dropped
     * exactly those words from the deck (or re-created words already mined).
     */
    private fun matchKey(expression: String, reading: String): String {
        val expr = expression.trim()
        val read = reading.trim().ifEmpty { expr }
        return expr + "\u0000" + read.toHiragana()
    }

    /**
     * Katakana to hiragana. The katakana block maps onto hiragana with a fixed
     * offset; the prolonged-sound mark and everything else pass through.
     */
    private fun String.toHiragana(): String = buildString(length) {
        for (c in this@toHiragana) {
            append(if (c.code in 0x30A1..0x30F6) (c.code - 0x60).toChar() else c)
        }
    }

    private fun tagsForLevel(level: Int): Set<String> =
        setOf("yomitan-mobile", "jlpt-n$level", "auto-generated")

    companion object {
        val LEVELS = listOf(5, 4, 3, 2, 1)

        fun defaultDeckName(level: Int): String = "JLPT N$level"
    }
}
