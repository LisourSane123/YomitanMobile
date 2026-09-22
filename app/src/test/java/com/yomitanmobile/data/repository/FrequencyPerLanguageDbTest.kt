package com.yomitanmobile.data.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.yomitanmobile.data.local.database.AppDatabase
import com.yomitanmobile.data.local.entity.DictionaryEntry
import com.yomitanmobile.data.local.entity.DictionaryInfo
import com.yomitanmobile.data.local.entity.WordFrequency
import com.yomitanmobile.data.parser.YomitanDictionaryParser
import com.yomitanmobile.data.settings.FrequencySettings
import com.yomitanmobile.data.settings.LanguageSettings
import com.yomitanmobile.domain.model.AppLanguage
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Frequency lists belong to one study language, and so does the order they
 * lead in. With one shared order, installing an English list either left
 * English cards without a number (JPDB leading) or took them off every
 * Japanese card (the English list leading, strict on).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class FrequencyPerLanguageDbTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: DictionaryRepositoryImpl
    private val settings = FrequencySettings(ApplicationProvider.getApplicationContext())

    @Before
    fun setUp() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java
        ).allowMainThreadQueries().build()
        repo = DictionaryRepositoryImpl(
            dictionaryDao = db.dictionaryDao(),
            dictionaryInfoDao = db.dictionaryInfoDao(),
            kanjiDao = db.kanjiDao(),
            frequencyDao = db.frequencyDao(),
            jlptTagDao = db.jlptTagDao(),
            parser = YomitanDictionaryParser(),
            database = db,
            languageSettings = LanguageSettings(ApplicationProvider.getApplicationContext()),
            frequencySettings = settings
        )

        // A Japanese and an English term dictionary, and one list for each.
        db.dictionaryInfoDao().insert(DictionaryInfo(name = "Jitendex", language = "ja"))
        db.dictionaryInfoDao().insert(DictionaryInfo(name = "Wiktionary EN→PL", language = "en"))
        db.dictionaryInfoDao().insert(DictionaryInfo(name = JPDB, language = "ja"))
        db.dictionaryInfoDao().insert(DictionaryInfo(name = WORDFREQ, language = "en"))
        db.dictionaryDao().insertAll(
            listOf(
                DictionaryEntry(expression = "学校", reading = "がっこう", definition = "[]", dictionaryName = "Jitendex", language = "ja"),
                // A Latin-script headword Jitendex lists too: the English list
                // knows the same string, and must still not rank this row.
                DictionaryEntry(expression = "OK", reading = "オーケー", definition = "[]", dictionaryName = "Jitendex", language = "ja"),
                DictionaryEntry(expression = "school", reading = "school", definition = "[]", dictionaryName = "Wiktionary EN→PL", language = "en")
            )
        )
        db.frequencyDao().insertAll(
            listOf(
                WordFrequency("学校", "がっこう", JPDB, 812, "812", position = 812),
                WordFrequency("school", "", WORDFREQ, 530, "530", position = 530),
                WordFrequency("OK", "", WORDFREQ, 90, "90", position = 90)
            )
        )
    }

    @After
    fun tearDown() = db.close()

    private suspend fun entry(expression: String, language: String) =
        db.dictionaryDao().getEntriesByExpressions(listOf(expression), language).single()

    @Test
    fun `each language's rows take the number of their own language's list`() = runBlocking {
        repo.reapplyFrequencies()

        assertEquals("812", entry("学校", "ja").frequencyValue)
        assertEquals("530", entry("school", "en").frequencyValue)
    }

    @Test
    fun `a Japanese row never takes a number from an English list`() = runBlocking {
        repo.reapplyFrequencies()

        val ok = entry("OK", "ja")
        assertEquals("", ok.frequencyValue)
        assertEquals(0, ok.frequency)
    }

    @Test
    fun `the English list leading English does not strip Japanese cards, strict or not`() = runBlocking {
        settings.setStrictLeading(true)
        settings.setOrder(AppLanguage.ENGLISH, listOf(WORDFREQ))
        repo.reapplyFrequencies()

        assertEquals("812", entry("学校", "ja").frequencyValue)
        assertEquals(812, entry("学校", "ja").frequency)
        assertEquals("530", entry("school", "en").frequencyValue)
        settings.setStrictLeading(false)
    }

    @Test
    fun `each language sees only its own lists, and keeps its own order`() = runBlocking {
        val frequencyDao = db.frequencyDao()
        assertEquals(listOf(JPDB), frequencyDao.observeDictionariesFor("ja").first())
        assertEquals(listOf(WORDFREQ), frequencyDao.observeDictionariesFor("en").first())

        settings.setOrder(AppLanguage.JAPANESE, listOf(JPDB))
        settings.setOrder(AppLanguage.ENGLISH, listOf(WORDFREQ))
        assertEquals(JPDB, settings.leadingDictionary(AppLanguage.JAPANESE, listOf(JPDB)))
        assertEquals(WORDFREQ, settings.leadingDictionary(AppLanguage.ENGLISH, listOf(WORDFREQ)))
    }

    private companion object {
        const val JPDB = "JPDB"
        const val WORDFREQ = "wordfreq (EN)"
    }
}
