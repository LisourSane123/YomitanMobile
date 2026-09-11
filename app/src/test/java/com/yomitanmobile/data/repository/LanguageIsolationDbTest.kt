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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The guarantee that makes one database safe for two languages: a search
 * never sees rows belonging to the language the user is not studying.
 *
 * This is the regression that would be least visible if it broke — Japanese
 * results would still appear, just with English words mixed in (or, worse,
 * the Japanese half would silently vanish after the migration defaulted its
 * rows to the wrong tag).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class LanguageIsolationDbTest {

    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java
        ).allowMainThreadQueries().build()

        // uk: the dictionary says this one is usually written in kana, which
        // is what puts its reading in the scanner's lexicon.
        insert("欲しい", "ほしい", "ja", frequency = 300, partsOfSpeech = "adj-i, uk")
        insert("学校", "がっこう", "ja", frequency = 100)
        insert("school", "school", "en", frequency = 100)
        insert("want", "want", "en", frequency = 300)
        insert("escuela", "escuela", "es", frequency = 100)
        insert("hablar", "hablar", "es", frequency = 300)
    }

    @After
    fun tearDown() = db.close()

    private fun repoFor(language: AppLanguage): DictionaryRepositoryImpl {
        val settings = LanguageSettings(ApplicationProvider.getApplicationContext())
        runBlocking { settings.setLanguage(language) }
        return DictionaryRepositoryImpl(
            dictionaryDao = db.dictionaryDao(),
            dictionaryInfoDao = db.dictionaryInfoDao(),
            kanjiDao = db.kanjiDao(),
            frequencyDao = db.frequencyDao(),
            jlptTagDao = db.jlptTagDao(),
            parser = YomitanDictionaryParser(),
            database = db,
            languageSettings = settings
        )
    }

    private fun insert(
        expression: String,
        reading: String,
        language: String,
        frequency: Int,
        partsOfSpeech: String = ""
    ) = runBlocking {
        db.dictionaryDao().insertAll(
            listOf(
                DictionaryEntry(
                    expression = expression,
                    reading = reading,
                    definition = "[]",
                    frequency = frequency,
                    partsOfSpeech = partsOfSpeech,
                    dictionaryName = "Test",
                    language = language
                )
            )
        )
    }

    @Test
    fun `japanese search returns japanese rows and nothing else`() = runBlocking {
        val repo = repoFor(AppLanguage.JAPANESE)
        val results = repo.searchCombined("学校").first().map { it.expression }
        assertEquals(listOf("学校"), results)
    }

    @Test
    fun `english rows are invisible while studying japanese`() = runBlocking {
        val repo = repoFor(AppLanguage.JAPANESE)
        assertTrue(repo.searchCombined("school").first().isEmpty())
        assertTrue(repo.searchExact("want").first().isEmpty())
    }

    @Test
    fun `japanese rows are invisible while studying english`() = runBlocking {
        val repo = repoFor(AppLanguage.ENGLISH)
        assertTrue(repo.searchCombined("学校").first().isEmpty())
        assertEquals(
            listOf("school"),
            repo.searchCombined("school").first().map { it.expression }
        )
    }

    @Test
    fun `each language sees only its own rows`() = runBlocking {
        // Three languages in one table: the pair that would be easiest to
        // confuse is English and Spanish, since both are Latin script and
        // nothing but the column separates them.
        assertEquals(
            listOf("escuela"),
            repoFor(AppLanguage.SPANISH).searchCombined("escuela").first().map { it.expression }
        )
        assertTrue(repoFor(AppLanguage.SPANISH).searchCombined("school").first().isEmpty())
        assertTrue(repoFor(AppLanguage.ENGLISH).searchCombined("escuela").first().isEmpty())
    }

    @Test
    fun `the scanner lexicon is scoped to the active language too`() = runBlocking {
        // getSurfaceLexicon feeds segmentation; an unscoped one would make a
        // Japanese scan match English words and vice versa.
        // 欲しい is tagged uk in the fixture, so its reading is a spelling and
        // belongs in the lexicon; がっこう is only how 学校 is pronounced and
        // must stay out — see DictionaryDao.getKanaWrittenReadings.
        assertEquals(setOf("欲しい", "ほしい", "学校"), repoFor(AppLanguage.JAPANESE).getSurfaceLexicon())
        assertEquals(setOf("school", "want"), repoFor(AppLanguage.ENGLISH).getSurfaceLexicon())
        assertEquals(setOf("escuela", "hablar"), repoFor(AppLanguage.SPANISH).getSurfaceLexicon())
    }

    @Test
    fun `a row written without a language defaults to japanese`() = runBlocking {
        // Mirrors migration 16→17, which stamps every pre-existing row 'ja'.
        // If this default ever disagreed with AppLanguage.DEFAULT, upgrading
        // users would open the app to an empty dictionary.
        db.dictionaryDao().insertAll(
            listOf(
                DictionaryEntry(
                    expression = "犬",
                    reading = "いぬ",
                    definition = "[]",
                    dictionaryName = "Test"
                )
            )
        )
        val results = repoFor(AppLanguage.DEFAULT).searchExact("犬").first()
        assertEquals(1, results.size)
    }
}
