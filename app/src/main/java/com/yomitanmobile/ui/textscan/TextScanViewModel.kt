package com.yomitanmobile.ui.textscan

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yomitanmobile.data.anki.AnkiCardCreator
import com.yomitanmobile.data.anki.AnkiCollectionIndex
import com.yomitanmobile.data.anki.AnkiCollectionStore
import com.yomitanmobile.data.anki.MonolingualCardResolver
import com.yomitanmobile.data.audio.AudioPlayer
import com.yomitanmobile.data.local.dao.ExportedWordDao
import com.yomitanmobile.data.settings.readCardStylePreferences
import com.yomitanmobile.data.text.TextFileReader
import com.yomitanmobile.dataStore
import com.yomitanmobile.domain.model.JlptDeckProgress
import com.yomitanmobile.domain.model.JlptDeckResult
import com.yomitanmobile.domain.model.MergedWordEntry
import com.yomitanmobile.domain.model.ScanToken
import com.yomitanmobile.domain.model.ScannedWord
import com.yomitanmobile.domain.model.TextScanFilters
import com.yomitanmobile.domain.model.TextScanPlan
import com.yomitanmobile.domain.model.TextScanSource
import com.yomitanmobile.domain.model.WordEntry
import com.yomitanmobile.domain.repository.DictionaryRepository
import com.yomitanmobile.domain.usecase.TextScanPlanner
import com.yomitanmobile.util.JapaneseTokenizer
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

sealed class TextScanEvent {
    data class Finished(val result: JlptDeckResult) : TextScanEvent()
    data class Error(val message: String) : TextScanEvent()
    data class FileTooLarge(val megabytes: Int) : TextScanEvent()
    object UnsupportedFormat : TextScanEvent()
    object NoDictionary : TextScanEvent()
    object PermissionRequired : TextScanEvent()
    object AnkiNotInstalled : TextScanEvent()
    object Cancelled : TextScanEvent()
}

/**
 * Drives "make cards from this file": read the document → segment it into
 * dictionary words → drop what is already known → write the rest to AnkiDroid.
 *
 * The expensive half (file read, tokenising, dictionary resolution) runs once
 * per file and its result is cached, so moving a filter chip only re-runs
 * [TextScanPlanner] over data already in memory — the plan updates instantly
 * instead of re-scanning a novel.
 *
 * Like the JLPT generator, the cards are NOT recorded in `exported_words`:
 * they are not mined words and would swamp the mining statistics. The
 * "don't recreate what I already have" guarantee comes from the stored Anki
 * collection scan.
 */
