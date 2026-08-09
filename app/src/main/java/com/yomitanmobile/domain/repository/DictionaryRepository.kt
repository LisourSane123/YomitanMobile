package com.yomitanmobile.domain.repository

import com.yomitanmobile.data.local.entity.DictionaryInfo
import com.yomitanmobile.data.local.entity.KanjiEntry
import com.yomitanmobile.domain.model.ImportProgress
import com.yomitanmobile.domain.model.ImportResult
import com.yomitanmobile.domain.model.WordEntry
import com.yomitanmobile.domain.model.WordFrequencyInfo
import kotlinx.coroutines.flow.Flow
import java.io.InputStream

interface DictionaryRepository {
    fun searchExact(query: String): Flow<List<WordEntry>>
    fun searchCombined(query: String): Flow<List<WordEntry>>
    fun searchByDefinition(query: String): Flow<List<WordEntry>>
    suspend fun getEntry(id: Long): WordEntry?
    suspend fun getEntriesByReading(reading: String): List<WordEntry>
    suspend fun getKanjis(kanjiList: List<String>): List<KanjiEntry>

    /**
     * Best (frequency-ranked) reading for each of [expressions], for
     * synthesising furigana on example sentences that lack ruby data.
     * Missing expressions are simply absent from the returned map.
     */
    suspend fun getReadingsForExpressions(expressions: List<String>): Map<String, String>

    /**
     * Every entry carrying the given JLPT level tag (5 = N5 … 1 = N1).
     * Source for the bulk JLPT deck generator.
     */
    suspend fun getEntriesByJlptLevel(level: Int): List<WordEntry>

    /**
     * Exact-expression batch lookup, chunked internally against SQLite's
     * variable limit. Used to resolve the built-in JLPT word list against
     * whatever dictionaries the user actually has installed.
     */
    suspend fun getEntriesForExpressions(expressions: List<String>): List<WordEntry>

    /**
     * Every written form and reading in the installed dictionaries, as one
     * set. This is the lexicon the text scanner segments Japanese text
     * against — see `DictionaryDao.getAllExpressions` for why it has to be
     * held in memory rather than queried per candidate.
     */
    suspend fun getSurfaceLexicon(): Set<String>

    /**
     * Batch reading lookup, chunked like [getEntriesForExpressions]. Resolves
     * the words a text spells in kana only (みる, ある) to dictionary entries.
     */
    suspend fun getEntriesForReadings(readings: List<String>): List<WordEntry>

    /**
     * Same lookup restricted to one installed dictionary. Used by the
     * monolingual (JP-JP) card engine, which must read the definition from the
     * dictionary the user picked, not from whichever one matched first.
     */
    suspend fun getEntriesForExpressionsFromDictionary(
        expressions: List<String>,
        dictionaryName: String
    ): List<WordEntry>

    /** Every installed list's rank for a word, for multi-list display. */
    suspend fun getFrequencies(expression: String, reading: String): List<WordFrequencyInfo>
    suspend fun importDictionary(
        inputStream: InputStream,
        onProgress: (ImportProgress) -> Unit = {}
    ): ImportResult
    suspend fun deleteDictionary(dictionaryName: String)
    fun getImportedDictionaries(): Flow<List<DictionaryInfo>>
    suspend fun getEntryCount(): Int
}
