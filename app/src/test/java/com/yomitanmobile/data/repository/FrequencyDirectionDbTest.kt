package com.yomitanmobile.data.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.yomitanmobile.data.local.database.AppDatabase
import com.yomitanmobile.data.local.entity.DictionaryEntry
import com.yomitanmobile.data.local.entity.WordFrequency
import com.yomitanmobile.data.parser.YomitanDictionaryParser
import com.yomitanmobile.data.settings.FrequencySettings
import com.yomitanmobile.data.settings.LanguageSettings
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
 * A frequency list built from occurrence counts, against a real database.
 *
 * The app reads `rank` as "lower is better" everywhere — search order, the
 * number on a card, the rarity cut in both deck generators — so a counted list
 * has to be converted into ranks on the way in. These tests pin the conversion
 * and the fact that it is reversible, because the detector can be wrong and
 * the user's override has to be able to undo it exactly.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class FrequencyDirectionDbTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: DictionaryRepositoryImpl

    private val listName = "Anime word counts"

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
            database = db,
            languageSettings = LanguageSettings(ApplicationProvider.getApplicationContext()),
            frequencySettings = FrequencySettings(ApplicationProvider.getApplicationContext())
        )
    }

    @After
    fun tearDown() = db.close()

    /**
     * 60 words whose counts run 1 000, 2 000 … 60 000 — the commonest word is
     * the one seen 60 000 times, which is the opposite of what the column
     * means everywhere else in the app.
     */
    private fun insertCountedList() = runBlocking {
        val rows = (1..60).map { i ->
            val count = i * 1_000
            WordFrequency(
                expression = "語$i",
                reading = "ご$i",
                dictionary = listName,
                rank = count,
                displayValue = count.toString()
            )
        }
        db.frequencyDao().insertAll(rows)
        db.dictionaryDao().insertAll(
            rows.map {
                DictionaryEntry(
                    expression = it.expression,
                    reading = it.reading,
                    definition = "[]",
                    dictionaryName = "Test"
                )
            }
        )
    }

    @Test
    fun `a counted list becomes ranks, commonest word first`() = runBlocking {
        insertCountedList()

        repo.classifyUnknownFrequencyLists()

        // Seen 60 000 times — the commonest word in the list, so rank 1.
        val commonest = repo.getFrequencies("語60", "ご60").single()
        assertEquals(1, commonest.rank)
        // …and it still SAYS 60 000, because that is what the list shipped and
        // what the user recognises; only the sort key was derived from it.
        assertEquals("60000", commonest.displayValue)
        assertTrue(commonest.higherIsBetter)
        assertEquals("$listName 60000×", commonest.label())

        val rarest = repo.getFrequencies("語1", "ご1").single()
        assertEquals(60, rarest.rank)

        // The rollup that feeds search order and the card ran too.
        assertEquals(
            1,
            db.dictionaryDao().getEntriesByExpressions(listOf("語60"), "ja").single().frequency
        )
    }

    @Test
    fun `the user can say it was ranks after all, and nothing is lost`() = runBlocking {
        insertCountedList()
        repo.classifyUnknownFrequencyLists()

        repo.setFrequencyListDirection(listName, higherIsBetter = false)

        // Back to the numbers the file shipped, computed from the display
        // value rather than from anything the conversion kept.
        val restored = repo.getFrequencies("語60", "ご60").single()
        assertEquals(60_000, restored.rank)
        assertEquals("$listName #60000", restored.label())
    }

    @Test
    fun `a rank list is left exactly as it is`() = runBlocking {
        val rows = (1..60).map { i ->
            WordFrequency("単語$i", "たんご$i", "JPDBv2", i, i.toString())
        }
        db.frequencyDao().insertAll(rows)

        repo.classifyUnknownFrequencyLists()

        val first = repo.getFrequencies("単語1", "たんご1").single()
        assertEquals(1, first.rank)
        assertEquals("JPDBv2 #1", first.label())
    }
}
