package com.yomitanmobile.data.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.yomitanmobile.data.local.database.AppDatabase
import com.yomitanmobile.data.local.entity.DictionaryEntry
import com.yomitanmobile.data.parser.YomitanDictionaryParser
import com.yomitanmobile.data.settings.LanguageSettings
import com.yomitanmobile.domain.model.AppLanguage
import com.yomitanmobile.domain.usecase.SearchDictionaryUseCase
import com.yomitanmobile.util.JapaneseDeconjugator
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
 * The whole Japanese lookup path, the way the search screen drives it:
 * deconjugate what was typed, run the literal query plus every candidate, and
 * merge. A learner types what the subtitle said — 食べています, 行った,
 * 勉強した — not the dictionary form.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class InflectedSearchDbTest {

    private lateinit var db: AppDatabase
    private lateinit var useCase: SearchDictionaryUseCase

    @Before
    fun setUp() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java
        ).allowMainThreadQueries().build()

        val words = listOf(
            Triple("食べる", "たべる", 500),
            // 食ぶ is a real (archaic) entry AND the shape the godan potential
            // rule produces from 食べる — the perfect trap for result ordering.
            Triple("食ぶ", "たぶ", 0),
            Triple("走る", "はしる", 900),
            Triple("行く", "いく", 120),
            Triple("読む", "よむ", 700),
            Triple("勉強", "べんきょう", 400),
            Triple("勉強する", "べんきょうする", 401),
            Triple("静か", "しずか", 1500),
            Triple("高い", "たかい", 300),
            Triple("来る", "くる", 200)
        )
        db.dictionaryDao().insertAll(
            words.map { (expr, reading, freq) ->
                DictionaryEntry(
                    expression = expr,
                    reading = reading,
                    definition = "[\"gloss\"]",
                    frequency = freq,
                    dictionaryName = "Test",
                    language = "ja"
                )
            }
        )
        val settings = LanguageSettings(ApplicationProvider.getApplicationContext())
        settings.setLanguage(AppLanguage.JAPANESE)
        useCase = SearchDictionaryUseCase(
            DictionaryRepositoryImpl(
                dictionaryDao = db.dictionaryDao(),
                dictionaryInfoDao = db.dictionaryInfoDao(),
                kanjiDao = db.kanjiDao(),
                frequencyDao = db.frequencyDao(),
                jlptTagDao = db.jlptTagDao(),
                parser = YomitanDictionaryParser(),
                database = db,
                languageSettings = settings
            )
        )
    }

    @After
    fun tearDown() = db.close()

    /** Exactly what SearchViewModel does for a Japanese query. */
    private fun search(query: String): List<String> = runBlocking {
        val candidates = JapaneseDeconjugator.analyze(query).map { it.baseForm }
        useCase.invokeWithAlternatives(query, candidates).first().map { it.expression }
    }

    @Test
    fun `an inflected verb finds its dictionary form`() {
        for (form in listOf("食べます", "食べました", "食べている", "食べてる", "食べたい", "食べなかった")) {
            assertEquals("$form should find 食べる first", "食べる", search(form).first())
        }
        assertEquals("走る", search("走っています").first())
        assertEquals("行く", search("行った").first())
        assertEquals("読む", search("読める").first())
        assertEquals("来る", search("来ました").first())
    }

    @Test
    fun `an adjective and a na-adjective find their base form`() {
        assertEquals("高い", search("高かった").first())
        assertEquals("静か", search("静かじゃない").first())
    }

    @Test
    fun `a suru verb finds both the verb and the noun`() {
        val results = search("勉強した")
        assertEquals(listOf("勉強", "勉強する"), results.sorted().sortedBy { it.length })
    }

    @Test
    fun `the common word outranks the archaism the rules also produce`() {
        // 食ぶ is what the potential rule makes of 食べ-; it is unranked, so it
        // must never be offered ahead of the word the user was inflecting.
        val results = search("食べています")
        assertEquals("食べる", results.first())
    }
}