@HiltViewModel
class TextScanViewModel @Inject constructor(
    private val repository: DictionaryRepository,
    private val textFileReader: TextFileReader,
    private val ankiCardCreator: AnkiCardCreator,
    private val ankiCollectionStore: AnkiCollectionStore,
    private val monolingualCardResolver: MonolingualCardResolver,
    private val exportedWordDao: ExportedWordDao,
    private val audioPlayer: AudioPlayer,
    @ApplicationContext private val appContext: Context
) : ViewModel() {

    private val logTag = "TextScanViewModel"

    private val _filters = MutableStateFlow(TextScanFilters())
    val filters: StateFlow<TextScanFilters> = _filters.asStateFlow()

    private val _deckName = MutableStateFlow(DEFAULT_DECK)
    val deckName: StateFlow<String> = _deckName.asStateFlow()

    private val _isAnalyzing = MutableStateFlow(false)
    val isAnalyzing: StateFlow<Boolean> = _isAnalyzing.asStateFlow()

    /** What the analyser is doing right now, for the progress line. */
    private val _analysisStage = MutableStateFlow("")
    val analysisStage: StateFlow<String> = _analysisStage.asStateFlow()

    private val _plan = MutableStateFlow<TextScanPlan?>(null)
    val plan: StateFlow<TextScanPlan?> = _plan.asStateFlow()

    private val _progress = MutableStateFlow<JlptDeckProgress?>(null)
    val progress: StateFlow<JlptDeckProgress?> = _progress.asStateFlow()

    private val _events = MutableSharedFlow<TextScanEvent>()
    val events: SharedFlow<TextScanEvent> = _events.asSharedFlow()

    /** Cached analysis of the current file set; see the class comment. */
    private var scannedWords: List<ScanToken> = emptyList()
    private var resolvedEntries: Map<String, MergedWordEntry> = emptyMap()
    private var totalTokens: Int = 0
    private var sources: List<TextScanSource> = emptyList()
    private var ankiScanUnavailable: Boolean = false
    private var minedKeys: Set<String> = emptySet()
    private var ankiIndex: AnkiCollectionIndex.Index = AnkiCollectionIndex.Index.EMPTY

    private var generationJob: Job? = null

    fun setDeckName(name: String) {
        _deckName.value = name
    }

    fun updateFilters(transform: (TextScanFilters) -> TextScanFilters) {
        _filters.value = transform(_filters.value)
        // Filters are cheap: re-plan from the cached scan instead of re-reading.
        if (sources.isNotEmpty()) recomputePlan()
    }

    /**
     * Reads and analyses the picked documents.
     *
     * Several files are treated as ONE body of text — a season of subtitles or
     * a series of books — so a word met in episode 1 and episode 9 is one card
     * with the combined count, and the file order decides which words count as
     * "early". Files are sorted by name first, which is what puts
     * `ep01, ep02, …` (and `vol1, vol2, …`) in the order they are watched.
     */
    fun analyze(uris: List<Uri>) {
        if (uris.isEmpty()) return
        if (_isAnalyzing.value) return
        _isAnalyzing.value = true
        _plan.value = null
        viewModelScope.launch {
            try {
                _analysisStage.value = STAGE_READING
                val documents = uris.map { textFileReader.read(it) }
                    .sortedBy { it.fileName.lowercase() }

                _analysisStage.value = STAGE_LEXICON
                val lexicon = repository.getSurfaceLexicon()
                if (lexicon.isEmpty()) {
                    _events.emit(TextScanEvent.NoDictionary)
                    return@launch
                }

                _analysisStage.value = STAGE_TOKENIZING
                val tokens = withContext(Dispatchers.Default) {
                    val words = JapaneseTokenizer.Lexicon { it in lexicon }
                    val accumulator = JapaneseTokenizer.Accumulator()
                    for (document in documents) {
                        accumulator.add(document.text, words)
                    }
                    val totalLength = accumulator.totalLength.coerceAtLeast(1)
                    accumulator.tokens().map { token ->
                        ScanToken(
                            baseForm = token.baseForm,
                            occurrences = token.count,
                            sentence = token.sentence,
                            // 1.0 at the start of the first file, 0.0 at the
                            // end of the last one.
                            earliness = 1f - token.firstOffset.toFloat() / totalLength
                        )
                    }
                }

                _analysisStage.value = STAGE_RESOLVING
                scannedWords = tokens
                totalTokens = tokens.sumOf { it.occurrences }
                resolvedEntries = resolveEntries(tokens.mapTo(HashSet()) { it.baseForm })
                sources = documents.map { document ->
                    TextScanSource(
                        fileName = document.fileName,
                        formatLabel = document.format.label,
                        charsetName = document.charsetName,
                        characterCount = document.text.count { JapaneseTokenizer.isJapanese(it) },
                        partCount = document.partCount
                    )
                }

                _analysisStage.value = STAGE_COMPARING
                val index = ankiCollectionStore.asIndex()
                ankiScanUnavailable = !index.available
                ankiIndex = index
                minedKeys = runCatching {
                    exportedWordDao.getAllExports().mapTo(HashSet()) {
                        matchKey(it.expression, it.reading)
                    }
                }.getOrElse {
                    Log.w(logTag, "Reading exported words failed", it)
                    emptySet()
                }

                if (_deckName.value == DEFAULT_DECK) {
                    _deckName.value = deckNameFor(documents.map { it.fileName })
                }
                recomputePlan()
            } catch (e: TextFileReader.TooLargeException) {
                _events.emit(TextScanEvent.FileTooLarge((e.bytes / (1024 * 1024)).toInt()))
            } catch (e: TextFileReader.UnsupportedFormatException) {
                _events.emit(TextScanEvent.UnsupportedFormat)
            } catch (e: Exception) {
                Log.e(logTag, "Text scan failed", e)
                _events.emit(TextScanEvent.Error(e.message ?: "unknown error"))
            } finally {
                _analysisStage.value = ""
                _isAnalyzing.value = false
            }
        }
    }

    private fun recomputePlan() {
        if (sources.isEmpty()) return
        val filters = _filters.value
        _plan.value = TextScanPlanner.plan(
            sources = sources,
            words = scannedWords,
            entries = resolvedEntries,
            filters = filters,
            totalTokenCount = totalTokens,
            isInAnki = { ankiIndex.contains(it.primaryExpression, it.reading) },
            isMined = { matchKey(it.primaryExpression, it.reading) in minedKeys },
            ankiScanUnavailable = filters.skipAlreadyInAnki && ankiScanUnavailable
        )
    }

    /**
     * Dictionary entry per scanned word.
     *
     * Words are looked up by written form first and by reading only for what
     * is left over — a text spells 見る with kanji but みる without, and both
     * must reach the same entry. Where a reading matches several words
     * (きく → 聞く / 効く / 菊) the commonest one wins; without part-of-speech
     * context there is no better signal, and picking the rarest homophone
     * would be strictly worse.
     */
    private suspend fun resolveEntries(words: Set<String>): Map<String, MergedWordEntry> {
        if (words.isEmpty()) return emptyMap()
        val wordList = words.toList()

        val byExpression = repository.getEntriesForExpressions(wordList)
            .groupBy { it.expression }
        val unresolved = wordList.filter { it !in byExpression }
        val byReading = if (unresolved.isEmpty()) {
            emptyMap()
        } else {
            repository.getEntriesForReadings(unresolved).groupBy { it.reading }
        }

        val result = HashMap<String, MergedWordEntry>(words.size)
        for (word in wordList) {
            val entries: List<WordEntry> = byExpression[word] ?: byReading[word] ?: continue
            val merged = MergedWordEntry.mergeEntries(entries)
            val best = merged.minByOrNull {
                if (it.frequency > 0) it.frequency else Int.MAX_VALUE
            } ?: continue
            result[word] = best
        }
        return result
    }

    /**
     * Attaches the sentence the word was met in.
     *
     * It goes into `exampleSentence`, which is the first candidate
     * `AnkiCardCreator.pickFrontContextSentence` looks at — so the sentence
     * from the user's own material wins over any dictionary example, and the
     * existing highlighter marks the target word inside it. Nothing in the card
     * builder needed changing.
     */
    private fun withSourceSentence(word: ScannedWord, enabled: Boolean): WordEntry {
        val entry = word.entry.toWordEntry()
        if (!enabled || word.sentence.isBlank()) return entry
        return entry.copy(
            exampleSentence = word.sentence,
            // The source sentence has no translation, and keeping the previous
            // entry's one would caption this sentence with another sentence's
            // meaning.
            exampleSentenceTranslation = ""
        )
    }

    /** Writes the planned cards to AnkiDroid. */
    fun generate() {
        val plan = _plan.value ?: return
        if (plan.selected.isEmpty()) return
        if (generationJob?.isActive == true) return

        generationJob = viewModelScope.launch {
            _progress.value = JlptDeckProgress(0, plan.selectedCount)
            try {
                val filters = _filters.value
                val storedStyle = readCardStylePreferences(appContext.dataStore.data.first())
                // The front-context slot is a card-style preference the user
                // may well have turned off globally. Asking for source
                // sentences here means asking for that slot, so force it on for
                // this batch instead of silently producing cards without the
                // sentence that was the point of the scan.
                val stylePrefs = if (filters.useSourceSentences) {
                    storedStyle.copy(showFrontContextSentence = true)
                } else {
                    storedStyle
                }
                val tts = if (filters.generateAudio) audioPlayer.getTts() else null
                val deck = _deckName.value.trim().ifBlank { DEFAULT_DECK }

                val entries = monolingualCardResolver.apply(
                    plan.selected.map { withSourceSentence(it, filters.useSourceSentences) }
                )

                val result = ankiCardCreator.exportBatchToAnki(
                    entries = entries,
                    deckName = deck,
                    stylePrefs = stylePrefs,
                    kanjiProvider = { kanji -> repository.getKanjis(kanji) },
                    tts = tts,
                    tags = tagsForSources(plan.sources),
                    onProgress = { done, total, word ->
                        _progress.value = JlptDeckProgress(done, total, word)
                    }
                )

                result.fold(
                    onSuccess = { batch ->
                        _events.emit(
                            TextScanEvent.Finished(
                                JlptDeckResult(
                                    deckName = deck,
                                    added = batch.added,
                                    failed = batch.failed
                                )
                            )
                        )
                        // Those words are now in the collection; a re-run must
                        // see them rather than offering them again.
                        _plan.value = null
                    },
                    onFailure = { error ->
                        when {
                            error is SecurityException ->
                                _events.emit(TextScanEvent.PermissionRequired)
                            error is IllegalStateException &&
                                error.message?.contains("not installed") == true ->
                                _events.emit(TextScanEvent.AnkiNotInstalled)
                            else -> _events.emit(
                                TextScanEvent.Error(error.message ?: "unknown error")
                            )
                        }
                    }
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                _events.emit(TextScanEvent.Cancelled)
                throw e
            } catch (e: Exception) {
                Log.e(logTag, "Card generation failed", e)
                _events.emit(TextScanEvent.Error(e.message ?: "unknown error"))
            } finally {
                _progress.value = null
            }
        }
    }

    fun cancelGeneration() {
        generationJob?.cancel()
    }

    /**
     * Tags carry the source file so a deck stays findable and bulk-deletable
     * in Anki: `yomitan-mobile`, `text-scan`, and a sanitised file name
     * (Anki tags cannot contain spaces).
     */
    private fun tagsForSources(sources: List<TextScanSource>): Set<String> {
        // One tag per file would be unusable for a 24-episode season, so a
        // multi-file scan is tagged with what the file names have in common
        // (the series name) and falls back to the first file's name.
        val slug = slugify(commonPrefix(sources.map { it.fileName }))
            .ifBlank { slugify(sources.firstOrNull()?.fileName.orEmpty()) }
        return buildSet {
            add("yomitan-mobile")
            add("text-scan")
            if (slug.isNotBlank()) add(slug)
        }
    }

    private fun slugify(name: String): String = name
        .substringBeforeLast('.')
        .replace(Regex("""[\s"]+"""), "-")
        .trim('-', '_', '.')
        .take(40)

    /**
     * Deck name suggestion. For a set of files it is what their names share
     * ("Shirokuma-Cafe-01.srt" + "Shirokuma-Cafe-02.srt" → "Shirokuma-Cafe"),
     * which is the series name often enough to be worth it, and the first file
     * name when they share nothing.
     */
    private fun deckNameFor(fileNames: List<String>): String {
        val shared = commonPrefix(fileNames).trim(' ', '-', '_', '.', '[', '(')
        val base = shared.ifBlank { fileNames.firstOrNull()?.substringBeforeLast('.').orEmpty() }
        return "Yomitan::" + base.take(60).ifBlank { "Text" }
    }

    private fun commonPrefix(names: List<String>): String {
        if (names.isEmpty()) return ""
        if (names.size == 1) return names.first().substringBeforeLast('.')
        var prefix = names.first().substringBeforeLast('.')
        for (name in names.drop(1)) {
            prefix = prefix.commonPrefixWith(name.substringBeforeLast('.'), ignoreCase = true)
            if (prefix.isEmpty()) return ""
        }
        // A one- or two-character "shared" prefix is a coincidence, not a name.
        return if (prefix.length >= 3) prefix else ""
    }

    /**
     * (expression, reading) identity for the already-mined check, matching
     * [com.yomitanmobile.ui.jlptdeck.JlptDeckViewModel]: readings are folded
     * to hiragana because `exported_words` stores them in hiragana while
     * dictionaries store plenty in katakana.
     */
    private fun matchKey(expression: String, reading: String): String {
        val expr = expression.trim()
        val read = reading.trim().ifEmpty { expr }
        return expr + " " + read.toHiragana()
    }

    private fun String.toHiragana(): String = buildString(length) {
        for (c in this@toHiragana) {
            append(if (c.code in 0x30A1..0x30F6) (c.code - 0x60).toChar() else c)
        }
    }

    companion object {
        const val DEFAULT_DECK = "Yomitan Mobile"

        const val STAGE_READING = "reading"
        const val STAGE_LEXICON = "lexicon"
        const val STAGE_TOKENIZING = "tokenizing"
        const val STAGE_RESOLVING = "resolving"
        const val STAGE_COMPARING = "comparing"
    }
}
