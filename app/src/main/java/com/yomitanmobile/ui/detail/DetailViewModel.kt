package com.yomitanmobile.ui.detail

import android.content.Context
import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yomitanmobile.MainActivity
import com.yomitanmobile.data.ai.AiSummaryResult
import com.yomitanmobile.data.ai.AiSummaryService
import com.yomitanmobile.data.anki.AnkiCardCreator
import com.yomitanmobile.data.anki.AnkiCollectionStore
import com.yomitanmobile.data.anki.MonolingualCardResolver
import com.yomitanmobile.data.audio.AudioPlayer
import com.yomitanmobile.data.local.dao.ExportedWordDao
import com.yomitanmobile.data.local.dao.FavoriteWordDao
import com.yomitanmobile.data.local.dao.LookupCountDao
import com.yomitanmobile.data.local.dao.SentenceDao
import com.yomitanmobile.data.local.entity.ExportedWord
import com.yomitanmobile.data.local.entity.FavoriteWord
import com.yomitanmobile.data.mapper.toKanjiInfo
import com.yomitanmobile.data.settings.readCardStylePreferences
import com.yomitanmobile.dataStore
import com.yomitanmobile.domain.model.CardStylePreferences
import com.yomitanmobile.domain.model.KanjiInfo
import com.yomitanmobile.domain.model.MergedWordEntry
import com.yomitanmobile.domain.model.PitchAccentStyle
import com.yomitanmobile.domain.model.WordEntry
import com.yomitanmobile.domain.model.WordFrequencyInfo
import com.yomitanmobile.domain.repository.DictionaryRepository
import com.yomitanmobile.domain.usecase.GetWordDetailUseCase
import com.yomitanmobile.util.FuriganaGenerator
import com.yomitanmobile.util.InputSanitizer
import com.yomitanmobile.util.JlptVocabulary
import com.yomitanmobile.util.LocaleHelper
import com.yomitanmobile.util.SentenceContextHighlighter
import com.yomitanmobile.util.WordCategoryClassifier
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import androidx.datastore.preferences.core.edit
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import javax.inject.Inject

sealed class DetailEvent {
    data class AnkiExportSuccess(val noteId: Long) : DetailEvent()
    data class AnkiExportError(val message: String) : DetailEvent()
    object AnkiPermissionRequired : DetailEvent()
    object AnkiNotInstalled : DetailEvent()
    data class AnkiDeckSelectionRequired(val decks: List<String>) : DetailEvent()
    data class AlreadyExported(val expression: String, val deckName: String) : DetailEvent()

    /**
     * The word is not in this app's export log, but the stored AnkiDroid
     * collection scan says a card for it already exists somewhere — a Core /
     * Kaishi deck, or mining done before this app was installed. The UI offers
     * "export anyway"; we never block outright, because the scan matches on
     * the written form and a homograph could be a genuinely different word.
     */
    data class AlreadyInCollection(val expression: String) : DetailEvent()

    /**
     * AI summary call failed. The export coroutine is parked on a
     * CompletableDeferred until the UI calls [DetailViewModel.resolveAiFailure]
     * with the user's choice — either finish the card with an empty summary
     * slot, or abort the export entirely. [message] is the provider's
     * error string (rate limit, bad key, network…) for display in the dialog.
     */
    data class AiSummaryFailedNeedsChoice(val message: String) : DetailEvent()

    /** Emitted after the user picks "abort" on the AI-failure dialog. */
    object AnkiExportCancelled : DetailEvent()
}

/** Options the user can pick when AI summary generation fails mid-export. */
enum class AiFailureChoice { CONTINUE_WITHOUT_AI, CANCEL_EXPORT }

