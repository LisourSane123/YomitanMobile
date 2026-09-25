package com.yomitanmobile.data.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.yomitanmobile.data.local.database.AppDatabase
import com.yomitanmobile.data.local.entity.DictionaryEntry
import com.yomitanmobile.data.parser.YomitanDictionaryParser
import com.yomitanmobile.data.settings.FrequencySettings
import com.yomitanmobile.data.settings.LanguageSettings
import com.yomitanmobile.domain.usecase.SearchDictionaryUseCase
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
 * The deconjugation candidates of one search go to the database in a SINGLE
 * query now. This pins that the words still come back — and in the order the
 * merge promises: what the user typed first, the base forms after it, ranked by
 * how common they are rather than by the order the rules produced them.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SearchAlternativesDbTest {

    private lateinit var db: AppDatabase
    private lateinit var useCase: SearchDictionaryUseCase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val repo = DictionaryRepositoryImpl(
            dictionaryDao = db.dictionaryDao(),
            dictionaryInfoDao = db.dictionaryInfoDao(),
            kanjiDao = db.kanjiDao(),
            frequencyDao = db.frequencyDao(),
            jlptTagDao = db.jlptTagDao(),
            parser = YomitanDictionaryParser(),
            database = db,
            languageSettings = LanguageSettings(context),
            frequencySettings = FrequencySettings(context)
        )
        useCase = SearchDictionaryUseCase(repo)
        runBlocking {
            db.dictionaryDao().insertAll(
                listOf(
                    entry("食べる", "たべる", frequency = 300),
                    entry("食ぶ", "たぶ", frequency = 0),
                    entry("食べた", "たべた", frequency = 0),
                    entry("走る", "はしる", frequency = 500),
                    entry("見る", "みる", frequency = 100)
                )
            )
        }
    }

    private fun entry(expression: String, reading: String, frequency: Int) = DictionaryEntry(
        expression = expression,
        reading = reading,
        definition = "[\"to eat\"]",
        dictionaryName = "Jitendex",
        language = "ja",
        frequency = frequency
    )

    @After
    fun tearDown() = db.close()

    @Test
    fun `every base form is found in one query`() = runBlocking {
        val results = useCase
            .invokeWithAlternatives("食べた", alternatives = listOf("食べる", "食ぶ", "走る"))
            .first()
        val words = results.map { it.expression }
        // What was typed comes first…
        assertEquals("食べた", words.first())
        // …and each alternative the database knows is there, whichever query
        // it would have taken before.
        assertTrue(words.toString(), words.containsAll(listOf("食べる", "食ぶ", "走る")))
        // Alternatives are ranked by frequency: 食べる (300) before 走る (500),
        // and the unranked archaism last.
        val ranked = words.drop(1)
        assertEquals(listOf("食べる", "走る", "食ぶ"), ranked)
    }

    @Test
    fun `a base form the dictionary does not have costs nothing`() = runBlocking {
        val results = useCase
            .invokeWithAlternatives("見た", alternatives = listOf("見る", "みつ", "みる", "見ゆ"))
            .first()
        assertEquals(listOf("見る"), results.map { it.expression })
    }

    @Test
    fun `no alternatives means no extra query, and the literal still works`() = runBlocking {
        val results = useCase.invoke("走る").first()
        assertEquals(listOf("走る"), results.map { it.expression })
    }

    @Test
    fun `the one-case prefix search finds what the six-branch one finds`() = runBlocking {
        // A Japanese query is the same string lowercased and titlecased, so it
        // takes the two-branch statement. Prefix, exact and reading all still
        // have to match.
        // Ranked one: the only entry with a frequency, then the unranked ones
        // shortest first — the order the DAO documents.
        assertEquals(
            listOf("食べる", "食ぶ", "食べた"),
            useCase.invoke("食").first().map { it.expression }
        )
        assertEquals("食べる", useCase.invoke("たべる").first().first().expression)
        assertEquals("食べる", useCase.invoke("食べる").first().first().expression)
    }
}
