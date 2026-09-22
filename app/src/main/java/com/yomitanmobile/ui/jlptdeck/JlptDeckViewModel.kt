package com.yomitanmobile.ui.jlptdeck

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yomitanmobile.data.anki.AnkiCardCreator
import com.yomitanmobile.data.anki.AnkiCollectionIndex
import com.yomitanmobile.data.anki.AnkiCollectionStore
import com.yomitanmobile.data.anki.MonolingualCardResolver
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
import kotlinx.coroutines.channels.BufferOverflow
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
    /** Audio was requested but the device has no usable TTS voice. */
    object AudioUnavailable : JlptDeckEvent()
    /**
     * Cards were marked as known, and AnkiDroid's provider has no way to
     * suspend them — they carry [tag] instead. Not an error: the deck was
     * written, and the user is told the one action that finishes the job.
     */
    data class SuspendNeedsAnki(val tag: String, val count: Int) : JlptDeckEvent()
    /** An `.apkg` was written: [notes] cards, [suspended] of them suspended. */
    data class FileWritten(val notes: Int, val suspended: Int) : JlptDeckEvent()
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
    private val ankiCollectionStore: AnkiCollectionStore,
    private val monolingualCardResolver: MonolingualCardResolver,
    private val exportedWordDao: ExportedWordDao,
    private val jlptTagDao: JlptTagDao,
    private val audioPlayer: AudioPlayer,
    private val voicevox: com.yomitanmobile.data.audio.voicevox.VoicevoxVoice,
    private val apkgWriter: com.yomitanmobile.data.anki.ApkgWriter,
    languageSettings: com.yomitanmobile.data.settings.LanguageSettings,
    @ApplicationContext private val appContext: Context
) : ViewModel() {

    private val logTag = "JlptDeckViewModel"

    /**
     * The level scale of the language being studied: JLPT for Japanese, CEFR
     * for English. Everything that names a level — the chips, the deck name,
     * the tags on the cards — goes through it.
     */
    private val language = languageSettings.current
    val scale: com.yomitanmobile.domain.model.LevelScale =
        com.yomitanmobile.domain.model.LevelScale.forLanguage(language)
            ?: com.yomitanmobile.domain.model.LevelScale.JLPT

    /** Note-type label the scan screen shows for generated words. */
    private val generatedSource = "Yomitan Mobile (${scale.displayName})"

    private val _level = MutableStateFlow(scale.levels.first())
    val level: StateFlow<Int> = _level.asStateFlow()

    private val _filters = MutableStateFlow(JlptDeckFilters())
    val filters: StateFlow<JlptDeckFilters> = _filters.asStateFlow()

    private val _deckName = MutableStateFlow(scale.deckName(scale.levels.first()))
    val deckName: StateFlow<String> = _deckName.asStateFlow()

    private val _isAnalyzing = MutableStateFlow(false)
    val isAnalyzing: StateFlow<Boolean> = _isAnalyzing.asStateFlow()

    private val _plan = MutableStateFlow<JlptDeckPlan?>(null)
    val plan: StateFlow<JlptDeckPlan?> = _plan.asStateFlow()

    private val _progress = MutableStateFlow<JlptDeckProgress?>(null)
    val progress: StateFlow<JlptDeckProgress?> = _progress.asStateFlow()

    /**
     * Words the user marked as already known while reviewing the plan.
     *
     * They are still created — the deck stays complete and one unsuspend
     * brings a word back — but they arrive suspended, so studying does not
     * begin with a hundred cards the reader already has. Frequency-first
     * ordering makes that the default shape of a generated deck, and
     * `assumeKnownTopRank` can only cut it off by a number.
     *
     * Keyed by expression + reading (see `previewKeyOf`), so a re-analysis
     * that produces the same word keeps the mark.
     */
    /**
     * Whether the generated words are taught to the stored collection scan.
     *
     * Normally yes: generated cards stay out of `exported_words` on purpose,
     * so the stored scan is the ONLY record that they exist, and without it a
     * second run offers the very words it just created.
     *
     * Off is for building a deck you do not yet trust. The scan is the app's
     * memory of the collection, and teaching it about a deck that turns out
     * to be wrong means every later run skips those words — with nothing to
     * undo it but a full rescan. Leaving it off keeps the memory untouched
     * until the user rescans, which is the honest source anyway.
     */
    private val _recordAsKnown = MutableStateFlow(true)
    val recordAsKnown: StateFlow<Boolean> = _recordAsKnown.asStateFlow()

    fun setRecordAsKnown(value: Boolean) {
        _recordAsKnown.value = value
    }

    private val _suspendedKeys = MutableStateFlow<Set<String>>(emptySet())
    val suspendedKeys: StateFlow<Set<String>> = _suspendedKeys.asStateFlow()

    fun toggleSuspended(key: String) {
        _suspendedKeys.value = _suspendedKeys.value.let {
            if (key in it) it - key else it + key
        }
    }

    fun suspendAll() {
        _suspendedKeys.value = _plan.value?.selected
            ?.map { com.yomitanmobile.ui.common.previewKeyOf(it) }
            ?.toSet()
            .orEmpty()
    }

    fun suspendFirst(count: Int) {
        _suspendedKeys.value = _suspendedKeys.value + (
            _plan.value?.selected.orEmpty()
                .take(count)
                .map { com.yomitanmobile.ui.common.previewKeyOf(it) }
            )
    }

    /**
     * Marks or unmarks a whole batch in one edit.
     *
     * The review's bulk buttons act on what its search has narrowed to, which
     * is the only way a deck of thousands is reviewed in one sitting: "suspend
     * everything matching 見" has to be one decision, not forty taps. Toggling
     * each key in turn would also publish a new set forty times over and
     * recompose the list on each of them.
     */
    fun setSuspended(keys: Collection<String>, suspended: Boolean) {
        if (keys.isEmpty()) return
        _suspendedKeys.value =
            if (suspended) _suspendedKeys.value + keys else _suspendedKeys.value - keys.toSet()
    }

    fun clearSuspended() {
        _suspendedKeys.value = emptySet()
    }

    private val _availableDecks = MutableStateFlow<List<String>>(emptyList())
    val availableDecks: StateFlow<List<String>> = _availableDecks.asStateFlow()

    // Buffered, never suspending. A default MutableSharedFlow is a rendezvous
    // channel: emit() waits for a collector, and the screen's collector only
    // exists while the screen is composed. Navigating away from a retained
    // ViewModel mid-operation parked the emitting coroutine forever, so the
    // finally block that clears the progress / "is exporting" flag never ran
    // and the screen came back stuck.
    private val _events = MutableSharedFlow<JlptDeckEvent>(
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
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
            _taggedWordCount.value = runCatching { jlptTagDao.countForLevel(level, language.entryTag) }
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
        if (_deckName.value == scale.deckName(_level.value) || _deckName.value.isBlank() ||
            scale.levels.any { _deckName.value == scale.deckName(it) }
        ) {
            _deckName.value = scale.deckName(level)
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

                // Rescan first. The stored copy is only as current as the
                // last time someone ran the scan screen, and a deck generated
                // against a stale one recreates every card added since — from
                // the phone's mining, the Kindle tool, another device. A
                // generator writes into the collection, so it has the
                // permission a scan needs, and a few seconds is nothing next
                // to a deck of duplicates. A scan AnkiDroid refuses keeps the
                // previous result, and an empty store still degrades to "not
                // checked", which the plan reports.
                val index = if (filters.skipAlreadyInAnki) {
                    ankiCollectionStore.refresh()
                    ankiCollectionStore.asIndex()
                } else {
                    AnkiCollectionIndex.Index.EMPTY
                }
                val storedScan = ankiCollectionStore.storedScanInfo()

                // One query for the whole candidate set, on the indexed
                // reading column — not one per word.
                val writtenForms = if (filters.skipAlreadyInAnki) {
                    repository.writtenFormsBySequence(candidates.map { it.reading })
                } else {
                    emptyMap()
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
                    isInAnki = {
                        index.containsAny(
                            // Every spelling of the word, not just the
                            // headword the dictionary happens to file it
                            // under. The deck holds whichever one its author
                            // typed — 傷付く where JMdict's primary is 傷つく,
                            // 奇麗 where it is 綺麗 — and the headword-to-
                            // headword comparison this replaces reported "not
                            // in your collection" for words already being
                            // studied. alternativeExpressions is kept in the
                            // list because TextScanPlanner's `frontedWith`
                            // puts the original headword there; it is empty
                            // otherwise, which is the whole reason the
                            // spellings are read from the database.
                            listOf(it.primaryExpression) +
                                it.alternativeExpressions +
                                writtenForms[it.sequenceNumber].orEmpty(),
                            it.reading,
                            readingCountsAlone = com.yomitanmobile.domain.usecase.WordFilterRules
                                .isUsuallyKana(it)
                        )
                    },
                    isMined = { matchKey(it.primaryExpression, it.reading) in minedKeys },
                    ankiScanUnavailable = filters.skipAlreadyInAnki && !index.available,
                    scannedWordCount = storedScan.wordCount,
                    scannedAt = storedScan.scannedAt
                )
            } catch (e: Exception) {
                Log.e(logTag, "Analyze failed", e)
                _events.emit(JlptDeckEvent.Error(e.message ?: "unknown error"))
            } finally {
                _isAnalyzing.value = false
            }
        }
    }

    /**
     * Writes the planned cards to an `.apkg` file instead of to AnkiDroid.
     *
     * The file is the only path where a card can really arrive suspended, and
     * the only one where the note type is guaranteed to be ours — see
     * [com.yomitanmobile.data.anki.ApkgWriter]. It also needs no AnkiDroid
     * permission and no AnkiDroid on this device.
     */
    fun exportToFile(target: android.net.Uri) {
        val plan = _plan.value ?: return
        if (plan.selected.isEmpty()) return
        if (generationJob?.isActive == true) return

        generationJob = viewModelScope.launch {
            _progress.value = JlptDeckProgress(0, plan.selectedCount)
            try {
                val stylePrefs = readCardStylePreferences(appContext.dataStore.data.first())
                val wantsAudio = _filters.value.generateAudio
                val tts = if (wantsAudio) audioPlayer.ensureTts() else null
                if (wantsAudio && tts == null && !voicevox.isActive()) {
                    _events.emit(JlptDeckEvent.AudioUnavailable)
                }
                val deck = _deckName.value.trim().ifBlank { scale.deckName(plan.level) }
                val entries = monolingualCardResolver.apply(plan.selected.map { it.toWordEntry() })
                val marks = _suspendedKeys.value

                val (notes, media) = ankiCardCreator.buildPackageNotes(
                    entries = entries,
                    stylePrefs = stylePrefs,
                    kanjiProvider = { kanji -> repository.getKanjis(kanji) },
                    tts = tts,
                    tags = tagsForLevel(plan.level),
                    audioWanted = wantsAudio,
                    suspendWord = { markKey(it) in marks },
                    onProgress = { done, total, word ->
                        _progress.value = JlptDeckProgress(done, total, word)
                    }
                )
                val (css, front, back) = ankiCardCreator.packageStyling(stylePrefs)
                val written = apkgWriter.write(
                    target = target,
                    notes = notes,
                    profile = ankiCardCreator.packageProfile(),
                    deckName = deck,
                    css = css,
                    frontTemplate = front,
                    backTemplate = back,
                    media = media
                )
                written.fold(
                    onSuccess = { result ->
                        _events.emit(JlptDeckEvent.FileWritten(result.notes, result.suspended))
                        // Deliberately NOT told to the stored collection scan:
                        // a file is not an import. Until the user imports it,
                        // those words are not in any collection, and claiming
                        // otherwise would make the next run skip them.
                    },
                    onFailure = { error ->
                        _events.emit(JlptDeckEvent.Error(error.message ?: "unknown error"))
                    }
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                _events.emit(JlptDeckEvent.Cancelled)
                throw e
            } catch (e: Exception) {
                Log.e(logTag, "Package export failed", e)
                _events.emit(JlptDeckEvent.Error(e.message ?: "unknown error"))
            } finally {
                _progress.value = null
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
                // ensureTts, not getTts: nothing on this screen ever started
                // the engine, so getTts() was null here and every generated
                // card came out without audio. Null now means the device
                // genuinely has no usable voice, and the user is told.
                val wantsAudio = _filters.value.generateAudio
                val tts = if (wantsAudio) audioPlayer.ensureTts() else null
                if (wantsAudio && tts == null && !voicevox.isActive()) {
                    _events.emit(JlptDeckEvent.AudioUnavailable)
                }
                val deck = _deckName.value.trim().ifBlank { scale.deckName(plan.level) }

                // One batched lookup rewrites the whole deck when the JP-JP
                // engine is on; a no-op otherwise.
                val entries = monolingualCardResolver.apply(
                    plan.selected.map { it.toWordEntry() }
                )

                // AnkiDroid's AddContentApi cannot suspend anything, so a
                // card marked here is written with an extra tag and the user
                // is told to suspend by that tag — or to export the deck as a
                // file instead, where the card really does arrive suspended.
                val suspendedMarks = _suspendedKeys.value
                val toSuspend = entries.filter { markKey(it) in suspendedMarks }
                val toStudy = entries.filterNot { markKey(it) in suspendedMarks }

                suspend fun writeBatch(batch: List<WordEntry>, extraTag: String?) =
                    if (batch.isEmpty()) {
                        Result.success(
                            com.yomitanmobile.data.anki.BatchExportResult(0, 0)
                        )
                    } else {
                        ankiCardCreator.exportBatchToAnki(
                            entries = batch,
                            deckName = deck,
                            stylePrefs = stylePrefs,
                            kanjiProvider = { kanji -> repository.getKanjis(kanji) },
                            tts = tts,
                            // Asked for, even when the synthesiser is missing:
                            // the pronunciation archive answers first and needs
                            // no voice.
                            audioWanted = wantsAudio,
                            tags = tagsForLevel(plan.level) + listOfNotNull(extraTag),
                            onProgress = { done, total, word ->
                                _progress.value = JlptDeckProgress(done, total, word)
                            }
                        )
                    }

                val studied = writeBatch(toStudy, null)
                val marked = writeBatch(toSuspend, SUSPEND_TAG)
                if (toSuspend.isNotEmpty()) {
                    _events.emit(JlptDeckEvent.SuspendNeedsAnki(SUSPEND_TAG, toSuspend.size))
                }
                val result = studied.mapCatching { first ->
                    val second = marked.getOrThrow()
                    com.yomitanmobile.data.anki.BatchExportResult(
                        added = first.added + second.added,
                        failed = first.failed + second.failed
                    )
                }

                result.fold(
                    onSuccess = { batch ->
                        // Those cards are in the collection now. The stored
                        // scan is the only record of what AnkiDroid holds
                        // (generated cards stay out of exported_words), so it
                        // has to learn about them straight away — otherwise
                        // running the generator again offers the very words it
                        // just created.
                        if (batch.added > 0 && _recordAsKnown.value) {
                            ankiCollectionStore.addWords(
                                entries.flatMap {
                                    listOf(it.expression, it.reading)
                                }.filter { it.isNotBlank() },
                                source = generatedSource
                            )
                        }
                        _events.emit(
                            JlptDeckEvent.Finished(
                                JlptDeckResult(
                                    deckName = deck,
                                    added = batch.added,
                                    failed = batch.failed,
                                    recordedAsKnown = _recordAsKnown.value
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

        // The curated list is JLPT's; English has only its tag lists.
        val builtIn = if (scale == com.yomitanmobile.domain.model.LevelScale.JLPT) {
            JlptVocabulary.wordsForLevel(level)
        } else {
            emptyList()
        }
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
        setOf("yomitan-mobile", scale.tag(level), "auto-generated")

    /** The key a word is marked under while reviewing; see `previewKeyOf`. */
    private fun markKey(entry: WordEntry): String = "${entry.expression}\t${entry.reading}"

    companion object {

        /**
         * Carried by cards the user marked as known when they are written
         * through AnkiDroid's provider, which cannot suspend. Searching
         * `tag:yomitan-suspend` in Anki selects exactly them.
         */
        const val SUSPEND_TAG = "yomitan-suspend"


    }
}
