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
import com.yomitanmobile.data.audio.AudioPlayer
import com.yomitanmobile.data.local.dao.ExportedWordDao
import com.yomitanmobile.data.local.dao.FavoriteWordDao
import com.yomitanmobile.data.local.dao.SentenceDao
import com.yomitanmobile.data.local.entity.ExportedWord
import com.yomitanmobile.data.local.entity.FavoriteWord
import com.yomitanmobile.dataStore
import com.yomitanmobile.data.sentence.OnlineSentenceService
import com.yomitanmobile.domain.model.CardStylePreferences
import com.yomitanmobile.domain.model.MergedWordEntry
import com.yomitanmobile.domain.model.PitchAccentStyle
import com.yomitanmobile.domain.model.WordEntry
import com.yomitanmobile.domain.repository.DictionaryRepository
import com.yomitanmobile.domain.usecase.GetWordDetailUseCase
import com.yomitanmobile.util.InputSanitizer
import com.yomitanmobile.util.JlptVocabulary
import com.yomitanmobile.util.LocaleHelper
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
     * AI summary call failed but the rest of the export succeeded. We
     * surface the message so the user can see WHY the summary slot is
     * empty (rate limit, bad key, network error) instead of silently
     * landing a partial card.
     */
    data class AiSummaryFailed(val message: String) : DetailEvent()
}

