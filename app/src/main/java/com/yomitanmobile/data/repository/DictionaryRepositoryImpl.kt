package com.yomitanmobile.data.repository

import com.yomitanmobile.data.local.dao.DictionaryDao
import com.yomitanmobile.data.local.dao.DictionaryInfoDao
import com.yomitanmobile.data.local.dao.FrequencyDao
import com.yomitanmobile.data.local.dao.KanjiDao
import com.yomitanmobile.data.local.database.AppDatabase
import com.yomitanmobile.data.local.entity.DictionaryEntry
import com.yomitanmobile.data.local.entity.DictionaryInfo
import com.yomitanmobile.data.local.entity.KanjiEntry
import com.yomitanmobile.data.local.entity.WordFrequency
import com.yomitanmobile.data.mapper.toDomain
import com.yomitanmobile.data.parser.YomitanDictionaryParser
import com.yomitanmobile.domain.model.ImportProgress
import com.yomitanmobile.domain.model.ImportResult
import com.yomitanmobile.domain.model.WordEntry
import com.yomitanmobile.domain.model.WordFrequencyInfo
import com.yomitanmobile.domain.repository.DictionaryRepository
import com.yomitanmobile.util.InputSanitizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
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
    private val parser: YomitanDictionaryParser,
    private val database: AppDatabase
) : DictionaryRepository {

    // Matches YomitanDictionaryParser's placeholder name: term/kanji/frequency
    // rows are written under this and renamed to the real index.json title
    // once parsing finishes (the title isn't reliably known mid-stream).
    private val tempDictionaryName = "temp"

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

    override fun searchExact(query: String): Flow<List<WordEntry>> {
        if (query.isBlank()) return flowOf(emptyList())
        val trimmed = query.trim()
        return dictionaryDao.searchExact(trimmed)
            .map { entries -> entries.map { it.toDomain() } }
            .catch { _ ->
                emit(emptyList())
            }
    }

    override fun searchCombined(query: String): Flow<List<WordEntry>> {
        if (query.isBlank()) return flowOf(emptyList())
        val trimmed = query.trim()
        // Exact equality uses the raw trimmed input; the LIKE-side gets a
        // copy with SQL wildcards (% _ \) escaped, paired with `ESCAPE '\'`
        // in the DAO query. Without this, a user typing `%` matched every
        // entry and `_` matched any single character.
        val likeQuery = InputSanitizer.sanitizeLikeQuery(trimmed)
        return dictionaryDao.searchCombined(trimmed, likeQuery)
            .map { entries -> entries.map { it.toDomain() } }
            .catch { _ ->
                emit(emptyList())
            }
    }

    override fun searchByDefinition(query: String): Flow<List<WordEntry>> {
        if (query.isBlank()) return flowOf(emptyList())
        val ftsQuery = InputSanitizer.sanitizeFtsQuery(query)
        if (ftsQuery.isBlank()) return flowOf(emptyList())
        return dictionaryDao.searchByDefinition(ftsQuery)
            .map { entries -> entries.map { it.toDomain() } }
            .catch { _ ->
                emit(emptyList())
            }
    }

    override suspend fun getEntry(id: Long): WordEntry? {
        return try {
            dictionaryDao.getById(id)?.toDomain()
        } catch (_: Exception) {
            null
        }
    }

    override suspend fun getEntriesByReading(reading: String): List<WordEntry> {
        return try {
            dictionaryDao.getByReading(reading).map { it.toDomain() }
        } catch (_: Exception) {
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

            // Clear any frequency rows left under the temp name by a previous
            // interrupted import before we start writing this one.
            frequencyDao.deleteByDictionary(tempDictionaryName)

            // Use streaming parser — entries are inserted in batches as they're parsed.
            // 10000 fits comfortably in one transaction and roughly halves the
            // transaction-commit overhead vs. 5000.
            val batchSize = 10000
            val parseResult = parser.parseFromZipStreaming(
                inputStream = inputStream,
                onProgress = onProgress,
                onBatch = { batch, _ ->
                    batch.chunked(batchSize).forEach { chunk ->
                        dictionaryDao.insertAll(chunk)
                    }
                    totalInserted += batch.size
                },
                onMetaBatch = { freqUpdates, pitchMap ->
                    if (freqUpdates.isNotEmpty()) {
                        // Best-rank rollup into dictionary_entries.frequency (search ordering)…
                        dictionaryDao.updateFrequencyBatch(freqUpdates)
                        // …plus the per-source row keeping THIS list's rank for
                        // multi-list display. Written under the temp name and
                        // renamed once we know the index.json title.
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
                        dictionaryDao.updatePitchAccentBatch(pitchMap)
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
                        dictionaryDao.updateJlptLevelBatch(jlptUpdates)
                        totalJlptUpdates += jlptUpdates.size
                    }
                }
            )

            dictionaryNameFromBatch = parseResult.dictionaryName

            // Promote frequency rows from the temp name to the real list title.
            // Runs for meta AND term dicts (a term dict can carry its own freq
            // meta banks). Drop any prior import of the same list first so a
            // re-import replaces rather than duplicates.
            if (dictionaryNameFromBatch != tempDictionaryName) {
                frequencyDao.deleteByDictionary(dictionaryNameFromBatch)
                frequencyDao.updateDictionaryName(tempDictionaryName, dictionaryNameFromBatch)
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
                    dictionaryDao.deleteByDictionary(dictionaryNameFromBatch)
                    kanjiDao.deleteByDictionary(dictionaryNameFromBatch)
                    dictionaryDao.updateDictionaryName("temp", dictionaryNameFromBatch)
                    kanjiDao.updateDictionaryName("temp", dictionaryNameFromBatch)
                }

                try {
                    dictionaryDao.rebuildFtsIndex()
                } catch (_: Exception) { /* FTS rebuild error */ }
            }

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
                    entryCount = entryCount
                )
            )

            ImportResult(
                success = true,
                dictionaryName = dictionaryNameFromBatch,
                entriesImported = entryCount
            )
        } catch (e: Exception) {
            ImportResult(
                success = false,
                dictionaryName = "Unknown",
                entriesImported = 0,
                errorMessage = e.message ?: "Unknown error during import"
            )
        }
    }

    override suspend fun getFrequencies(expression: String, reading: String): List<WordFrequencyInfo> {
        if (expression.isBlank()) return emptyList()
        return try {
            frequencyDao.getForWord(expression, reading.trim())
                .map { WordFrequencyInfo(it.dictionary, it.rank, it.displayValue) }
        } catch (_: Exception) {
            emptyList()
        }
    }

    override suspend fun deleteDictionary(dictionaryName: String) {
        withContext(Dispatchers.IO) {
            dictionaryDao.deleteByDictionary(dictionaryName)
            kanjiDao.deleteByDictionary(dictionaryName)
            frequencyDao.deleteByDictionary(dictionaryName)
            dictionaryInfoDao.deleteByName(dictionaryName)
            try {
                dictionaryDao.rebuildFtsIndex()
            } catch (_: Exception) { /* FTS rebuild error */ }
        }
    }

    override fun getImportedDictionaries(): Flow<List<DictionaryInfo>> {
        return dictionaryInfoDao.getAllDictionaries()
    }

    override suspend fun getEntryCount(): Int {
        return try {
            dictionaryDao.getEntryCount()
        } catch (_: Exception) {
            0
        }
    }
}
