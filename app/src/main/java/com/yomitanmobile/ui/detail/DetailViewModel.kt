package com.yomitanmobile.ui.detail

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yomitanmobile.MainActivity
import com.yomitanmobile.data.anki.AnkiCardCreator
import com.yomitanmobile.data.audio.AudioPlayer
import com.yomitanmobile.data.local.dao.ExportedWordDao
import com.yomitanmobile.data.local.entity.ExportedWord
import com.yomitanmobile.dataStore
import com.yomitanmobile.domain.model.WordEntry
import com.yomitanmobile.domain.usecase.GetWordDetailUseCase
import com.yomitanmobile.util.InputSanitizer
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
import javax.inject.Inject

sealed class DetailEvent {
    data class AnkiExportSuccess(val noteId: Long) : DetailEvent()
    data class AnkiExportError(val message: String) : DetailEvent()
    object AnkiPermissionRequired : DetailEvent()
    object AnkiNotInstalled : DetailEvent()
    data class AnkiDeckSelectionRequired(val decks: List<String>) : DetailEvent()
    data class AlreadyExported(val expression: String, val deckName: String) : DetailEvent()
}

@HiltViewModel
class DetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val getWordDetailUseCase: GetWordDetailUseCase,
    private val ankiCardCreator: AnkiCardCreator,
    private val audioPlayer: AudioPlayer,
    private val exportedWordDao: ExportedWordDao,
    @ApplicationContext private val appContext: Context
) : ViewModel() {

    private val entryId: Long = savedStateHandle.get<Long>("entryId") ?: 0L

    private val _entry = MutableStateFlow<WordEntry?>(null)
    val entry: StateFlow<WordEntry?> = _entry.asStateFlow()

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _isExporting = MutableStateFlow(false)
    val isExporting: StateFlow<Boolean> = _isExporting.asStateFlow()

    private val _events = MutableSharedFlow<DetailEvent>()
    val events: SharedFlow<DetailEvent> = _events.asSharedFlow()

    val isPlaying: StateFlow<Boolean> = audioPlayer.isPlaying
    val ttsReady: StateFlow<Boolean> = audioPlayer.ttsReady

    init {
        loadEntry()
        audioPlayer.initTts()
    }

    private fun loadEntry() {
        viewModelScope.launch {
            _isLoading.value = true
            val word = getWordDetailUseCase.invoke(entryId)
            _entry.value = word
            _isLoading.value = false
        }
    }

    fun playAudio() {
        val word = _entry.value ?: return
        val textToSpeak = word.reading.ifBlank { word.expression }
        audioPlayer.playWord(textToSpeak, word.audioFile.takeIf { it.isNotBlank() })
    }

    fun stopAudio() {
        audioPlayer.stopPlayback()
    }

    fun exportToAnki() {
        val word = _entry.value ?: return
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

            // Check if already exported
            val existing = exportedWordDao.findExported(
                word.expression, word.reading, savedDeck
            )
            if (existing != null) {
                _events.emit(DetailEvent.AlreadyExported(word.expression, savedDeck))
                return@launch
            }

            performExport(word, savedDeck)
        }
    }

    fun forceExport() {
        val word = _entry.value ?: return
        viewModelScope.launch {
            val savedDeck = appContext.dataStore.data
                .map { it[MainActivity.ANKI_DECK_NAME] }
                .first() ?: "Mining Deck"
            performExport(word, savedDeck)
        }
    }

    fun exportToAnkiWithDeck(deckName: String) {
        val word = _entry.value ?: return
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

    private suspend fun performExport(word: WordEntry, deckName: String) {
        _isExporting.value = true
        try {
            val result = ankiCardCreator.exportToAnki(
                entry = word,
                tts = audioPlayer.getTts(),
                deckName = deckName
            )
            result.fold(
                onSuccess = { noteId ->
                    // Record the export
                    exportedWordDao.insert(
                        ExportedWord(
                            expression = word.expression,
                            reading = word.reading,
                            deckName = deckName,
                            ankiNoteId = noteId
                        )
                    )
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

    override fun onCleared() {
        super.onCleared()
        audioPlayer.stopPlayback()
    }
}
