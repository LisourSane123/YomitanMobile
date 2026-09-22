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
 * Frequency lists of both directions against a real database.
 *
 * The list's numbers stay exactly as shipped — on the chips, in the stored
 * table and on the card. Only `position` (1 = commonest) is derived, and only
 * the leading list's number reaches `dictionary_entries.frequency_value`.
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
    fun `a counted list keeps its numbers and gets positions, commonest first`() = runBlocking {
        insertCountedList()

        repo.classifyUnknownFrequencyLists()

        val commonest = repo.getFrequencies("語60", "ご60").single()
        // Untouched: the list said 60 000, and so does every copy of it.
        assertEquals(60_000, commonest.rank)
        assertEquals("60000", commonest.displayValue)
        assertTrue(commonest.higherIsBetter)
        assertEquals("$listName 60000×", commonest.label())
        // Derived, for ordering and tiers only.
        assertEquals(1, commonest.position)
        assertEquals(60, repo.getFrequencies("語1", "ご1").single().position)
    }

    @Test
    fun `the leading list's own number lands on the entry, its position orders it`() = runBlocking {
        insertCountedList()
        FrequencySettings(ApplicationProvider.getApplicationContext()).setOrder(com.yomitanmobile.domain.model.AppLanguage.JAPANESE, listOf(listName))

        repo.classifyUnknownFrequencyLists()
        repo.reapplyFrequencies()

        val entry = db.dictionaryDao().getEntriesByExpressions(listOf("語60"), "ja").single()
        assertEquals("60000", entry.frequencyValue)
        assertEquals(1, entry.frequency)
    }

    @Test
    fun `a word the leading list does not know carries no leading number`() = runBlocking {
        insertCountedList()
        db.frequencyDao().insertAll(listOf(WordFrequency("語1", "ご1", "JPDBv2", 7, "7")))
        FrequencySettings(ApplicationProvider.getApplicationContext()).setOrder(com.yomitanmobile.domain.model.AppLanguage.JAPANESE, listOf("JPDBv2", listName))

        repo.classifyUnknownFrequencyLists()
        repo.reapplyFrequencies()

        val known = db.dictionaryDao().getEntriesByExpressions(listOf("語1"), "ja").single()
        assertEquals("7", known.frequencyValue)
        // Not strict: ordering may borrow the other list's standing, but the
        // card number and the tier do not.
        val unknown = db.dictionaryDao().getEntriesByExpressions(listOf("語60"), "ja").single()
        assertEquals("", unknown.frequencyValue)
        assertEquals(1, unknown.frequency)
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
        assertEquals(1, first.position)
        assertEquals("JPDBv2 #1", first.label())
    }
}