@HiltViewModel
class DetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val getWordDetailUseCase: GetWordDetailUseCase,
    private val repository: DictionaryRepository,
    private val ankiCardCreator: AnkiCardCreator,
    private val audioPlayer: AudioPlayer,
    private val sentenceDao: SentenceDao,
    private val aiSummaryService: AiSummaryService,
    private val exportedWordDao: ExportedWordDao,
    private val ankiCollectionStore: AnkiCollectionStore,
    private val monolingualCardResolver: MonolingualCardResolver,
    private val favoriteWordDao: FavoriteWordDao,
    private val lookupCountDao: LookupCountDao,
    @ApplicationContext private val appContext: Context
) : ViewModel() {

    private val logTag = "DetailViewModel"

    private val entryId: Long = savedStateHandle.get<Long>("entryId") ?: 0L

    private val _entry = MutableStateFlow<MergedWordEntry?>(null)
    val entry: StateFlow<MergedWordEntry?> = _entry.asStateFlow()

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _isExporting = MutableStateFlow(false)
    val isExporting: StateFlow<Boolean> = _isExporting.asStateFlow()

    private val _events = MutableSharedFlow<DetailEvent>()
    val events: SharedFlow<DetailEvent> = _events.asSharedFlow()

    val isPlaying: StateFlow<Boolean> = audioPlayer.isPlaying
    val ttsReady: StateFlow<Boolean> = audioPlayer.ttsReady

    private val _isFavorite = MutableStateFlow(false)
    val isFavorite: StateFlow<Boolean> = _isFavorite.asStateFlow()

    /**
     * Running count of how many times the user has opened this word's
     * detail page (tracked per `(expression, reading)` in the
     * `lookup_counts` table). Updated every time [loadEntry] resolves.
     * The UI surfaces it as "Looked up N×" and, past a threshold,
     * suggests favoriting / exporting.
     */
    private val _lookupCount = MutableStateFlow(0)
    val lookupCount: StateFlow<Int> = _lookupCount.asStateFlow()

    /**
     * Per-kanji breakdown (char + On/Kun readings + meanings) for every
     * kanji in the current word, in the order they appear in the
     * expression. Same source and shape as the Anki export's
     * KanjiBreakdown field — the detail screen renders it in a dedicated
     * "Kanji" card. Empty for kana-only words or when no kanji dictionary
     * is installed.
     */
    private val _kanjiInfo = MutableStateFlow<List<KanjiInfo>>(emptyList())
    val kanjiInfo: StateFlow<List<KanjiInfo>> = _kanjiInfo.asStateFlow()

    /**
     * Per-source frequency ranks for the current word, already ordered by the
     * user's priority preference and collapsed to the top list when "show all"
     * is off. Empty when no frequency list covers the word. Rendered as chips
     * in the detail header.
     */
    private val _frequencies = MutableStateFlow<List<WordFrequencyInfo>>(emptyList())
    val frequencies: StateFlow<List<WordFrequencyInfo>> = _frequencies.asStateFlow()

    /**
     * Synthesised furigana for example sentences that arrived without ruby
     * data (plain-JMDict examples, seeded sentences, or Jitendex imported
     * before the parser preserved readings). Keyed by the sentence's plain JP
     * text; the detail screen falls back to this map when an
     * [com.yomitanmobile.domain.model.ExamplePair] carries no segments, so
     * tapping a kanji still reveals its reading. Empty until the lookup
     * resolves and for sentences whose words aren't in the installed
     * dictionary.
     */
    private val _generatedFurigana =
        MutableStateFlow<Map<String, List<com.yomitanmobile.domain.model.FuriganaSegment>>>(emptyMap())
    val generatedFurigana: StateFlow<Map<String, List<com.yomitanmobile.domain.model.FuriganaSegment>>> =
        _generatedFurigana.asStateFlow()

    /**
     * Coroutine handoff for the AI-failure dialog. Set internal so the
     * extracted gate can be exercised in tests via [resolveAiFailure].
     * [_isExporting] ensures only one decision can be in flight at a time.
     */
    private val aiFailureGate = AiFailureGate()

    /**
     * Called by the UI when the AI-failure dialog is dismissed. Safe to
     * call when no export is parked — the gate treats that as a no-op.
     */
    fun resolveAiFailure(choice: AiFailureChoice) {
        aiFailureGate.resolve(choice)
    }

    init {
        loadEntry()
        audioPlayer.initTts()
    }

    private fun loadEntry() {
        viewModelScope.launch {
            _isLoading.value = true
            val word = getWordDetailUseCase.invoke(entryId)
            _entry.value = word?.let { baseWord ->
                val normalizedReading = baseWord.reading.ifBlank { baseWord.expression }
                val relatedEntries = runCatching {
                    repository.getEntriesByReading(normalizedReading)
                }.getOrDefault(emptyList())

                val mergeInput = (relatedEntries + baseWord)
                    .distinctBy { it.id }
                    .ifEmpty { listOf(baseWord) }

                val merged = MergedWordEntry.mergeEntries(mergeInput)
                val picked = merged.firstOrNull { entryId in it.entryIds }
                    ?: merged.firstOrNull { it.primaryId == entryId }
                    ?: merged.firstOrNull()
                picked?.let { entry ->
                    if (entry.jlptLevel > 0) entry
                    else {
                        val level = JlptVocabulary.getLevel(entry.primaryExpression, entry.reading)
                        if (level > 0) entry.copy(jlptLevel = level) else entry
                    }
                }
            }
            _isLoading.value = false
            checkFavoriteStatus()
            recordLookup()
            loadKanjiBreakdown()
            loadFrequencies()
            loadExampleFurigana()
        }
    }

    /**
     * Builds tappable furigana for any example sentence that shipped without
     * ruby segments. Collects the kanji words across every displayed sentence,
     * resolves their readings in a single dictionary query, then aligns each
     * reading onto its kanji via [com.yomitanmobile.util.FuriganaGenerator].
     * Best-effort: leaves the map empty on any failure so the sentence still
     * renders as plain text.
     */
    private fun loadExampleFurigana() {
        val merged = _entry.value ?: return
        val sentences = buildList {
            // Synthesise for any example that carries no real ruby reading —
            // that's both the legacy no-segments imports AND current-Jitendex
            // sentences that shipped with no ruby at all (~14%; Jitendex is
            // all-or-nothing per sentence). Sentences that already have ruby
            // readings are left to the dictionary's own segments.
            merged.examples.forEach {
                if (it.segments.none { seg -> seg.reading.isNotBlank() }) add(it.jp)
            }
            if (merged.examples.isEmpty() && merged.exampleSentence.isNotBlank()) {
                add(merged.exampleSentence)
            }
        }.filter { it.isNotBlank() && MergedWordEntry.containsKanji(it) }
            .distinct()
        if (sentences.isEmpty()) return

        viewModelScope.launch {
            runCatching {
                val candidates = sentences
                    .flatMapTo(HashSet()) { FuriganaGenerator.candidateExpressions(it) }
                    .toList()
                val readings = repository.getReadingsForExpressions(candidates)
                sentences.associateWith { FuriganaGenerator.generate(it, readings) }
            }.onSuccess { _generatedFurigana.value = it }
                .onFailure { exception ->
                    Log.w(logTag, "Example furigana generation failed", exception)
                }
        }
    }

    /**
     * Before export, fills in synthesised furigana segments for any example
     * that shipped without Jitendex ruby (~14% of Jitendex sentences, plus
     * plain imports), so the exported Anki card can render tap-to-reveal
     * furigana on those sentences too. Ruby examples are left untouched.
     * Best-effort: returns the word unchanged on any failure.
     */
    private suspend fun enrichExamplesWithFurigana(word: WordEntry): WordEntry {
        val needsSynthesis = { ex: com.yomitanmobile.domain.model.ExamplePair ->
            ex.segments.none { it.reading.isNotBlank() } && MergedWordEntry.containsKanji(ex.jp)
        }
        if (word.examples.none(needsSynthesis)) return word
        return runCatching {
            val candidates = word.examples
                .filter(needsSynthesis)
                .flatMapTo(HashSet()) { FuriganaGenerator.candidateExpressions(it.jp) }
                .toList()
            val readings = repository.getReadingsForExpressions(candidates)
            word.copy(
                examples = word.examples.map { ex ->
                    if (!needsSynthesis(ex)) ex
                    else {
                        val segs = FuriganaGenerator.generate(ex.jp, readings)
                        if (segs.any { it.reading.isNotBlank() }) ex.copy(segments = segs) else ex
                    }
                }
            )
        }.getOrDefault(word)
    }

    /**
     * Makes sure the exported word carries a usable example sentence.
     *
     * Sentence-source priority order:
     *   1. Jitendex examples already attached to the entry (preferred)
     *   2. Pre-seeded local SentenceDao matches
     * The online Tatoeba fallback was removed — Jitendex covers most words and
     * the seeded SentenceDao handles common beginner words. An empty Sentence
     * field just collapses the back-side block via the {{#Sentence}} mustache.
     *
     * The local lookup also kicks in when the attached examples exist but NONE
     * of them actually contains the target word: the front-context highlight
     * would have nothing to mark there, and a seeded sentence that does contain
     * the word is the better front. It only ever fills the legacy single
     * example columns, so the back-side {{Sentence}} block — which prefers the
     * attached examples — stays untouched.
     */
    private suspend fun fillSentenceForExport(
        word: WordEntry,
        stylePrefs: CardStylePreferences
    ): WordEntry {
        val lookup = word.expression.ifBlank { word.reading }
        val tokens = listOf(word.expression, word.reading)
        val hasAttachedMatch = word.examples.any {
            it.jp.isNotBlank() && SentenceContextHighlighter.containsTarget(it.jp, tokens)
        }
        val needsLocalLookup = when {
            word.examples.isEmpty() -> word.exampleSentence.isBlank()
            // Attached examples cover the back of the card; only a missing
            // highlightable sentence for the front justifies another query.
            stylePrefs.showFrontContextSentence -> !hasAttachedMatch &&
                !SentenceContextHighlighter.containsTarget(word.exampleSentence, tokens)
            else -> false
        }
        if (!needsLocalLookup) return word

        val localSentences = runCatching {
            sentenceDao.getSentencesByExpressionOrReading(
                expression = lookup,
                reading = word.reading.ifBlank { lookup }
            )
        }.getOrElse {
            Log.w(logTag, "Local sentence lookup failed", it)
            emptyList()
        }
        if (localSentences.isEmpty()) return word

        val best = localSentences.firstOrNull {
            SentenceContextHighlighter.containsTarget(it.sentenceJapanese, tokens)
        } ?: localSentences.first()

        return word.copy(
            exampleSentence = best.sentenceJapanese,
            exampleSentenceTranslation = best.sentenceEnglish
        )
    }

    /**
     * Loads every installed list's frequency rank for the current word and
     * orders them by the user's priority preference (collapsing to the top
     * list when "show all" is off). Best-effort: leaves the list empty on any
     * failure so the rest of the detail screen still renders.
     */
    private fun loadFrequencies() {
        val merged = _entry.value
        if (merged == null) {
            _frequencies.value = emptyList()
            return
        }
        val expression = merged.primaryExpression.ifBlank { merged.reading }
        val reading = merged.reading.ifBlank { expression }
        viewModelScope.launch {
            runCatching {
                val raw = repository.getFrequencies(expression, reading)
                val prefs = appContext.dataStore.data.first()
                val priority = (prefs[MainActivity.FREQUENCY_DISPLAY_ORDER] ?: "")
                    .split(',').map { it.trim() }.filter { it.isNotBlank() }
                val showAll = prefs[MainActivity.FREQUENCY_SHOW_ALL] ?: true
                WordFrequencyInfo.order(raw, priority, showAll)
            }.onSuccess { _frequencies.value = it }
                .onFailure { exception ->
                    _frequencies.value = emptyList()
                    Log.w(logTag, "Frequency load failed", exception)
                }
        }
    }

    /**
     * Loads the kanji breakdown for the resolved word. Best-effort: filters
     * the kanji characters out of the primary expression, fetches their
     * entries via the same [DictionaryRepository.getKanjis] path the Anki
     * export uses, and orders them by first appearance in the expression so
     * the on-screen card matches reading order. Leaves the list empty (no
     * card shown) for kana-only words or when the kanji lookup fails.
     */
    private fun loadKanjiBreakdown() {
        val merged = _entry.value
        if (merged == null) {
            _kanjiInfo.value = emptyList()
            return
        }
        val expression = merged.primaryExpression
        val kanjiChars = expression
            .filter { MergedWordEntry.isKanji(it) }
            .map { it.toString() }
            .distinct()
        if (kanjiChars.isEmpty()) {
            _kanjiInfo.value = emptyList()
            return
        }
        viewModelScope.launch {
            runCatching {
                repository.getKanjis(kanjiChars)
                    .map { it.toKanjiInfo() }
                    .sortedBy { info ->
                        expression.indexOf(info.kanji).takeIf { it >= 0 } ?: Int.MAX_VALUE
                    }
            }.onSuccess { _kanjiInfo.value = it }
                .onFailure { exception ->
                    _kanjiInfo.value = emptyList()
                    Log.w(logTag, "Kanji breakdown load failed", exception)
                }
        }
    }

    /**
     * Increments the per-word lookup counter and republishes the new
     * value to [_lookupCount]. Best-effort — if the DB call throws (e.g.
     * the user is on a pre-migration DB during an upgrade), we leave the
     * UI showing 0 rather than blocking detail rendering.
     */
    private fun recordLookup() {
        val merged = _entry.value ?: return
        val expression = merged.primaryExpression
        if (expression.isBlank()) return
        val reading = merged.reading.ifBlank { expression }

        viewModelScope.launch {
            runCatching {
                lookupCountDao.incrementOrInsert(
                    expression = expression,
                    reading = reading,
                    now = Instant.now().toEpochMilli()
                )
                lookupCountDao.getCount(expression, reading) ?: 0
            }.onSuccess { count ->
                _lookupCount.value = count
            }.onFailure { exception ->
                Log.w(logTag, "Lookup-count update failed", exception)
            }
        }
    }

    private fun checkFavoriteStatus() {
        val merged = _entry.value ?: return
        viewModelScope.launch {
            try {
                val reading = merged.reading.ifBlank { merged.primaryExpression }
                favoriteWordDao.isFavorite(merged.primaryExpression, reading).collect { fav ->
                    _isFavorite.value = fav
                }
            } catch (_: Exception) { }
        }
    }

    fun toggleFavorite() {
        val merged = _entry.value ?: return
        viewModelScope.launch {
            try {
                val expression = merged.primaryExpression
                val reading = merged.reading.ifBlank { expression }
                if (_isFavorite.value) {
                    favoriteWordDao.delete(expression, reading)
                } else {
                    favoriteWordDao.insert(
                        FavoriteWord(
                            expression = expression,
                            reading = reading,
                            definitionPreview = merged.definitionTextShort(),
                            entryId = merged.primaryId
                        )
                    )
                }
            } catch (_: Exception) { }
        }
    }

    fun playAudio() {
        val merged = _entry.value ?: return
        val textToSpeak = merged.reading.ifBlank { merged.primaryExpression }
        audioPlayer.playWord(textToSpeak, merged.audioFile.takeIf { it.isNotBlank() })
    }

    fun stopAudio() {
        audioPlayer.stopPlayback()
    }

    fun exportToAnki(includeAiSummary: Boolean = false) {
        val merged = _entry.value ?: return
        val word = merged.toWordEntry()
        viewModelScope.launch {
            try {
                if (!ankiCardCreator.isAnkiInstalled()) {
                    _events.emit(DetailEvent.AnkiNotInstalled)
                    return@launch
                }
                if (!ankiCardCreator.hasAnkiPermission()) {
                    _events.emit(DetailEvent.AnkiPermissionRequired)
                    return@launch
                }

                // Check if deck is already selected
                val savedDeck = appContext.dataStore.data
                    .map { it[MainActivity.ANKI_DECK_NAME] }
                    .first()

                if (savedDeck.isNullOrBlank()) {
                    // Need to let user pick a deck first
                    val decks = ankiCardCreator.getAvailableDecks()
                    _events.emit(DetailEvent.AnkiDeckSelectionRequired(decks))
                    return@launch
                }

                val sanitizedDeck = InputSanitizer.sanitizeDeckName(savedDeck)
                val safeExpression = normalizeExpression(word.expression, word.reading)
                val safeReading = normalizeReading(safeExpression, word.reading)

                // Check if already exported
                val existing = findExistingExport(
                    expression = safeExpression,
                    reading = safeReading,
                    deckName = sanitizedDeck
                )
                if (existing != null) {
                    _events.emit(DetailEvent.AlreadyExported(safeExpression, sanitizedDeck))
                    return@launch
                }

                // Second guard: a card may exist in AnkiDroid without ever
                // passing through this app (Core, Kaishi, an older setup). The
                // stored collection scan knows about those; an empty store just
                // means "not scanned" and lets the export through.
                if (isInScannedCollection(safeExpression, safeReading)) {
                    _events.emit(DetailEvent.AlreadyInCollection(safeExpression))
                    return@launch
                }

                performExport(word, sanitizedDeck, includeAiSummary)
            } catch (exception: Exception) {
                Log.e(logTag, "Export pre-check failed", exception)
                _events.emit(DetailEvent.AnkiExportError(exception.message ?: "Unknown error"))
            }
        }
    }

    fun forceExport(includeAiSummary: Boolean = false) {
        val merged = _entry.value ?: return
        val word = merged.toWordEntry()
        viewModelScope.launch {
            try {
                val savedDeckRaw = appContext.dataStore.data
                    .map { it[MainActivity.ANKI_DECK_NAME] }
                    .first() ?: "Mining Deck"
                val savedDeck = InputSanitizer.sanitizeDeckName(savedDeckRaw)
                performExport(word, savedDeck, includeAiSummary)
            } catch (exception: Exception) {
                Log.e(logTag, "Force export failed", exception)
                _events.emit(DetailEvent.AnkiExportError(exception.message ?: "Unknown error"))
            }
        }
    }

    fun exportToAnkiWithDeck(deckName: String, includeAiSummary: Boolean = false) {
        val merged = _entry.value ?: return
        val word = merged.toWordEntry()
        val sanitizedDeck = InputSanitizer.sanitizeDeckName(deckName)
        viewModelScope.launch {
            try {
                appContext.dataStore.edit { prefs ->
                    prefs[MainActivity.ANKI_DECK_NAME] = sanitizedDeck
                }

                val safeExpression = normalizeExpression(word.expression, word.reading)
                val safeReading = normalizeReading(safeExpression, word.reading)

                // Check if already exported to this deck
                val existing = findExistingExport(
                    expression = safeExpression,
                    reading = safeReading,
                    deckName = sanitizedDeck
                )
                if (existing != null) {
                    _events.emit(DetailEvent.AlreadyExported(safeExpression, sanitizedDeck))
                    return@launch
                }

                // Second guard: a card may exist in AnkiDroid without ever
                // passing through this app (Core, Kaishi, an older setup). The
                // stored collection scan knows about those; an empty store just
                // means "not scanned" and lets the export through.
                if (isInScannedCollection(safeExpression, safeReading)) {
                    _events.emit(DetailEvent.AlreadyInCollection(safeExpression))
                    return@launch
                }

                performExport(word, sanitizedDeck, includeAiSummary)
            } catch (exception: Exception) {
                Log.e(logTag, "Export with deck failed", exception)
                _events.emit(DetailEvent.AnkiExportError(exception.message ?: "Unknown error"))
            }
        }
    }

    private fun normalizeExpression(expression: String, reading: String): String {
        val normalizedReading = reading.trim()
        return expression.trim().ifBlank { normalizedReading }
    }

    private fun normalizeReading(expression: String, reading: String): String {
        return reading.trim().ifBlank { expression.trim() }
    }

    private suspend fun isInScannedCollection(expression: String, reading: String): Boolean =
        runCatching { ankiCollectionStore.contains(expression, reading) }
            .getOrElse {
                Log.w(logTag, "Collection duplicate check failed; allowing the export", it)
                false
            }

    private suspend fun findExistingExport(
        expression: String,
        reading: String,
        deckName: String
    ): ExportedWord? {
        val direct = exportedWordDao.findExported(expression, reading, deckName)
        if (direct != null) return direct

        // Backward compatibility: previous app versions could store empty reading.
        val emptyReadingMatch = exportedWordDao.findExported(expression, "", deckName)
        if (emptyReadingMatch != null) return emptyReadingMatch

        // Backward compatibility: previous app versions could mirror expression in reading.
        if (reading != expression) {
            val expressionReadingMatch = exportedWordDao.findExported(expression, expression, deckName)
            if (expressionReadingMatch != null) return expressionReadingMatch
        }

        return null
    }

    private suspend fun loadCardStylePreferences(): CardStylePreferences =
        readCardStylePreferences(appContext.dataStore.data.first())

    private suspend fun performExport(
        word: WordEntry,
        deckName: String,
        includeAiSummary: Boolean
    ) {
        _isExporting.value = true
        try {
            val stylePrefs = loadCardStylePreferences()

            // Sentence-source priority order:
            //   1. Jitendex examples already attached to the entry (preferred)
            //   2. Pre-seeded local SentenceDao matches
            // The online Tatoeba fallback was removed — Jitendex covers
            // most words and the seeded SentenceDao handles common
            // beginner words. Empty Sentence field just collapses the
            // whole back-side block via the {{#Sentence}} mustache.
            // The front-context sentence needs a sentence just as much as the
            // back-side block does, so the local lookup runs when EITHER
            // option is on — otherwise turning the back-side sentences off
            // silently emptied the front of the card too.
            val needsSentence = stylePrefs.showSentence || stylePrefs.showFrontContextSentence
            val wordForExport = if (needsSentence) {
                fillSentenceForExport(word, stylePrefs)
            } else {
                word
            }
                .let { enrichExamplesWithFurigana(it) }
                // Card engine last: it swaps the meaning for a monolingual
                // definition and strips the sentence translations, so it has to
                // see the sentences the previous steps attached. A no-op when
                // the engine is left on JP-EN.
                .let { monolingualCardResolver.apply(it) }

            // Fetch kanji information
            val kanjiChars = wordForExport.expression.filter { com.yomitanmobile.domain.model.MergedWordEntry.isKanji(it) }.map { it.toString() }.distinct()
            val kanjiData = if (kanjiChars.isNotEmpty()) repository.getKanjis(kanjiChars) else emptyList()

            // AI summary is opt-in PER EXPORT — the caller chooses with the
            // [includeAiSummary] flag (two distinct buttons in the detail
            // screen). The CARD_AI_SUMMARY_ENABLED preference no longer
            // gates this; only the API key is still required. Failures
            // don't block the export — we just emit the card without a
            // summary section. The user-language flag drives the
            // {language} placeholder so Polish users get Polish summaries
            // by default.
            val aiSummaryText = if (
                includeAiSummary &&
                stylePrefs.aiApiKey.isNotBlank()
            ) {
                val isEnglish = LocaleHelper.isEnglish(appContext.resources.configuration)
                val language = if (isEnglish) "English" else "Polish"
                val result = aiSummaryService.generateSummary(
                    provider = stylePrefs.aiProvider,
                    apiKey = stylePrefs.aiApiKey,
                    promptTemplate = stylePrefs.aiPrompt,
                    word = wordForExport.expression,
                    reading = wordForExport.reading,
                    meanings = wordForExport.definitions,
                    language = language,
                    modelOverride = stylePrefs.aiModel
                )
                when (result) {
                    is AiSummaryResult.Success -> result.text
                    is AiSummaryResult.Failure -> {
                        Log.w(logTag, "AI summary failed: ${result.message}")
                        // Park the export on the gate; the UI shows a
                        // dialog and calls resolveAiFailure() with the
                        // user's choice. CANCEL_EXPORT bails out before
                        // touching AnkiDroid so no card is created;
                        // CONTINUE_WITHOUT_AI lands the card with an
                        // empty summary slot.
                        _events.emit(DetailEvent.AiSummaryFailedNeedsChoice(result.message))
                        when (aiFailureGate.awaitDecision()) {
                            AiFailureChoice.CONTINUE_WITHOUT_AI -> ""
                            AiFailureChoice.CANCEL_EXPORT -> {
                                _events.emit(DetailEvent.AnkiExportCancelled)
                                return
                            }
                        }
                    }
                    AiSummaryResult.Disabled -> ""
                }
            } else ""

            val result = ankiCardCreator.exportToAnki(
                entry = wordForExport,
                kanjiData = kanjiData,
                tts = audioPlayer.getTts(),
                deckName = deckName,
                stylePrefs = stylePrefs,
                aiSummaryText = aiSummaryText
            )
            result.fold(
                onSuccess = { noteId ->
                    val safeExpression = normalizeExpression(wordForExport.expression, wordForExport.reading)
                    val safeReading = normalizeReading(safeExpression, wordForExport.reading)

                    // Record export metadata in local DB if available, but keep Anki export successful
                    // even when local schema is stale.
                    runCatching {
                        val exportedAt = Instant.now().toEpochMilli()
                        val localHour = LocalDateTime.ofInstant(
                            Instant.ofEpochMilli(exportedAt),
                            ZoneId.systemDefault()
                        ).hour
                        val classifierInput = wordForExport.copy(
                            expression = safeExpression,
                            reading = safeReading
                        )
                        // classifyAll() can return up to N categories; the
                        // first is the primary scorer (matches the legacy
                        // `exportCategory` value). We store both so old
                        // queries against `export_category` keep working
                        // and new queries can roll up multi-label.
                        val allCategories = runCatching {
                            WordCategoryClassifier.classifyAll(classifierInput)
                        }.getOrDefault(listOf(WordCategoryClassifier.CATEGORY_OTHER))
                        val primaryCategory = allCategories.firstOrNull()
                            ?: WordCategoryClassifier.CATEGORY_OTHER

                        exportedWordDao.insert(
                            ExportedWord(
                                expression = safeExpression,
                                reading = safeReading,
                                deckName = deckName,
                                ankiNoteId = noteId,
                                exportDate = exportedAt,
                                exportHour = localHour,
                                exportCategory = primaryCategory,
                                exportCategories = allCategories.joinToString(",")
                            )
                        )
                    }.onFailure { exception ->
                        Log.e(logTag, "Failed to persist export metadata", exception)
                    }

                    _events.emit(DetailEvent.AnkiExportSuccess(noteId))
                },
                onFailure = { error ->
                    val message = error.message?.trim().orEmpty()
                    if (message.contains("duplicate", ignoreCase = true)) {
                        val safeExpression = normalizeExpression(wordForExport.expression, wordForExport.reading)
                        _events.emit(DetailEvent.AlreadyExported(safeExpression, deckName))
                    } else {
                        _events.emit(DetailEvent.AnkiExportError(message.ifBlank { "Unknown error" }))
                    }
                }
            )
        } catch (exception: Exception) {
            Log.e(logTag, "Export flow failed", exception)
            _events.emit(DetailEvent.AnkiExportError(exception.message ?: "Unknown error"))
        } finally {
            _isExporting.value = false
        }
    }

    override fun onCleared() {
        super.onCleared()
        audioPlayer.stopPlayback()
    }
}
