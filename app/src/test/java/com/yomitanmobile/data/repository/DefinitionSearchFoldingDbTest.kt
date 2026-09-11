package com.yomitanmobile.data.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.yomitanmobile.data.local.database.AppDatabase
import com.yomitanmobile.data.local.entity.DictionaryEntry
import com.yomitanmobile.data.parser.YomitanDictionaryParser
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
import com.yomitanmobile.data.settings.FrequencySettings

/**
 * Meaning-search has to fold case and diacritics, not just ASCII.
 *
 * With FTS4's default `simple` tokenizer this was silently broken for the
 * two languages the app gained: "gad" and "GAD" both matched (ASCII folds),
 * but "żaba" did not match a definition reading "Żaba", and "año" only
 * matched when typed with the tilde. Nobody would report it as a bug —
 * it looks like the word simply isn't in the dictionary.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class DefinitionSearchFoldingDbTest {

    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java
        ).allowMainThreadQueries().build()
        runBlocking {
            db.dictionaryDao().insertAll(
                listOf(
                    entry("frog", """["Żaba zielona"]""", "en"),
                    entry("snake", """["wąż, gad"]""", "en"),
                    entry("year", """["año, rok"]""", "es"),
                    entry("学校", """["school"]""", "ja")
                )
            )
            db.dictionaryDao().rebuildFtsIndex()
        }
    }

    @After
    fun tearDown() = db.close()

    private fun entry(expression: String, definition: String, language: String) =
        DictionaryEntry(
            expression = expression,
            reading = expression,
            definition = definition,
            dictionaryName = "Test",
            language = language
        )

    private fun search(query: String, language: AppLanguage): List<String> = runBlocking {
        val settings = LanguageSettings(ApplicationProvider.getApplicationContext())
        settings.setLanguage(language)
        DictionaryRepositoryImpl(
            dictionaryDao = db.dictionaryDao(),
            dictionaryInfoDao = db.dictionaryInfoDao(),
            kanjiDao = db.kanjiDao(),
            frequencyDao = db.frequencyDao(),
            jlptTagDao = db.jlptTagDao(),
            parser = YomitanDictionaryParser(),
            database = db,
            languageSettings = settings,
            frequencySettings = FrequencySettings(ApplicationProvider.getApplicationContext())
        ).searchByDefinition(query).first().map { it.expression }
    }

    @Test
    fun `a lowercase query matches a capitalised Polish gloss`() {
        assertEquals(listOf("frog"), search("żaba", AppLanguage.ENGLISH))
        assertEquals(listOf("frog"), search("Żaba", AppLanguage.ENGLISH))
    }

    @Test
    fun `a query typed without diacritics still matches`() {
        // Someone on a keyboard without Polish or Spanish layout.
        assertEquals(listOf("frog"), search("zaba", AppLanguage.ENGLISH))
        assertEquals(listOf("snake"), search("waz", AppLanguage.ENGLISH))
        assertEquals(listOf("year"), search("ano", AppLanguage.SPANISH))
    }

    @Test
    fun `ASCII folding still works and stays language-scoped`() {
        assertEquals(listOf("snake"), search("GAD", AppLanguage.ENGLISH))
        // The Japanese row is invisible from English, and vice versa.
        assertEquals(emptyList<String>(), search("school", AppLanguage.ENGLISH))
        assertEquals(listOf("学校"), search("school", AppLanguage.JAPANESE))
    }
}
