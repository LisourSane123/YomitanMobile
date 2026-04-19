package com.yomitanmobile.ui.detail

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yomitanmobile.MainActivity
import com.yomitanmobile.data.anki.AnkiCardCreator
import com.yomitanmobile.data.audio.AudioPlayer
import com.yomitanmobile.data.local.dao.ExportedWordDao
import com.yomitanmobile.data.local.dao.FavoriteWordDao
import com.yomitanmobile.data.local.entity.ExportedWord
import com.yomitanmobile.data.local.entity.FavoriteWord
import com.yomitanmobile.data.sentence.OnlineSentenceService
import com.yomitanmobile.dataStore
import com.yomitanmobile.domain.model.CardStylePreferences
import com.yomitanmobile.domain.model.MergedWordEntry
import com.yomitanmobile.domain.model.WordEntry
import com.yomitanmobile.domain.repository.DictionaryRepository
import com.yomitanmobile.domain.usecase.GetWordDetailUseCase
import com.yomitanmobile.util.InputSanitizer
import com.yomitanmobile.util.JlptLevelUtil
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
import java.util.Calendar
import javax.inject.Inject

sealed class DetailEvent {
    data class AnkiExportSuccess(val noteId: Long) : DetailEvent()
    data class AnkiExportError(val message: String) : DetailEvent()
    object AnkiPermissionRequired : DetailEvent()
    object AnkiNotInstalled : DetailEvent()
    data class AnkiDeckSelectionRequired(val decks: List<String>) : DetailEvent()
    data class AlreadyExported(val expression: String, val deckName: String) : DetailEvent()
}

enum class CardQualityTier {
    EXCELLENT,
    GOOD,
    FAIR,
    WEAK
}

data class CardQualityScore(
    val score: Int,
    val tier: CardQualityTier,
    val reasons: List<String>
)

