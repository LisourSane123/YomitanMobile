package com.yomitanmobile.data.repository

import com.yomitanmobile.data.local.dao.DictionaryDao
import com.yomitanmobile.data.local.dao.DictionaryInfoDao
import com.yomitanmobile.data.local.entity.DictionaryEntry
import com.yomitanmobile.data.local.entity.DictionaryInfo
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
    private val parser: YomitanDictionaryParser
) : DictionaryRepository {

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

    override suspend fun getEntry(id: Long): WordEntry? {
        return try {
            dictionaryDao.getById(id)?.toDomain()
        } catch (_: Exception) {
            null
        }
    }

    override suspend fun importDictionary(
        inputStream: InputStream,
        onProgress: (ImportProgress) -> Unit
    ): ImportResult = withContext(Dispatchers.IO) {
        try {
            var totalInserted = 0
            var dictionaryNameFromBatch: String
            var totalFreqUpdates = 0
            var totalPitchUpdates = 0

            // Use streaming parser — entries are inserted in batches as they're parsed
            val parseResult = parser.parseFromZipStreaming(
                inputStream = inputStream,
                onProgress = onProgress,
                onBatch = { batch, _ ->
                    val batchSize = 500
                    batch.chunked(batchSize).forEach { chunk ->
                        dictionaryDao.insertAll(chunk)
                    }
                    totalInserted += batch.size
                },
                onMetaBatch = { freqMap, pitchMap ->
                    if (freqMap.isNotEmpty()) {
                        dictionaryDao.updateFrequencyBatch(freqMap)
                        totalFreqUpdates += freqMap.size
                    }
                    if (pitchMap.isNotEmpty()) {
                        dictionaryDao.updatePitchAccentBatch(pitchMap)
                        totalPitchUpdates += pitchMap.size
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
                totalFreqUpdates + totalPitchUpdates
            } else {
                totalInserted
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

    override suspend fun deleteDictionary(dictionaryName: String) {
        withContext(Dispatchers.IO) {
            dictionaryDao.deleteByDictionary(dictionaryName)
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