@HiltViewModel
class DetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val getWordDetailUseCase: GetWordDetailUseCase,
    private val repository: DictionaryRepository,
    private val ankiCardCreator: AnkiCardCreator,
    private val audioPlayer: AudioPlayer,
    private val sentenceDao: SentenceDao,
    private val onlineSentenceService: OnlineSentenceService,
    private val aiSummaryService: AiSummaryService,
    private val exportedWordDao: ExportedWordDao,
    private val favoriteWordDao: FavoriteWordDao,
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

    fun exportToAnki() {
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

                performExport(word, sanitizedDeck)
            } catch (exception: Exception) {
                Log.e(logTag, "Export pre-check failed", exception)
                _events.emit(DetailEvent.AnkiExportError(exception.message ?: "Unknown error"))
            }
        }
    }

    fun forceExport() {
        val merged = _entry.value ?: return
        val word = merged.toWordEntry()
        viewModelScope.launch {
            try {
                val savedDeckRaw = appContext.dataStore.data
                    .map { it[MainActivity.ANKI_DECK_NAME] }
                    .first() ?: "Mining Deck"
                val savedDeck = InputSanitizer.sanitizeDeckName(savedDeckRaw)
                performExport(word, savedDeck)
            } catch (exception: Exception) {
                Log.e(logTag, "Force export failed", exception)
                _events.emit(DetailEvent.AnkiExportError(exception.message ?: "Unknown error"))
            }
        }
    }

    fun exportToAnkiWithDeck(deckName: String) {
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

                performExport(word, sanitizedDeck)
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

    private suspend fun loadCardStylePreferences(): CardStylePreferences {
        val prefs = appContext.dataStore.data.first()
        return CardStylePreferences(
            expressionBold = prefs[MainActivity.CARD_EXPRESSION_BOLD] ?: true,
            expressionFontSize = prefs[MainActivity.CARD_EXPRESSION_FONT_SIZE] ?: 48,
            readingFontSize = prefs[MainActivity.CARD_READING_FONT_SIZE] ?: 28,
            meaningFontSize = prefs[MainActivity.CARD_MEANING_FONT_SIZE] ?: 20,
            frontContextSentenceFontSize = prefs[MainActivity.CARD_FRONT_CONTEXT_SENTENCE_FONT_SIZE] ?: 14,
            backSentenceFontSize = prefs[MainActivity.CARD_BACK_SENTENCE_FONT_SIZE] ?: 14,
            fontFamily = prefs[MainActivity.CARD_FONT_FAMILY] ?: "Hiragino Sans",
            cardBackgroundColor = prefs[MainActivity.CARD_BACKGROUND_COLOR] ?: "#1a1a1a",
            expressionColor = prefs[MainActivity.CARD_EXPRESSION_COLOR] ?: "#ffffff",
            readingColor = prefs[MainActivity.CARD_READING_COLOR] ?: "#80cbc4",
            meaningColor = prefs[MainActivity.CARD_MEANING_COLOR] ?: "#e0e0e0",
            accentColor = prefs[MainActivity.CARD_ACCENT_COLOR] ?: "#80cbc4",
            showPitchAccent = prefs[MainActivity.CARD_SHOW_PITCH] ?: true,
            pitchAccentStyle = PitchAccentStyle.fromStorage(
                prefs[MainActivity.CARD_PITCH_ACCENT_STYLE]
            ),
            showFrequency = prefs[MainActivity.CARD_SHOW_FREQUENCY] ?: true,
            showSentence = prefs[MainActivity.CARD_SHOW_SENTENCE] ?: true,
            showFrontContextSentence = prefs[MainActivity.CARD_SHOW_FRONT_CONTEXT_SENTENCE] ?: false,
            randomFontsEnabled = prefs[MainActivity.CARD_RANDOM_FONTS_ENABLED] ?: false,
            randomFonts = prefs[MainActivity.CARD_RANDOM_FONTS] ?: emptySet(),
            randomVoicesEnabled = prefs[MainActivity.TTS_RANDOM_VOICES_ENABLED] ?: false,
            randomVoices = prefs[MainActivity.TTS_RANDOM_VOICES] ?: emptySet(),
            useOnlineSentenceApi = prefs[MainActivity.CARD_USE_ONLINE_SENTENCE_API] ?: false,
            showSectionDividers = prefs[MainActivity.CARD_SHOW_SECTION_DIVIDERS] ?: true,
            aiSummaryEnabled = prefs[MainActivity.CARD_AI_SUMMARY_ENABLED] ?: false,
            aiProvider = com.yomitanmobile.data.ai.AiProvider.fromStorage(
                prefs[MainActivity.CARD_AI_PROVIDER]
            ),
            aiApiKey = prefs[MainActivity.CARD_AI_API_KEY] ?: "",
            aiPrompt = prefs[MainActivity.CARD_AI_PROMPT]
                ?: com.yomitanmobile.data.ai.AI_DEFAULT_PROMPT,
            aiModel = prefs[MainActivity.CARD_AI_MODEL] ?: ""
        )
    }

    private suspend fun performExport(word: WordEntry, deckName: String) {
        _isExporting.value = true
        try {
            val stylePrefs = loadCardStylePreferences()

            // Sentence-source priority order:
            //   1. Jitendex examples already attached to the entry (preferred)
            //   2. Pre-seeded local SentenceDao matches
            //   3. Online Tatoeba API (only with explicit user consent)
            val wordForExport = if (stylePrefs.showSentence) {
                val lookup = word.expression.ifBlank { word.reading }
                if (word.examples.isNotEmpty()) {
                    // Already populated from the dictionary import — leave as-is.
                    // AnkiCardCreator consumes word.examples directly.
                    word
                } else {
                    val localSentences = sentenceDao.getSentencesByExpressionOrReading(
                        expression = lookup,
                        reading = word.reading.ifBlank { lookup }
                    )
                    if (localSentences.isNotEmpty()) {
                        val bestSentence = localSentences.first()
                        word.copy(
                            exampleSentence = bestSentence.sentenceJapanese,
                            exampleSentenceTranslation = bestSentence.sentenceEnglish
                        )
                    } else if (stylePrefs.useOnlineSentenceApi) {
                        val onlineSentence = runCatching {
                            onlineSentenceService.fetchSentenceForWord(lookup)
                        }.getOrNull()
                        if (onlineSentence != null) {
                            word.copy(
                                exampleSentence = onlineSentence.japanese,
                                exampleSentenceTranslation = onlineSentence.translation
                            )
                        } else {
                            word
                        }
                    } else {
                        word
                    }
                }
            } else {
                word
            }
            
            // Fetch kanji information
            val kanjiChars = wordForExport.expression.filter { com.yomitanmobile.domain.model.MergedWordEntry.isKanji(it) }.map { it.toString() }.distinct()
            val kanjiData = if (kanjiChars.isNotEmpty()) repository.getKanjis(kanjiChars) else emptyList()

            // AI summary is opt-in (CARD_AI_SUMMARY_ENABLED) and requires
            // a user-supplied API key. Failures don't block the export —
            // we just emit the card without a summary section. The
            // user-language flag drives the {language} placeholder so
            // Polish users get Polish summaries by default.
            val aiSummaryText = if (
                stylePrefs.aiSummaryEnabled &&
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
                        _events.emit(DetailEvent.AiSummaryFailed(result.message))
                        ""
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
                        val exportCategory = runCatching {
                            WordCategoryClassifier.classify(
                                wordForExport.copy(
                                    expression = safeExpression,
                                    reading = safeReading
                                )
                            )
                        }.getOrDefault(WordCategoryClassifier.CATEGORY_OTHER)

                        exportedWordDao.insert(
                            ExportedWord(
                                expression = safeExpression,
                                reading = safeReading,
                                deckName = deckName,
                                ankiNoteId = noteId,
                                exportDate = exportedAt,
                                exportHour = localHour,
                                exportCategory = exportCategory
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
