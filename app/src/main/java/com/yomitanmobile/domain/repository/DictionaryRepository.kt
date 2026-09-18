package com.yomitanmobile.domain.repository

import com.yomitanmobile.data.local.entity.DictionaryInfo
import com.yomitanmobile.data.local.entity.FrequencyListSetting
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

    /**
     * Entries containing the query somewhere other than at the start — the
     * compounds (食欲 for 欲) that the prefix-based [searchCombined] misses.
     * Disjoint from [searchCombined] by construction, so callers can simply
     * append the results.
     */
    fun searchContains(query: String): Flow<List<WordEntry>>
    fun searchByDefinition(query: String): Flow<List<WordEntry>>
    suspend fun getEntry(id: Long): WordEntry?
    suspend fun getEntriesByReading(reading: String): List<WordEntry>
    suspend fun getKanjis(kanjiList: List<String>): List<KanjiEntry>

    /** One character's full row, or null when no installed dictionary has it. */
    suspend fun getKanji(kanji: String): KanjiEntry?

    /** Kanji in a grade / JLPT bucket (0 = any), commonest first. */
    suspend fun listKanji(grade: Int = 0, jlpt: Int = 0): List<KanjiEntry>

    suspend fun kanjiCountsByGrade(): List<com.yomitanmobile.data.local.dao.KanjiBucket>

    suspend fun kanjiCountsByJlpt(): List<com.yomitanmobile.data.local.dao.KanjiBucket>

    /** Words written with this character, commonest first. */
    suspend fun wordsContainingKanji(kanji: String, limit: Int = 60): List<WordEntry>

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
     * The forms a word is actually WRITTEN in: every expression, plus the
     * readings of words the dictionary says are written in kana. This is the
     * lexicon the text scanner segments Japanese text against — see
     * `DictionaryDao.getAllExpressions` for why it has to be held in memory
     * rather than queried per candidate, and
     * `DictionaryDao.getKanaWrittenReadings` for why plain readings are not in
     * it.
     */
    suspend fun getSurfaceLexicon(): Set<String>

    /**
     * The surfaces the installed frequency lists rank inside [maxRank], each
     * with its rank. The scanner prefers a common reading of an ambiguous
     * stretch over a rare one, and where two readings are both common the
     * numbers decide — あった is ある (15), not あう (172). Empty when no
     * frequency dictionary is installed, which simply turns the preference
     * off.
     */
    suspend fun getCommonSurfaces(maxRank: Int): Map<String, Int>

    /**
     * Re-computes the frequency stamped on every entry — the number that goes
     * on a card, orders search results and drives the rarity filters. Called
     * after an import and whenever the user changes which frequency list
     * leads.
     */
    suspend fun reapplyFrequencies()

    /**
     * Which way each installed frequency list's numbers run — ranks (lower =
     * commoner) or occurrence counts (higher = commoner). See [FrequencyListSetting].
     */
    fun observeFrequencyLists(): Flow<List<FrequencyListSetting>>

    /**
     * Classifies lists installed before this app knew a list could run the
     * other way. No-op once every installed list has an answer.
     */
    suspend fun classifyUnknownFrequencyLists()

    /**
     * Batch reading lookup, chunked like [getEntriesForExpressions]. Resolves
     * the words a text spells in kana only (みる, ある) to dictionary entries.
     */
    suspend fun getEntriesForReadings(readings: List<String>): List<WordEntry>

    /**
     * Every written form of a word, keyed by its JMdict sequence.
     *
     * The duplicate check against the AnkiDroid collection asks "do I already
     * have THIS WORD", and a word is not one string: JMdict files 傷つく,
     * 傷付く and 疵つく as one entry, 綺麗 and 奇麗 as another. The deck holds
     * whichever one its author typed, and comparing headword to headword
     * reported "you do not have it" for words the reader had been studying for
     * months — which is what put them back in a generated deck.
     *
     * `MergedWordEntry.alternativeExpressions` cannot answer this and never
     * could: `mergeEntries` groups by (expression, reading), so a group holds
     * exactly one spelling and the list is always empty. The spellings are a
     * fact about the database, so they are read from it.
     *
     * Looked up by READING, because `reading` is indexed and `sequence_number`
     * is not — then grouped by sequence, which is what keeps homophones apart.
     * こうえん matches 公園 and 講演 on the reading; they carry different
     * sequences, so owning one never marks the other as known.
     *
     * Entries with no sequence (a dictionary that ships none) are left out
     * rather than guessed at: the caller then compares the headword alone,
     * exactly as before.
     */
    suspend fun writtenFormsBySequence(readings: Collection<String>): Map<Int, List<String>>

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
