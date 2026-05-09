package com.yomitanmobile.data.repository

import com.yomitanmobile.data.local.dao.DictionaryDao
import com.yomitanmobile.data.local.dao.DictionaryInfoDao
import com.yomitanmobile.data.local.dao.KanjiDao
import com.yomitanmobile.data.local.database.AppDatabase
import com.yomitanmobile.data.local.entity.DictionaryEntry
import com.yomitanmobile.data.local.entity.DictionaryInfo
import com.yomitanmobile.data.local.entity.KanjiEntry
import com.yomitanmobile.data.mapper.toDomain
import com.yomitanmobile.data.parser.YomitanDictionaryParser
import com.yomitanmobile.domain.model.ImportProgress
import com.yomitanmobile.domain.model.ImportResult
import com.yomitanmobile.domain.model.WordEntry
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
    private val parser: YomitanDictionaryParser,
    private val database: AppDatabase
) : DictionaryRepository {

    /**
     * SQLite tuning that's only safe during the import itself: synchronous=OFF
     * skips fsync per transaction, journal_mode=MEMORY keeps the rollback
     * journal in RAM. PRAGMA isn't expressible through Room @Query (it
     * returns no rows), so we go through the support helper directly. Each
     * helper restores the durable defaults afterwards in a finally.
     */
    private fun beginBulkImport() {
        runCatching {
            val db = database.openHelper.writableDatabase
            db.execSQL("PRAGMA synchronous = OFF")
            db.execSQL("PRAGMA journal_mode = MEMORY")
        }
    }

    private fun endBulkImport() {
        runCatching {
            val db = database.openHelper.writableDatabase
            db.execSQL("PRAGMA synchronous = NORMAL")
            db.execSQL("PRAGMA journal_mode = WAL")
        }
    }

    override suspend fun getKanjis(kanjiList: List<String>): List<KanjiEntry> {
        return kanjiDao.getKanjis(kanjiList)
    }

    override fun search(query: String): Flow<List<WordEntry>> {
        if (query.isBlank()) return flowOf(emptyList())
        val ftsQuery = InputSanitizer.sanitizeFtsQuery(query)
        if (ftsQuery.isBlank()) return flowOf(emptyList())
        return dictionaryDao.searchFts(ftsQuery)
            .map { entries -> entries.map { it.toDomain() } }
            .catch { _ ->
                emit(emptyList())
            }
    }

    override fun searchCombined(query: String): Flow<List<WordEntry>> {
        if (query.isBlank()) return flowOf(emptyList())
        return dictionaryDao.searchCombined(query.trim())
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
        // Drop synchronous=OFF + journal=MEMORY for the duration of the
        // import. Cuts fsync per transaction and avoids WAL bookkeeping
        // during what is effectively a one-shot bulk load. Restored to safer
        // settings in the finally block — cards added afterwards still
        // hit a durable journal_mode=WAL.
        beginBulkImport()

        try {
            var totalInserted = 0
            var totalKanjiInserted = 0
            var dictionaryNameFromBatch: String
            var totalFreqUpdates = 0
            var totalPitchUpdates = 0
            var totalJlptUpdates = 0

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
                        dictionaryDao.updateFrequencyBatch(freqUpdates)
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

            // For meta-only dictionaries (frequency/pitch), we don't insert term entries
            // — the meta data was already applied to existing entries via onMetaBatch
            if (!parseResult.isMetaDictionary) {
                // Update entries that were inserted with "temp" dictionary name
                // to the actual dictionary name from index.json
                if (dictionaryNameFromBatch != "temp") {
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
        } finally {
            // Always restore durable settings before the user starts adding
            // search history / favorites / Anki exports — those rows must not
            // be at risk of disappearing on a power cycle.
            endBulkImport()
        }
    }

    override suspend fun deleteDictionary(dictionaryName: String) {
        withContext(Dispatchers.IO) {
            dictionaryDao.deleteByDictionary(dictionaryName)
            kanjiDao.deleteByDictionary(dictionaryName)
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
