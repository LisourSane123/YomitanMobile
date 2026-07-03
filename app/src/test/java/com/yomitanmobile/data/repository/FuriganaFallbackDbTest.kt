package com.yomitanmobile.data.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.yomitanmobile.data.local.database.AppDatabase
import com.yomitanmobile.data.local.entity.DictionaryEntry
import com.yomitanmobile.data.parser.YomitanDictionaryParser
import com.yomitanmobile.util.FuriganaGenerator
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
 * End-to-end check of the synthesized-furigana fallback against a real (in-memory
 * Room) dictionary: candidateExpressions → repository.getReadingsForExpressions →
 * FuriganaGenerator.generate. This is the path a no-ruby / pre-ruby import relies
 * on, and the layer that was never exercised on the JVM before.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class FuriganaFallbackDbTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: DictionaryRepositoryImpl

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
            parser = YomitanDictionaryParser(),
            database = db
        )
    }

    @After
    fun tearDown() = db.close()

    private fun insert(expression: String, reading: String, frequency: Int = 0) = runBlocking {
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

    @Test
    fun readingsLookupReturnsRows() = runBlocking {
        insert("私", "わたし")
        insert("本", "ほん")
        insert("読む", "よむ")

        val map = repo.getReadingsForExpressions(listOf("私", "本", "読む", "存在しない"))
        assertEquals("わたし", map["私"])
        assertEquals("ほん", map["本"])
        assertEquals("よむ", map["読む"])
        assertTrue("absent word is simply missing", "存在しない" !in map)
    }

    @Test
    fun readingsLookupPicksFrequencyRankedReading() = runBlocking {
        // Two readings for the same expression — the frequency-ranked one wins.
        insert("私", "あっし", frequency = 0)
        insert("私", "わたし", frequency = 10)
        val map = repo.getReadingsForExpressions(listOf("私"))
        assertEquals("わたし", map["私"])
    }

    @Test
    fun readingsLookupSurvivesOver999Candidates() = runBlocking {
        // Reproduces the SQLite variable-limit bug: a candidate list well past
        // 999 must not throw / silently return empty.
        insert("郵便局", "ゆうびんきょく")
        val padding = (1..1500).map { "x$it" }
        val map = repo.getReadingsForExpressions(padding + "郵便局")
        assertEquals("ゆうびんきょく", map["郵便局"])
    }

    @Test
    fun fullFallbackAnnotatesNoRubySentence() = runBlocking {
        // The real user path: a sentence with NO ruby, resolved purely from the
        // installed dictionary, including a conjugated verb via deconjugation.
        insert("私", "わたし", frequency = 10)
        insert("寿司", "すし")
        insert("食べる", "たべる")

        val sentence = "私は寿司を食べた。"
        val candidates = FuriganaGenerator.candidateExpressions(sentence).toList()
        val readings = repo.getReadingsForExpressions(candidates)
        val segs = FuriganaGenerator.generate(sentence, readings)

        assertEquals(sentence, segs.joinToString("") { it.text })
        assertTrue("私→わたし: $segs", segs.any { it.text == "私" && it.reading == "わたし" })
        assertTrue("寿司→すし: $segs", segs.any { it.text == "寿司" && it.reading == "すし" })
        assertTrue("食→た (conjugated 食べた): $segs", segs.any { it.text == "食" && it.reading == "た" })
    }
}
