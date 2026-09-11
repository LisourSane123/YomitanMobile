package com.yomitanmobile.data.local.dao

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.yomitanmobile.data.local.database.AppDatabase
import com.yomitanmobile.data.local.entity.DictionaryEntry
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
 * `searchCombined` matches its prefix as an index range instead of
 * `LIKE :q || '%'`, because the LIKE form planned as a full table scan on
 * every keystroke. These tests pin the behaviour that rewrite has to preserve:
 * the same rows, the same order, exact matches first — plus the two things the
 * range form changes on purpose (wildcards are literal characters, and the
 * case variants cover what a keyboard capitalises on its own).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PrefixRangeSearchDbTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: DictionaryDao

    private fun entry(
        expression: String,
        reading: String = expression,
        frequency: Int = 0,
        language: String = "ja",
        definition: String = "[\"gloss\"]"
    ) = DictionaryEntry(
        expression = expression,
        reading = reading,
        definition = definition,
        frequency = frequency,
        pitchAccent = "",
        partsOfSpeech = "n",
        dictionaryName = "test",
        sequenceNumber = 0,
        exampleSentence = "",
        exampleSentenceTranslation = "",
        audioFile = "",
        jlptLevel = 0,
        language = language,
        examplesJson = "[]"
    )

    /** Calls the DAO the way the repository does. */
    private suspend fun search(query: String, language: String = "ja"): List<String> {
        val lowered = query.lowercase()
        val titled = lowered.replaceFirstChar { it.uppercaseChar() }
        return dao.searchCombined(
            exactQuery = query,
            prefixStart = query,
            prefixEnd = query + "􏿿",
            lowerPrefixStart = lowered,
            lowerPrefixEnd = lowered + "􏿿",
            titlePrefixStart = titled,
            titlePrefixEnd = titled + "􏿿",
            language = language
        ).first().map { it.expression }
    }

    @Before
    fun setUp() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java
        ).allowMainThreadQueries().build()
        dao = db.dictionaryDao()
        dao.insertAll(
            listOf(
                entry("日本", "にほん", frequency = 10),
                entry("日本語", "にほんご", frequency = 50),
                entry("日本人", "にほんじん", frequency = 900),
                entry("本", "ほん", frequency = 5),
                entry("食欲", "しょくよく", frequency = 4000),
                entry("欲しい", "ほしい", frequency = 300),
                entry("dog", "dog", frequency = 20, language = "en"),
                entry("Dogma", "Dogma", frequency = 8000, language = "en"),
                entry("100%", "100%", language = "en"),
                entry("1000", "1000", language = "en")
            )
        )
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `prefix matches on expression and reading`() = runBlocking {
        assertEquals(listOf("日本", "日本語", "日本人"), search("日本"))
        // Reading side: にほん is not a prefix of any expression here.
        assertEquals(listOf("日本", "日本語", "日本人"), search("にほん"))
    }

    @Test
    fun `an exact match outranks its own prefixes`() = runBlocking {
        // 本 is rarer by rank than nothing else here, but it is the exact hit.
        assertEquals("本", search("本").first())
    }

    @Test
    fun `otherwise the most frequent comes first`() = runBlocking {
        assertEquals(listOf("日本", "日本語", "日本人"), search("に"))
    }

    @Test
    fun `the query stays inside its own language`() = runBlocking {
        assertTrue(search("dog").isEmpty())
        assertEquals(listOf("dog", "Dogma"), search("dog", language = "en"))
    }

    @Test
    fun `an auto-capitalised query still finds the lower-case entry`() = runBlocking {
        // The keyboard capitalises the first letter; the entry is lower case.
        assertEquals(listOf("dog", "Dogma"), search("Dog", language = "en"))
    }

    @Test
    fun `wildcards are literal characters, not patterns`() = runBlocking {
        // Under the old LIKE form these needed escaping or matched everything.
        assertEquals(listOf("100%"), search("100%", language = "en"))
        assertEquals(emptyList<String>(), search("%", language = "en"))
        assertEquals(emptyList<String>(), search("_", language = "en"))
    }

    @Test
    fun `a prefix followed by a non-BMP character is still inside the range`() = runBlocking {
        dao.insertAll(listOf(entry("𠮟", "しかる"), entry("𠮟𠮟", "しかるしかる")))
        assertEquals(listOf("𠮟", "𠮟𠮟"), search("𠮟"))
    }
}
