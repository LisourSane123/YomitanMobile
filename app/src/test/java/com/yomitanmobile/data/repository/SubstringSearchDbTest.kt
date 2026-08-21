package com.yomitanmobile.data.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.yomitanmobile.data.local.database.AppDatabase
import com.yomitanmobile.data.local.entity.DictionaryEntry
import com.yomitanmobile.data.parser.YomitanDictionaryParser
import com.yomitanmobile.domain.usecase.SearchDictionaryUseCase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Substring search against a real (in-memory Room) dictionary.
 *
 * The reported bug: looking up 欲 returned 欲しい but not 食欲, because search
 * was prefix-only. These tests pin both halves of the fix — the compounds now
 * show up, and they still show up AFTER the word itself and its prefix matches.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SubstringSearchDbTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: DictionaryRepositoryImpl
    private lateinit var useCase: SearchDictionaryUseCase

    @Before
    fun setUp() {
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
            database = db
        )
        useCase = SearchDictionaryUseCase(repo)

        insert("欲", "よく", frequency = 500)
        insert("欲しい", "ほしい", frequency = 300)
        insert("欲望", "よくぼう", frequency = 900)
        insert("食欲", "しょくよく", frequency = 800)
        insert("意欲", "いよく", frequency = 700)
        insert("無気力", "むきりょく", frequency = 1000)
    }

    @After
    fun tearDown() = db.close()

    private fun insert(expression: String, reading: String, frequency: Int) = runBlocking {
        db.dictionaryDao().insertAll(
            listOf(
                DictionaryEntry(
                    expression = expression,
                    reading = reading,
                    definition = "[]",
                    frequency = frequency,
                    dictionaryName = "Test"
                )
            )
        )
    }

    private fun search(query: String): List<String> = runBlocking {
        useCase.invoke(query).first().map { it.expression }
    }

    @Test
    fun `single kanji finds the compounds it appears inside`() {
        val results = search("欲")
        assertTrue("食欲 missing from $results", results.contains("食欲"))
        assertTrue("意欲 missing from $results", results.contains("意欲"))
        assertFalse(results.contains("無気力"))
    }

    @Test
    fun `the word itself and its prefix matches still rank above the compounds`() {
        val results = search("欲")
        assertEquals("欲", results.first())
        val lastPrefix = maxOf(results.indexOf("欲しい"), results.indexOf("欲望"))
        val firstCompound = minOf(results.indexOf("食欲"), results.indexOf("意欲"))
        assertTrue("compounds outranked prefix matches: $results", firstCompound > lastPrefix)
    }

    @Test
    fun `substring matching works on readings too`() {
        // しょくよく is only reachable through 食欲's reading.
        assertTrue(search("よく").contains("食欲"))
    }

    @Test
    fun `results are not duplicated by the substring pass`() {
        val results = search("欲")
        assertEquals(results.size, results.distinct().size)
    }

    @Test
    fun `single kana skips the substring scan`() {
        // A one-character kana query would match a large share of a real
        // dictionary; it stays prefix-only on purpose.
        assertFalse(useCase.shouldSearchSubstring("く"))
        assertTrue(useCase.shouldSearchSubstring("欲"))
        assertTrue(useCase.shouldSearchSubstring("よく"))
        assertFalse(useCase.shouldSearchSubstring("ab"))
    }
}
