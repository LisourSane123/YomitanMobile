package com.yomitanmobile.domain.repository

import com.yomitanmobile.data.local.entity.DictionaryInfo
import com.yomitanmobile.domain.model.ImportProgress
import com.yomitanmobile.domain.model.ImportResult
import com.yomitanmobile.domain.model.WordEntry
import kotlinx.coroutines.flow.Flow
import java.io.InputStream

interface DictionaryRepository {
    fun search(query: String): Flow<List<WordEntry>>
    fun searchCombined(query: String): Flow<List<WordEntry>>
    suspend fun getEntry(id: Long): WordEntry?
    suspend fun getEntriesByReading(reading: String): List<WordEntry>
    suspend fun importDictionary(
        inputStream: InputStream,
        onProgress: (ImportProgress) -> Unit = {}
    ): ImportResult
    suspend fun deleteDictionary(dictionaryName: String)
    fun getImportedDictionaries(): Flow<List<DictionaryInfo>>
    suspend fun getEntryCount(): Int
}