@HiltViewModel
class DetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val getWordDetailUseCase: GetWordDetailUseCase,
    private val repository: DictionaryRepository,
    private val ankiCardCreator: AnkiCardCreator,
    private val audioPlayer: AudioPlayer,
    private val onlineSentenceService: OnlineSentenceService,
    private val exportedWordDao: ExportedWordDao,
    private val favoriteWordDao: FavoriteWordDao,
    @ApplicationContext private val appContext: Context
) : ViewModel() {

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

    private val _cardQualityScore = MutableStateFlow<CardQualityScore?>(null)
    val cardQualityScore: StateFlow<CardQualityScore?> = _cardQualityScore.asStateFlow()

    init {
        loadEntry()
        audioPlayer.initTts()
    }

    private fun loadEntry() {
        viewModelScope.launch {
            _isLoading.value = true
            val word = getWordDetailUseCase.invoke(entryId)
            _entry.value = word
                ?.let { MergedWordEntry.mergeEntries(listOf(it)).firstOrNull() }
            _isLoading.value = false
            checkFavoriteStatus()
            refreshCardQualityScore()
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

            // Check if already exported
            val existing = exportedWordDao.findExported(
                word.expression, word.reading, sanitizedDeck
            )
            if (existing != null) {
                _events.emit(DetailEvent.AlreadyExported(word.expression, sanitizedDeck))
                return@launch
            }

            performExport(word, sanitizedDeck)
        }
    }

    fun forceExport() {
        val merged = _entry.value ?: return
        val word = merged.toWordEntry()
        viewModelScope.launch {
            val savedDeckRaw = appContext.dataStore.data
                .map { it[MainActivity.ANKI_DECK_NAME] }
                .first() ?: "Mining Deck"
            val savedDeck = InputSanitizer.sanitizeDeckName(savedDeckRaw)
            performExport(word, savedDeck)
        }
    }

    fun exportToAnkiWithDeck(deckName: String) {
        val merged = _entry.value ?: return
        val word = merged.toWordEntry()
        val sanitizedDeck = InputSanitizer.sanitizeDeckName(deckName)
        viewModelScope.launch {
            appContext.dataStore.edit { prefs ->
                prefs[MainActivity.ANKI_DECK_NAME] = sanitizedDeck
            }

            // Check if already exported to this deck
            val existing = exportedWordDao.findExported(
                word.expression, word.reading, sanitizedDeck
            )
            if (existing != null) {
                _events.emit(DetailEvent.AlreadyExported(word.expression, sanitizedDeck))
                return@launch
            }

            performExport(word, sanitizedDeck)
        }
    }

    private suspend fun loadCardStylePreferences(): CardStylePreferences {
        val prefs = appContext.dataStore.data.first()
        return CardStylePreferences(
            expressionBold = prefs[MainActivity.CARD_EXPRESSION_BOLD] ?: true,
            expressionFontSize = prefs[MainActivity.CARD_EXPRESSION_FONT_SIZE] ?: 48,
            readingFontSize = prefs[MainActivity.CARD_READING_FONT_SIZE] ?: 28,
            meaningFontSize = prefs[MainActivity.CARD_MEANING_FONT_SIZE] ?: 20,
            fontFamily = prefs[MainActivity.CARD_FONT_FAMILY] ?: "Hiragino Sans",
            cardBackgroundColor = prefs[MainActivity.CARD_BACKGROUND_COLOR] ?: "#1a1a1a",
            expressionColor = prefs[MainActivity.CARD_EXPRESSION_COLOR] ?: "#ffffff",
            readingColor = prefs[MainActivity.CARD_READING_COLOR] ?: "#80cbc4",
            meaningColor = prefs[MainActivity.CARD_MEANING_COLOR] ?: "#e0e0e0",
            accentColor = prefs[MainActivity.CARD_ACCENT_COLOR] ?: "#80cbc4",
            showPitchAccent = prefs[MainActivity.CARD_SHOW_PITCH] ?: true,
            showFrequency = prefs[MainActivity.CARD_SHOW_FREQUENCY] ?: true,
            showSentence = prefs[MainActivity.CARD_SHOW_SENTENCE] ?: true,
            showFrontContextSentence = prefs[MainActivity.CARD_SHOW_FRONT_CONTEXT_SENTENCE] ?: false,
            randomFontsEnabled = prefs[MainActivity.CARD_RANDOM_FONTS_ENABLED] ?: false,
            randomFonts = prefs[MainActivity.CARD_RANDOM_FONTS] ?: emptySet(),
            randomVoicesEnabled = prefs[MainActivity.TTS_RANDOM_VOICES_ENABLED] ?: false,
            randomVoices = prefs[MainActivity.TTS_RANDOM_VOICES] ?: emptySet(),
            useOnlineSentenceApi = prefs[MainActivity.CARD_USE_ONLINE_SENTENCE_API] ?: false,
            onlineSentenceApiConsentGranted = prefs[MainActivity.SENTENCE_API_CONSENT_GRANTED] ?: false
        )
    }

    private suspend fun performExport(word: WordEntry, deckName: String) {
        _isExporting.value = true
        try {
            val stylePrefs = loadCardStylePreferences()

            val wordForExport = if (stylePrefs.useOnlineSentenceApi && stylePrefs.onlineSentenceApiConsentGranted) {
                val lookup = word.expression.ifBlank { word.reading }
                val onlineSentence = onlineSentenceService.fetchSentenceForWord(lookup)
                if (onlineSentence != null && onlineSentence.japanese.isNotBlank()) {
                    word.copy(
                        exampleSentence = onlineSentence.japanese,
                        exampleSentenceTranslation = onlineSentence.translation.ifBlank { word.exampleSentenceTranslation }
                    )
                } else {
                    word
                }
            } else {
                word
            }
            
            // Fetch kanji information
            val kanjiChars = wordForExport.expression.filter { com.yomitanmobile.domain.model.MergedWordEntry.isKanji(it) }.map { it.toString() }.distinct()
            val kanjiData = if (kanjiChars.isNotEmpty()) repository.getKanjis(kanjiChars) else emptyList()

            val result = ankiCardCreator.exportToAnki(
                entry = wordForExport,
                kanjiData = kanjiData,
                tts = audioPlayer.getTts(),
                deckName = deckName,
                stylePrefs = stylePrefs
            )
            result.fold(
                onSuccess = { noteId ->
                    // Record the export
                    val exportedAt = System.currentTimeMillis()
                    val localHour = Calendar.getInstance().apply {
                        timeInMillis = exportedAt
                    }.get(Calendar.HOUR_OF_DAY)
                    val exportCategory = WordCategoryClassifier.classify(wordForExport)
                    exportedWordDao.insert(
                        ExportedWord(
                            expression = wordForExport.expression,
                            reading = wordForExport.reading,
                            deckName = deckName,
                            ankiNoteId = noteId,
                            exportDate = exportedAt,
                            exportHour = localHour,
                            exportCategory = exportCategory
                        )
                    )
                    refreshCardQualityScore()
                    _events.emit(DetailEvent.AnkiExportSuccess(noteId))
                },
                onFailure = { error ->
                    _events.emit(DetailEvent.AnkiExportError(error.message ?: "Unknown error"))
                }
            )
        } finally {
            _isExporting.value = false
        }
    }

    private suspend fun refreshCardQualityScore() {
        val merged = _entry.value
        if (merged == null) {
            _cardQualityScore.value = null
            return
        }
        _cardQualityScore.value = computeCardQuality(merged)
    }

    private suspend fun computeCardQuality(entry: MergedWordEntry): CardQualityScore {
        var score = 0
        val reasons = mutableListOf<String>()

        // Frequency quality (more common words are usually better early mining targets)
        when {
            entry.frequency in 1..3_000 -> {
                score += 35
                reasons += "Bardzo częste słowo"
            }
            entry.frequency in 3_001..10_000 -> {
                score += 28
                reasons += "Częste słowo"
            }
            entry.frequency in 10_001..30_000 -> {
                score += 18
                reasons += "Średnia częstotliwość"
            }
            entry.frequency > 0 -> {
                score += 10
                reasons += "Rzadsze słowo"
            }
            else -> reasons += "Brak danych o częstotliwości"
        }

        if (entry.exampleSentence.isNotBlank()) {
            score += 20
            reasons += "Ma przykładowe zdanie"
        } else {
            reasons += "Brak przykładowego zdania"
        }

        if (entry.exampleSentenceTranslation.isNotBlank()) {
            score += 5
        }

        when {
            entry.definitions.size <= 2 -> {
                score += 12
                reasons += "Kompaktowe znaczenie"
            }
            entry.definitions.size <= 5 -> {
                score += 8
            }
            else -> {
                score += 2
                reasons += "Wiele znaczeń"
            }
        }

        if (entry.pitchAccent.isNotBlank()) {
            score += 8
            reasons += "Zawiera pitch accent"
        }

        if (entry.partsOfSpeech.isNotEmpty()) {
            score += 5
        }

        when (entry.primaryExpression.length) {
            in 1..6 -> score += 10
            in 7..10 -> score += 5
            else -> score -= 3
        }

        val jlpt = JlptLevelUtil.getLevel(entry.primaryExpression, entry.frequency)
        if (jlpt != null) {
            score += when (jlpt) {
                JlptLevelUtil.JlptLevel.N5 -> 12
                JlptLevelUtil.JlptLevel.N4 -> 12
                JlptLevelUtil.JlptLevel.N3 -> 10
                JlptLevelUtil.JlptLevel.N2 -> 8
                JlptLevelUtil.JlptLevel.N1 -> 6
            }
            reasons += "Poziom ${jlpt.label}"
        }

        val normalizedReading = entry.reading.ifBlank { entry.primaryExpression }
        val exportsCount = exportedWordDao.countExportsForWord(entry.primaryExpression, normalizedReading)
        if (exportsCount > 0) {
            score -= minOf(20, exportsCount * 6)
            reasons += "Już eksportowane ${exportsCount}×"
        }

        score = score.coerceIn(0, 100)
        val tier = when {
            score >= 80 -> CardQualityTier.EXCELLENT
            score >= 60 -> CardQualityTier.GOOD
            score >= 40 -> CardQualityTier.FAIR
            else -> CardQualityTier.WEAK
        }

        return CardQualityScore(
            score = score,
            tier = tier,
            reasons = reasons.distinct().take(6)
        )
    }

    override fun onCleared() {
        super.onCleared()
        audioPlayer.stopPlayback()
    }
}
