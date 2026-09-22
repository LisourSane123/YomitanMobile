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
import com.yomitanmobile.domain.model.AppLanguage
import com.yomitanmobile.domain.model.CefrLevel
import com.yomitanmobile.domain.model.StudyLevel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * CEFR levels through the app's real import: onto English rows, in CefrLevel's
 * convention, and never onto a Japanese row of the same spelling — JLPT and
 * CEFR share the stored column.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class CefrLevelImportDbTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: DictionaryRepositoryImpl

    private val csv = "headword,pos,CEFR,CoreInventory 1,CoreInventory 2,Threshold\n" +
        "house,noun,A1,,,\n" +
        "light,adjective,A1,,,\n" +
        "light,verb,B1,,,\n" +
        "adviser/advisor,noun,B2,,,\n" +
        "OK,adjective,A1,,,\n"

    @Before
    fun setUp() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
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
        db.dictionaryInfoDao().insert(DictionaryInfo(name = "Wiktionary EN→PL", language = "en"))
        db.dictionaryInfoDao().insert(DictionaryInfo(name = "Jitendex", language = "ja"))
        db.dictionaryDao().insertAll(
            listOf("house", "light", "advisor", "Adviser").map {
                DictionaryEntry(expression = it, reading = it, definition = "[]", dictionaryName = "Wiktionary EN→PL", language = "en")
            } + DictionaryEntry(expression = "OK", reading = "オーケー", definition = "[]", dictionaryName = "Jitendex", language = "ja")
        )
    }

    @After
    fun tearDown() = db.close()

    private fun level(word: String, language: String) =
        runBlocking { db.dictionaryDao().getEntriesByExpressions(listOf(word), language).single().jlptLevel }

    @Test
    fun `a word keeps its easiest level, and each spelling variant is a word`() {
        val levels = LevelListConverter.cefrLevels(csv)
        assertEquals(CefrLevel.A1, levels["light"])
        assertEquals(CefrLevel.B2, levels["adviser"])
        assertEquals(CefrLevel.B2, levels["advisor"])
    }

    @Test
    fun `levels land on English rows only`() = runBlocking {
        val zip = LevelListConverter.toYomitanZip("CEFR-J (EN A1–B2)", "t", "en", "cite", LevelListConverter.cefrLevels(csv))
        val result = repo.importDictionary(zip.inputStream(), onProgress = {})
        assertTrue(result.errorMessage ?: "", result.success)

        assertEquals(CefrLevel.A1.dbValue, level("house", "en"))
        assertEquals(CefrLevel.A1.dbValue, level("light", "en"))
        assertEquals(CefrLevel.B2.dbValue, level("advisor", "en"))
        // The capitalised spelling a dictionary may use takes the level too.
        assertEquals(CefrLevel.B2.dbValue, level("Adviser", "en"))
        // Jitendex's OK is not the English word: no level from this list.
        assertEquals(0, level("OK", "ja"))

        // What the deck generator asks for, in English mode: every A1 word.
        LanguageSettings(ApplicationProvider.getApplicationContext()).setLanguage(AppLanguage.ENGLISH)
        val englishRepo = DictionaryRepositoryImpl(
            dictionaryDao = db.dictionaryDao(), dictionaryInfoDao = db.dictionaryInfoDao(), kanjiDao = db.kanjiDao(),
            frequencyDao = db.frequencyDao(), jlptTagDao = db.jlptTagDao(), parser = YomitanDictionaryParser(), database = db,
            languageSettings = LanguageSettings(ApplicationProvider.getApplicationContext()).apply { loadBlocking() },
            frequencySettings = FrequencySettings(ApplicationProvider.getApplicationContext())
        )
        val a1 = englishRepo.getEntriesByJlptLevel(CefrLevel.A1.dbValue).map { it.expression }.toSet()
        assertEquals(setOf("house", "light"), a1)
        LanguageSettings(ApplicationProvider.getApplicationContext()).setLanguage(AppLanguage.JAPANESE)
    }

    @Test
    fun `the badge reads the number on the language's own scale`() {
        assertEquals("CEFR" to "A1", StudyLevel.badge(AppLanguage.ENGLISH, 6)!!.let { it.scale to it.label })
        assertEquals("CEFR" to "C2", StudyLevel.badge(AppLanguage.ENGLISH, 1)!!.let { it.scale to it.label })
        assertEquals("JLPT" to "N5", StudyLevel.badge(AppLanguage.JAPANESE, 5)!!.let { it.scale to it.label })
        // A CEFR A1 (6) is no JLPT level at all.
        assertNull(StudyLevel.badge(AppLanguage.JAPANESE, 6))
        assertNull(StudyLevel.badge(AppLanguage.SPANISH, 3))
    }

    /** The real lists: -Dcefr.dir=<dir with both CSVs>. */
    @Test
    fun `the real CEFR-J and Octanove lists convert`() {
        val dir = File(System.getProperty("cefr.dir").orEmpty())
        val cefrj = File(dir, "cefrj-vocabulary-profile-1.5.csv")
        val octanove = File(dir, "octanove-vocabulary-profile-c1c2-1.0.csv")
        Assume.assumeTrue(cefrj.isFile && octanove.isFile)
        val a = LevelListConverter.cefrLevels(cefrj.readText())
        val c = LevelListConverter.cefrLevels(octanove.readText())
        println("CEFR-J: ${a.size} words ${a.values.groupingBy { it }.eachCount()}; Octanove: ${c.size} ${c.values.groupingBy { it }.eachCount()}")
        assertEquals(CefrLevel.A1, a["house"])
        assertTrue(c.values.all { it == CefrLevel.C1 || it == CefrLevel.C2 })
    }
}
