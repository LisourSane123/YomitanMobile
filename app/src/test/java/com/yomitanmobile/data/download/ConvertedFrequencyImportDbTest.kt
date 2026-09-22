package com.yomitanmobile.data.download

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.yomitanmobile.data.local.database.AppDatabase
import com.yomitanmobile.data.local.entity.DictionaryEntry
import com.yomitanmobile.data.local.entity.DictionaryInfo
import com.yomitanmobile.data.parser.YomitanDictionaryParser
import com.yomitanmobile.data.repository.DictionaryRepositoryImpl
import com.yomitanmobile.data.settings.FrequencySettings
import com.yomitanmobile.data.settings.LanguageSettings
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A converted English list through the app's real import: stored as an
 * English list, rolled onto English rows — capitalised headwords included —
 * and nowhere near the Japanese ones, even when installed while the app was
 * set to Japanese.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ConvertedFrequencyImportDbTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: DictionaryRepositoryImpl

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
            // Default language: Japanese — the list must still land in English.
            languageSettings = LanguageSettings(ApplicationProvider.getApplicationContext()),
            frequencySettings = FrequencySettings(ApplicationProvider.getApplicationContext())
        )
        db.dictionaryInfoDao().insert(DictionaryInfo(name = "Wiktionary EN→PL", language = "en"))
        db.dictionaryInfoDao().insert(DictionaryInfo(name = "Jitendex", language = "ja"))
        db.dictionaryDao().insertAll(
            listOf(
                DictionaryEntry(expression = "school", reading = "school", definition = "[]", dictionaryName = "Wiktionary EN→PL", language = "en"),
                DictionaryEntry(expression = "English", reading = "English", definition = "[]", dictionaryName = "Wiktionary EN→PL", language = "en"),
                DictionaryEntry(expression = "TV", reading = "TV", definition = "[]", dictionaryName = "Wiktionary EN→PL", language = "en"),
                DictionaryEntry(expression = "OK", reading = "オーケー", definition = "[]", dictionaryName = "Jitendex", language = "ja")
            )
        )
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `a converted list imports as English and ranks English headwords only`() = runBlocking {
        val counts = "you 900\nok 800\n't 700\nschool 600\ntv 500\nenglish 400\n"
        val words = FrequencyListConverter.rankedWords(FrequencyListConverter.Format.SUBTITLE_COUNTS, counts.byteInputStream())
        val zip = FrequencyListConverter.toYomitanZip("OpenSubtitles (EN)", "test", "en", "CC BY-SA 4.0", words)

        val result = repo.importDictionary(zip.inputStream(), onProgress = {})
        assertTrue(result.errorMessage ?: "", result.success)

        assertEquals(listOf("OpenSubtitles (EN)"), db.frequencyDao().observeDictionariesFor("en").first())
        assertEquals(emptyList<String>(), db.frequencyDao().observeDictionariesFor("ja").first())

        fun en(word: String) = runBlocking { db.dictionaryDao().getEntriesByExpressions(listOf(word), "en").single() }
        // you=1, ok=2, school=3, tv=4, english=5 — "'t" dropped, ranks renumbered.
        assertEquals("3", en("school").frequencyValue)
        assertEquals("4", en("TV").frequencyValue)
        assertEquals("5", en("English").frequencyValue)
        // The Japanese OK is not an English word, whatever the list says.
        val ok = db.dictionaryDao().getEntriesByExpressions(listOf("OK"), "ja").single()
        assertEquals("", ok.frequencyValue)
    }
}
