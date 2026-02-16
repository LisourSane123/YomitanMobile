package com.yomitanmobile.data.repository

import android.util.Log
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

    companion object {
        private const val TAG = "DictionaryRepo"
    }

    override fun search(query: String): Flow<List<WordEntry>> {
        if (query.isBlank()) return flowOf(emptyList())
        // Escape FTS special characters to prevent crashes
        val escaped = query.trim().replace("\"", "\"\"")
        val ftsQuery = "\"$escaped\"*"
        return dictionaryDao.searchFts(ftsQuery)
            .map { entries -> entries.map { it.toDomain() } }
            .catch { e ->
                Log.e(TAG, "FTS search error", e)
                emit(emptyList())
            }
    }

    override fun searchCombined(query: String): Flow<List<WordEntry>> {
        if (query.isBlank()) return flowOf(emptyList())
        return dictionaryDao.searchCombined(query.trim())
            .map { entries -> entries.map { it.toDomain() } }
            .catch { e ->
                Log.e(TAG, "Combined search error", e)
                emit(emptyList())
            }
    }

    override suspend fun getEntry(id: Long): WordEntry? {
        return try {
            dictionaryDao.getById(id)?.toDomain()
        } catch (e: Exception) {
            Log.e(TAG, "Error getting entry $id", e)
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
                    // Apply frequency updates in a single transaction (batch)
                    if (freqMap.isNotEmpty()) {
                        try {
                            dictionaryDao.updateFrequencyBatch(freqMap)
                            totalFreqUpdates += freqMap.size
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to batch update frequency: ${e.message}")
                        }
                    }
                    // Apply pitch accent updates in a single transaction (batch)
                    if (pitchMap.isNotEmpty()) {
                        try {
                            dictionaryDao.updatePitchAccentBatch(pitchMap)
                            totalPitchUpdates += pitchMap.size
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to batch update pitch: ${e.message}")
                        }
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

                // Delete any previous version of this dictionary first
                val existingInfo = dictionaryInfoDao.getByName(dictionaryNameFromBatch)
                if (existingInfo != null) {
                    dictionaryInfoDao.deleteByName(dictionaryNameFromBatch)
                }

                // Rebuild FTS index
                try {
                    dictionaryDao.rebuildFtsIndex()
                } catch (e: Exception) {
                    Log.e(TAG, "Error rebuilding FTS index", e)
                }
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

            Log.i(TAG, "Import complete: $dictionaryNameFromBatch - " +
                "$totalInserted entries, $totalFreqUpdates freq updates, $totalPitchUpdates pitch updates")

            ImportResult(
                success = true,
                dictionaryName = dictionaryNameFromBatch,
                entriesImported = entryCount
            )
        } catch (e: Exception) {
            Log.e(TAG, "Import error", e)
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
            } catch (e: Exception) {
                Log.e(TAG, "Error rebuilding FTS after delete", e)
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
            Log.e(TAG, "Error getting entry count", e)
            0
        }
    }
}
