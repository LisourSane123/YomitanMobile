package com.yomitanmobile.data.repository

import android.util.Log
import androidx.room.withTransaction
import com.yomitanmobile.data.local.dao.DictionaryDao
import com.yomitanmobile.data.local.dao.DictionaryInfoDao
import com.yomitanmobile.data.local.dao.FrequencyDao
import com.yomitanmobile.data.local.dao.JlptTagDao
import com.yomitanmobile.data.local.dao.KanjiDao
import com.yomitanmobile.data.local.database.AppDatabase
import com.yomitanmobile.data.local.database.FrequencyPositions
import com.yomitanmobile.data.local.entity.DictionaryEntry
import com.yomitanmobile.data.local.entity.DictionaryInfo
import com.yomitanmobile.data.local.entity.FrequencyListSetting
import com.yomitanmobile.data.local.entity.JlptTag
import com.yomitanmobile.data.local.entity.KanjiEntry
import com.yomitanmobile.data.local.entity.WordFrequency
import com.yomitanmobile.data.mapper.toDomain
import com.yomitanmobile.data.parser.YomitanDictionaryParser
import com.yomitanmobile.data.settings.FrequencySettings
import com.yomitanmobile.data.settings.LanguageSettings
import com.yomitanmobile.domain.model.AppLanguage
import com.yomitanmobile.domain.model.FrequencyDirection
import com.yomitanmobile.domain.model.ImportProgress
import com.yomitanmobile.domain.model.ImportResult
import com.yomitanmobile.domain.model.WordEntry
import com.yomitanmobile.domain.model.WordFrequencyInfo
import com.yomitanmobile.domain.repository.DictionaryRepository
import com.yomitanmobile.util.InputSanitizer
import com.yomitanmobile.util.JapaneseTokenizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.InputStream
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DictionaryRepositoryImpl @Inject constructor(
    private val dictionaryDao: DictionaryDao,
    private val dictionaryInfoDao: DictionaryInfoDao,
    private val kanjiDao: KanjiDao,
    private val frequencyDao: FrequencyDao,
    private val jlptTagDao: JlptTagDao,
    private val parser: YomitanDictionaryParser,
    private val database: AppDatabase,
    private val languageSettings: LanguageSettings,
    private val frequencySettings: FrequencySettings
) : DictionaryRepository {

    // Every read below is scoped to the language being studied. Reading it
    // per call rather than caching a field here keeps the repository correct
    // if the setting is ever changed without a restart; today it cannot be,
    // but a stale field would fail silently (empty results) rather than loudly.
    private val language: String
        get() = languageSettings.current.entryTag

    // Matches YomitanDictionaryParser's placeholder name: term/kanji/frequency
    // rows are written under this and renamed to the real index.json title
    // once parsing finishes (the title isn't reliably known mid-stream).
    private val tempDictionaryName = "temp"

    // Safe batch size for `column IN (:list)` binds. Kept comfortably below
    // SQLite's 999-variable ceiling (Android 8–11) with room for the query's
    // other parameters.
    private val IN_CLAUSE_CHUNK = 400

    private companion object {
        // A real DB/parse failure and an empty result set both surface as an
        // empty list to the UI; without logging the throwable first they're
        // indistinguishable, which once hid a query-syntax crash for a whole
        // release. Every swallow below logs before it degrades to empty.
        const val TAG = "DictionaryRepo"
    }

    // NOTE: previously we toggled `PRAGMA synchronous = OFF` and
    // `journal_mode = MEMORY` for the duration of the import to speed up
    // bulk inserts. That trade was unsafe: if the process was killed in the
    // middle of a multi-GB import (OOM, ANR, user force-stop) the database
    // could be left in an unrecoverable state, taking the user's favorites,
    // search history, and Anki export log down with it. Room's default
    // WAL+NORMAL is already fast enough — the per-batch transaction
    // grouping (10k entries) is where the real win comes from. Do not
    // re-introduce these PRAGMAs without addressing the corruption risk.

    override suspend fun getKanjis(kanjiList: List<String>): List<KanjiEntry> {
        return kanjiDao.getKanjis(kanjiList)
    }

    override suspend fun getKanji(kanji: String): KanjiEntry? =
        withContext(Dispatchers.IO) { runCatching { kanjiDao.getKanji(kanji) }.getOrNull() }

    override suspend fun listKanji(grade: Int, jlpt: Int): List<KanjiEntry> =
        withContext(Dispatchers.IO) {
            runCatching { kanjiDao.listKanji(grade, jlpt) }.getOrDefault(emptyList())
        }

    override suspend fun kanjiCountsByGrade() = withContext(Dispatchers.IO) {
        runCatching { kanjiDao.countsByGrade() }.getOrDefault(emptyList())
    }

    override suspend fun kanjiCountsByJlpt() = withContext(Dispatchers.IO) {
        runCatching { kanjiDao.countsByJlpt() }.getOrDefault(emptyList())
    }

    override suspend fun wordsContainingKanji(kanji: String, limit: Int): List<WordEntry> =
        withContext(Dispatchers.IO) {
            runCatching {
                dictionaryDao.wordsContainingKanji(kanji, language, limit).map { it.toDomain() }
            }.getOrDefault(emptyList())
        }

    override suspend fun getReadingsForExpressions(
        expressions: List<String>
    ): Map<String, String> {
        if (expressions.isEmpty()) return emptyMap()
        return try {
            // The furigana synthesiser feeds hundreds of candidate substrings in
            // here (up to ~12 per kanji position × every example sentence on the
            // page). A single `IN (:expressions)` bind blows past SQLite's
            // SQLITE_MAX_VARIABLE_NUMBER (999 on Android 8–11 / API 26–30), which
            // threw and — via the catch below — silently returned no readings, so
            // tappable furigana never appeared on those devices. Chunk the IN
            // list well under the limit and merge, then keep the best
            // (frequency-ranked) reading per expression across chunks so the
            // result is identical to one big ordered query would have produced.
            expressions.distinct()
                .chunked(IN_CLAUSE_CHUNK)
                .flatMap { chunk -> dictionaryDao.getReadingsForExpressions(chunk, language) }
                .groupBy { it.expression }
                .mapValues { (_, rows) ->
                    // Ranked rows (frequency > 0) beat unranked; among ranked the
                    // lowest rank wins — matches the DAO's ORDER BY across chunks.
                    rows.minByOrNull { if (it.frequency > 0) it.frequency else Int.MAX_VALUE }!!.reading
                }
        } catch (e: Exception) {
            Log.w(TAG, "getReadingsForExpressions failed (${expressions.size} expressions)", e)
            emptyMap()
        }
    }

    override suspend fun getEntriesByJlptLevel(level: Int): List<WordEntry> {
        if (level !in 1..5) return emptyList()
        return withContext(Dispatchers.IO) {
            try {
                dictionaryDao.getEntriesByJlptLevel(level, language).map { it.toDomain() }
            } catch (e: Exception) {
                Log.w(TAG, "getEntriesByJlptLevel($level) failed", e)
                emptyList()
            }
        }
    }

    override suspend fun getEntriesForExpressions(expressions: List<String>): List<WordEntry> {
        if (expressions.isEmpty()) return emptyList()
        return withContext(Dispatchers.IO) {
            try {
                expressions.distinct()
                    .chunked(IN_CLAUSE_CHUNK)
                    .flatMap { chunk -> dictionaryDao.getEntriesByExpressions(chunk, language) }
                    .map { it.toDomain() }
            } catch (e: Exception) {
                Log.w(TAG, "getEntriesForExpressions failed (${expressions.size} expressions)", e)
                emptyList()
            }
        }
    }

    override suspend fun getEntriesForReadings(readings: List<String>): List<WordEntry> {
        if (readings.isEmpty()) return emptyList()
        return withContext(Dispatchers.IO) {
            try {
                readings.distinct()
                    .chunked(IN_CLAUSE_CHUNK)
                    .flatMap { chunk -> dictionaryDao.getEntriesByReadings(chunk, language) }
                    .map { it.toDomain() }
            } catch (e: Exception) {
                Log.w(TAG, "getEntriesForReadings failed (${readings.size} readings)", e)
                emptyList()
            }
        }
    }

    override suspend fun writtenFormsBySequence(
        readings: Collection<String>
    ): Map<Int, List<String>> {
        if (readings.isEmpty()) return emptyMap()
        return withContext(Dispatchers.IO) {
            try {
                readings.filter { it.isNotBlank() }
                    .distinct()
                    .chunked(IN_CLAUSE_CHUNK)
                    .flatMap { chunk -> dictionaryDao.getEntriesByReadings(chunk, language) }
                    .filter { it.sequenceNumber > 0 && it.expression.isNotBlank() }
                    .groupBy { it.sequenceNumber }
                    .mapValues { (_, rows) -> rows.map { it.expression }.distinct() }
            } catch (e: Exception) {
                Log.w(TAG, "writtenFormsBySequence failed (${readings.size} readings)", e)
                emptyMap()
            }
        }
    }

    override suspend fun getSurfaceLexicon(): Set<String> = withContext(Dispatchers.IO) {
        try {
            val expressions = dictionaryDao.getAllExpressions(language)
            // Only the readings that double as a spelling — see
            // [DictionaryDao.getKanaWrittenReadings]; taking every reading is
            // what let kana fragments of one word match another word entirely.
            val readings = dictionaryDao.getKanaWrittenReadings(language)
            // …plus the kana spellings the frequency lists rank as written.
            // Jitendex does not tag ご飯, 頷く or 柔らかい "usually kana", but a
            // novel writes ごはん, うなずく and やわらかい — and without them
            // longest match cut ご + はん, うな + ずい, やわ + らかい.
            val writtenKana = runCatching {
                frequencyDao.getRankedWrittenForms(JapaneseTokenizer.KANA_WRITTEN_RANK)
                    .filter { form -> form.all { JapaneseTokenizer.isKana(it) || it == 'ー' } }
            }.getOrDefault(emptyList())
            HashSet<String>(expressions.size + readings.size + writtenKana.size).apply {
                addAll(expressions)
                addAll(readings)
                addAll(writtenKana)
            }
        } catch (e: Exception) {
            Log.w(TAG, "getSurfaceLexicon failed", e)
            emptySet()
        }
    }

    override suspend fun getCommonSurfaces(maxRank: Int): Map<String, Int> =
        withContext(Dispatchers.IO) {
            try {
                val ranked = frequencyDao.getCommonSurfaceRanks(maxRank)
                // Best rank wins when several lists (or both columns) name the
                // same surface, the same rule as applyFrequenciesFromTable().
                HashMap<String, Int>(ranked.size).apply {
                    for (row in ranked) {
                        val existing = this[row.surface]
                        if (existing == null || row.rank < existing) this[row.surface] = row.rank
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "getCommonSurfaces failed", e)
                emptyMap()
            }
        }

    override suspend fun getEntriesForExpressionsFromDictionary(
        expressions: List<String>,
        dictionaryName: String
    ): List<WordEntry> {
        if (expressions.isEmpty() || dictionaryName.isBlank()) return emptyList()
        return withContext(Dispatchers.IO) {
            try {
                expressions.distinct()
                    .chunked(IN_CLAUSE_CHUNK)
                    .flatMap { chunk ->
                        dictionaryDao.getEntriesByExpressionsFromDictionary(chunk, dictionaryName)
                    }
                    .map { it.toDomain() }
            } catch (e: Exception) {
                Log.w(TAG, "getEntriesForExpressionsFromDictionary failed", e)
                emptyList()
            }
        }
    }

    override fun searchExact(query: String): Flow<List<WordEntry>> {
        if (query.isBlank()) return flowOf(emptyList())
        val trimmed = query.trim()
        return dictionaryDao.searchExact(trimmed, language)
            .map { entries -> entries.map { it.toDomain() } }
            .catch { e ->
                // The query text itself stays out of the log: Log.w survives R8
                // (only v/d/i are stripped), so it would ship a record of what
                // the user looks up to logcat on release builds.
                Log.w(TAG, "searchExact failed (${trimmed.length} chars)", e)
                emit(emptyList())
            }
    }

    override fun searchCombined(query: String): Flow<List<WordEntry>> {
        if (query.isBlank()) return flowOf(emptyList())
        val trimmed = query.trim()
        // The DAO matches the prefix as index ranges, so it needs bounds
        // rather than an escaped LIKE pattern. Three case forms, because a
        // range comparison is case-sensitive and LIKE was not: as typed, all
        // lower case, and first-letter capitalised (what the keyboard does on
        // its own). All three collapse to one range for a Japanese query.
        val lowered = trimmed.lowercase()
        val titled = lowered.replaceFirstChar { it.uppercaseChar() }
        return dictionaryDao.searchCombined(
            exactQuery = trimmed,
            prefixStart = trimmed,
            prefixEnd = prefixUpperBound(trimmed),
            lowerPrefixStart = lowered,
            lowerPrefixEnd = prefixUpperBound(lowered),
            titlePrefixStart = titled,
            titlePrefixEnd = prefixUpperBound(titled),
            language = language
        )
            .map { entries -> entries.map { it.toDomain() } }
            .catch { e ->
                Log.w(TAG, "searchCombined failed (${trimmed.length} chars)", e)
                emit(emptyList())
            }
    }

    /**
     * Exclusive upper bound of the prefix range for [prefix]: the prefix
     * followed by the highest code point there is, which sorts after every
     * string that starts with it under BINARY collation. U+10FFFF rather than
     * U+FFFF, so an entry whose next character sits outside the BMP (rare
     * kanji) still falls inside the range.
     */
    private fun prefixUpperBound(prefix: String): String = prefix + "\uDBFF\uDFFF"

    override fun searchContains(query: String): Flow<List<WordEntry>> {
        if (query.isBlank()) return flowOf(emptyList())
        val trimmed = query.trim()
        val likeQuery = InputSanitizer.sanitizeLikeQuery(trimmed)
        if (likeQuery.isBlank()) return flowOf(emptyList())
        return dictionaryDao.searchContains(likeQuery, language)
            .map { entries -> entries.map { it.toDomain() } }
            .catch { e ->
                Log.w(TAG, "searchContains failed (${trimmed.length} chars)", e)
                emit(emptyList())
            }
    }

    override fun searchByDefinition(query: String): Flow<List<WordEntry>> {
        if (query.isBlank()) return flowOf(emptyList())
        val ftsQuery = InputSanitizer.sanitizeFtsQuery(query)
        if (ftsQuery.isBlank()) return flowOf(emptyList())
        return dictionaryDao.searchByDefinition(ftsQuery, language)
            .map { entries -> entries.map { it.toDomain() } }
            .catch { e ->
                Log.w(TAG, "searchByDefinition failed (${ftsQuery.length} chars)", e)
                emit(emptyList())
            }
    }

    override suspend fun getEntry(id: Long): WordEntry? {
        return try {
            dictionaryDao.getById(id)?.toDomain()
        } catch (e: Exception) {
            Log.w(TAG, "getEntry($id) failed", e)
            null
        }
    }

    override suspend fun getEntriesByReading(reading: String): List<WordEntry> {
        return try {
            dictionaryDao.getByReading(reading, language).map { it.toDomain() }
        } catch (e: Exception) {
            Log.w(TAG, "getEntriesByReading('$reading') failed", e)
            emptyList()
        }
    }

    override suspend fun importDictionary(
        inputStream: InputStream,
        onProgress: (ImportProgress) -> Unit
    ): ImportResult = withContext(Dispatchers.IO) {
        try {
            var totalInserted = 0
            var totalKanjiInserted = 0
            var dictionaryNameFromBatch: String
            var totalFreqUpdates = 0
            var totalPitchUpdates = 0
            var totalJlptUpdates = 0
            var ftsWarning: String? = null

            // Clear any rows left under the temp name by a previous interrupted
            // import before we start writing this one. Term/kanji rows matter
            // most: stale "temp" entries would surface in search results AND
            // get renamed into THIS import's dictionary by the promotion step
            // below, silently mixing two dictionaries' contents.
            clearTempRows()

            // Use streaming parser — entries are inserted in batches as they're parsed.
            // 10000 fits comfortably in one transaction and roughly halves the
            // transaction-commit overhead vs. 5000.
            val batchSize = 10000
            // What the rows are stamped with while streaming; corrected below
            // if index.json declares a sourceLanguage of its own.
            val importLanguage = languageSettings.current
            val parseResult = parser.parseFromZipStreaming(
                inputStream = inputStream,
                defaultLanguage = importLanguage.entryTag,
                onProgress = onProgress,
                onBatch = { batch, _ ->
                    batch.chunked(batchSize).forEach { chunk ->
                        dictionaryDao.insertAll(chunk)
                    }
                    totalInserted += batch.size
                },
                onMetaBatch = { freqUpdates, pitchMap ->
                    if (freqUpdates.isNotEmpty()) {
                        // Only the per-source row, keeping THIS list's number
                        // as shipped. Nothing is written to the term rows
                        // here: which way the numbers run is not known until
                        // the whole list has been read, and the rollup after
                        // the import does that job from this table. Written
                        // under the temp name and renamed once we know the
                        // index.json title.
                        frequencyDao.insertAll(
                            freqUpdates.map { u ->
                                WordFrequency(
                                    expression = u.expression,
                                    reading = u.reading?.trim().orEmpty(),
                                    dictionary = tempDictionaryName,
                                    rank = u.frequency,
                                    displayValue = u.displayValue.ifBlank { u.frequency.toString() }
                                )
                            }
                        )
                        totalFreqUpdates += freqUpdates.size
                    }
                    if (pitchMap.isNotEmpty()) {
                        dictionaryDao.updatePitchAccentBatch(pitchMap, importLanguage.entryTag)
                        totalPitchUpdates += pitchMap.size
                    }
                },
                onKanjiBatch = { batch, _ ->
                    batch.chunked(batchSize).forEach { chunk ->
                        kanjiDao.insertAll(chunk)
                    }
                    totalKanjiInserted += batch.size
                },
                onJlptBatch = { jlptUpdates ->
                    if (jlptUpdates.isNotEmpty()) {
                        // Persisted only. The table is the source of truth:
                        // term rows lose their jlpt_level on every re-import,
                        // a tag dictionary installed before its term dictionary
                        // has nothing to update, and the rollup after the
                        // import (applyJlptLevelsFromTags) is what knows which
                        // language a tag is for — writing rows from here put a
                        // CEFR level on a Japanese row of the same spelling.
                        jlptTagDao.insertAll(
                            jlptUpdates.map { u ->
                                JlptTag(
                                    expression = u.expression,
                                    reading = u.reading?.trim().orEmpty(),
                                    dictionary = tempDictionaryName,
                                    level = u.level
                                )
                            }
                        )
                        totalJlptUpdates += jlptUpdates.size
                    }
                }
            )

            dictionaryNameFromBatch = parseResult.dictionaryName
            val declaredLanguage = AppLanguage.fromSourceLanguageCode(parseResult.sourceLanguage)
            val effectiveLanguage = declaredLanguage ?: importLanguage

            // Promote frequency rows from the temp name to the real list title.
            // Runs for meta AND term dicts (a term dict can carry its own freq
            // meta banks). Drop any prior import of the same list first so a
            // re-import replaces rather than duplicates.
            if (dictionaryNameFromBatch != tempDictionaryName) {
                // One transaction: between the delete and the rename the user
                // owns neither copy of the list, and a crash in that window
                // used to leave them with nothing.
                database.withTransaction {
                    frequencyDao.deleteByDictionary(dictionaryNameFromBatch)
                    frequencyDao.updateDictionaryName(tempDictionaryName, dictionaryNameFromBatch)
                    // Same replace-don't-duplicate rename for the JLPT tag rows.
                    jlptTagDao.deleteByDictionary(dictionaryNameFromBatch)
                    jlptTagDao.updateDictionaryName(tempDictionaryName, dictionaryNameFromBatch)
                }
            }

            // For meta-only dictionaries (frequency/pitch), we don't insert term entries
            // — the meta data was already applied to existing entries via onMetaBatch
            if (!parseResult.isMetaDictionary) {
                // Update entries that were inserted with "temp" dictionary name
                // to the actual dictionary name from index.json
                if (dictionaryNameFromBatch != "temp") {
                    // Replace any PREVIOUS import of the same dictionary before
                    // promoting the freshly-parsed rows. The new rows are still
                    // under "temp" here, so deleting the real-name rows removes
                    // only the OLD copy — without this a re-import (e.g. to
                    // backfill furigana with the updated parser) would leave two
                    // full copies of every entry in the table.
                    // Delete-then-rename must be atomic. Interrupted between
                    // the two statements it destroyed the installed copy and
                    // left the new rows under the temp name, which the next
                    // import then cleared: the dictionary was simply gone.
                    database.withTransaction {
                        dictionaryDao.deleteByDictionary(dictionaryNameFromBatch)
                        kanjiDao.deleteByDictionary(dictionaryNameFromBatch)
                        dictionaryDao.updateDictionaryName("temp", dictionaryNameFromBatch)
                        kanjiDao.updateDictionaryName("temp", dictionaryNameFromBatch)
                    }
                }

                // index.json wins over the language that was active when the
                // user tapped import: a kty-en-pl zip is an English dictionary
                // whether or not the app happened to be in Japanese mode. Only
                // written when the declared language actually differs, so the
                // common case (no sourceLanguage field) costs nothing.
                if (declaredLanguage != null && declaredLanguage != importLanguage) {
                    dictionaryDao.updateLanguageForDictionary(
                        dictionaryNameFromBatch,
                        declaredLanguage.entryTag
                    )
                }

                // A failed rebuild is not cosmetic: definition (meaning)
                // search reads the FTS table, so it would keep answering with
                // the PREVIOUS dictionary's contents — or nothing — while the
                // import reported success. Report it instead of swallowing it.
                try {
                    dictionaryDao.rebuildFtsIndex()
                } catch (e: Exception) {
                    Log.w(TAG, "FTS rebuild after import failed", e)
                    ftsWarning = "FTS"
                }
            }

            // The dictionary's row goes in BEFORE the rollup: the rollup reads
            // each list's language from it, and a list without a row is taken
            // for Japanese — so an English list imported last would sit out
            // the English pass until something rolled up again.
            // Clean up any previous DictionaryInfo for this dictionary (both meta and regular)
            val existingInfo = dictionaryInfoDao.getByName(dictionaryNameFromBatch)
            if (existingInfo != null) {
                dictionaryInfoDao.deleteByName(dictionaryNameFromBatch)
            }

            val entryCount = if (parseResult.isMetaDictionary) {
                totalFreqUpdates + totalPitchUpdates + totalJlptUpdates
            } else {
                totalInserted + totalKanjiInserted
            }

            dictionaryInfoDao.insert(
                DictionaryInfo(
                    name = dictionaryNameFromBatch,
                    version = parseResult.version,
                    revision = parseResult.revision,
                    entryCount = entryCount,
                    language = effectiveLanguage.entryTag
                )
            )

            // Roll the stored meta tables back down onto the term rows. Needed
            // in both directions:
            //  • a term import writes rows with jlpt_level = 0 / frequency = 0
            //    (a plain JMdict carries neither) and replaces rows that had
            //    the meta data applied to them,
            //  • a meta import may have arrived BEFORE the term dictionary it
            //    describes, so its per-row updates matched nothing.
            // Without this the JLPT deck generator silently drops to zero
            // candidates depending only on install order.
            // Before the rollup: the list's direction decides its positions,
            // and the rollup reads positions.
            if (totalFreqUpdates > 0) {
                try {
                    classifyFrequencyList(dictionaryNameFromBatch)
                } catch (e: Exception) {
                    Log.w(TAG, "Classifying the imported frequency list failed", e)
                }
            }

            reapplyStoredMeta()

            ImportResult(
                success = true,
                dictionaryName = dictionaryNameFromBatch,
                entriesImported = entryCount,
                warning = ftsWarning
            )
        } catch (e: Exception) {
            // Remove the partial rows this failed import wrote under the temp
            // name so they don't pollute search until the next import runs.
            try {
                clearTempRows()
            } catch (cleanupError: Exception) {
                Log.w(TAG, "temp-row cleanup after failed import also failed", cleanupError)
            }
            ImportResult(
                success = false,
                dictionaryName = "Unknown",
                entriesImported = 0,
                errorMessage = e.message ?: "Unknown error during import"
            )
        }
    }

    private suspend fun clearTempRows() {
        dictionaryDao.deleteByDictionary(tempDictionaryName)
        kanjiDao.deleteByDictionary(tempDictionaryName)
        frequencyDao.deleteByDictionary(tempDictionaryName)
        jlptTagDao.deleteByDictionary(tempDictionaryName)
    }

    /**
     * Re-applies the two side tables (`jlpt_tags`, `word_frequencies`) onto
     * `dictionary_entries`. Runs after every import, so the term rows always
     * reflect all installed meta data no matter what order things were
     * installed or reinstalled in. Both statements only ever improve a row
     * (easiest JLPT level wins, best frequency rank wins), so running it more
     * often than strictly necessary is safe.
     *
     * Failures are logged and swallowed: the import itself already succeeded,
     * and the next import (or a manual re-import of the meta dictionary)
     * repairs the rollup.
     */
    private suspend fun reapplyStoredMeta() {
        try {
            dictionaryDao.applyJlptLevelsFromTags()
        } catch (e: Exception) {
            Log.w(TAG, "Re-applying stored JLPT levels failed", e)
        }
        try {
            rollupFrequencies()
        } catch (e: Exception) {
            Log.w(TAG, "Re-applying stored frequencies failed", e)
        }
    }

    /**
     * Decides which way a freshly imported list's numbers run and computes
     * each word's position in it. See [FrequencyDirection] for why the format
     * cannot say which, and [FrequencyPositions] for what the position is.
     *
     * The list's numbers are not touched. A direction stored by hand in an
     * older version is kept rather than re-detected.
     */
    private suspend fun classifyFrequencyList(dictionary: String) {
        if (dictionary.isBlank() || dictionary == tempDictionaryName) return
        val stats = frequencyDao.statsFor(dictionary) ?: return
        if (stats.rowCount == 0) return
        val existing = frequencyDao.getListSetting(dictionary)
        val userChosen = existing != null && !existing.autoDetected
        val higherIsBetter = if (userChosen && existing != null) {
            existing.higherIsBetter
        } else {
            FrequencyDirection.isCountBased(
                dictionaryName = dictionary,
                rowCount = stats.rowCount,
                distinctValues = stats.distinctValues,
                maxValue = stats.maxValue
            )
        }
        frequencyDao.upsertListSetting(
            FrequencyListSetting(
                dictionary = dictionary,
                higherIsBetter = higherIsBetter,
                autoDetected = !userChosen
            )
        )
        database.withTransaction {
            FrequencyPositions.recompute(
                database.openHelper.writableDatabase,
                dictionary,
                higherIsBetter
            )
        }
    }

    /**
     * Classifies lists installed before this app knew a list could run the
     * other way. Their positions were filled by the migration as if they were
     * ranks, which is what they were read as until then.
     */
    override suspend fun classifyUnknownFrequencyLists() = withContext(Dispatchers.IO) {
        val known = frequencyDao.getListSettings().map { it.dictionary }.toSet()
        val installed = frequencyDao.observeDictionaries().first()
        val unknown = installed.filter { it !in known }
        if (unknown.isEmpty()) return@withContext
        unknown.forEach { classifyFrequencyList(it) }
        reapplyFrequencies()
    }

    override fun observeFrequencyLists(): Flow<List<FrequencyListSetting>> =
        frequencyDao.observeListSettings()

    /**
     * The frequency rollup, once per study language that has anything
     * installed: that language's rows, that language's lists, and the list
     * leading THAT language's order. A list counts words of one language, so
     * a Japanese row must never take a number from an English list — which a
     * single rollup over every list did the moment one was installed.
     *
     * Each pass is one statement over the term table, so a language costs a
     * pass only when it has dictionaries; a Japanese-only install runs
     * exactly the one pass it always ran.
     */
    private suspend fun rollupFrequencies() {
        val strict = if (frequencySettings.strictLeading()) 1 else 0
        // A language needs a pass when it has term rows to stamp — or a list
        // whose numbers are stamped on rows and have to come off when it goes.
        val languages = (dictionaryInfoDao.installedLanguages() + frequencyDao.listLanguages()).distinct()
        for (tag in languages) {
            val language = AppLanguage.entries.firstOrNull { it.entryTag == tag } ?: continue
            val lists = frequencyDao.observeDictionariesFor(tag).first()
            dictionaryDao.applyFrequenciesFromTable(
                language = tag,
                leadingDictionary = frequencySettings.leadingDictionary(language, lists),
                dictionaries = lists,
                strict = strict
            )
        }
    }

    /**
     * Re-rolls `dictionary_entries.frequency` after the user changes which
     * list leads. One statement per language over the whole table, so it runs
     * off the main thread and reports when it is done rather than blocking
     * the screen.
     */
    override suspend fun reapplyFrequencies() = withContext(Dispatchers.IO) {
        rollupFrequencies()
    }

    override suspend fun getFrequencies(expression: String, reading: String): List<WordFrequencyInfo> {
        if (expression.isBlank()) return emptyList()
        return try {
            val counted = frequencyDao.getListSettings()
                .filter { it.higherIsBetter }
                .map { it.dictionary }
                .toSet()
            frequencyDao.getForWord(expression, reading.trim())
                .map {
                    WordFrequencyInfo(
                        dictionary = it.dictionary,
                        rank = it.rank,
                        displayValue = it.displayValue,
                        position = it.position,
                        // A counted list's chip has to say so: "12 345×" is a
                        // number that goes the other way from "#12 345".
                        higherIsBetter = it.dictionary in counted
                    )
                }
        } catch (e: Exception) {
            Log.w(TAG, "getFrequencies failed", e)
            emptyList()
        }
    }

    override suspend fun deleteDictionary(dictionaryName: String) {
        withContext(Dispatchers.IO) {
            val wasFrequencyList = frequencyDao.getListSetting(dictionaryName) != null
            dictionaryDao.deleteByDictionary(dictionaryName)
            kanjiDao.deleteByDictionary(dictionaryName)
            frequencyDao.deleteByDictionary(dictionaryName)
            frequencyDao.deleteListSetting(dictionaryName)
            if (wasFrequencyList) {
                // Its numbers are still stamped on the term rows — and if it
                // led, on every card exported from now on.
                try {
                    reapplyFrequencies()
                } catch (e: Exception) {
                    Log.w(TAG, "Frequency rollup after delete failed", e)
                }
            }
            jlptTagDao.deleteByDictionary(dictionaryName)
            dictionaryInfoDao.deleteByName(dictionaryName)
            try {
                dictionaryDao.rebuildFtsIndex()
            } catch (e: Exception) {
                // Same as on import: without the rebuild the deleted
                // dictionary's terms keep coming back from meaning search.
                Log.w(TAG, "FTS rebuild after delete failed", e)
            }
        }
    }

    override fun getImportedDictionaries(): Flow<List<DictionaryInfo>> {
        return dictionaryInfoDao.getAllDictionaries()
    }

    override suspend fun getEntryCount(): Int {
        return try {
            dictionaryDao.getEntryCount()
        } catch (e: Exception) {
            Log.w(TAG, "getEntryCount failed", e)
            0
        }
    }
}
