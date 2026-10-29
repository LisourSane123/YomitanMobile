package com.yomitanmobile.data.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.yomitanmobile.data.local.database.AppDatabase
import com.yomitanmobile.data.local.entity.DictionaryEntry
import com.yomitanmobile.data.local.entity.DictionaryInfo
import com.yomitanmobile.data.parser.YomitanDictionaryParser
import com.yomitanmobile.data.settings.FrequencySettings
import com.yomitanmobile.data.settings.LanguageSettings
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
 * Deleting a dictionary in chunks, with no FTS rebuild afterwards: the
 * content-sync trigger is what keeps meaning search clean, so it must be
 * enough on its own — the deleted dictionary's glosses are gone from the
 * index, the other dictionary's are all still there.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class DictionaryDeleteDbTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: DictionaryRepositoryImpl

    @Before
    fun setUp() = runBlocking {
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
        db.dictionaryInfoDao().insert(DictionaryInfo(name = KEEP, language = "ja"))
        db.dictionaryInfoDao().insert(DictionaryInfo(name = GONE, language = "ja"))
        db.dictionaryDao().insertAll(
            List(3) { DictionaryEntry(expression = "猫$it", reading = "ねこ", definition = "[\"keepword cat\"]", dictionaryName = KEEP) }
        )
        // More rows than one delete chunk, so the loop has to go round.
        db.dictionaryDao().insertAll(
            List(GONE_ROWS) { DictionaryEntry(expression = "犬$it", reading = "いぬ", definition = "[\"goneword dog\"]", dictionaryName = GONE) }
        )
    }

    @After
    fun tearDown() = db.close()

    private fun ftsHits(term: String): Int =
        db.openHelper.readableDatabase
            .query("SELECT COUNT(*) FROM dictionary_entries_fts WHERE dictionary_entries_fts MATCH ?", arrayOf(term))
            .use { it.moveToFirst(); it.getInt(0) }

    @Test
    fun `delete removes the rows, their index entries and the dictionary, and nothing else`() = runBlocking {
        assertEquals(GONE_ROWS, ftsHits("goneword"))
        val progress = mutableListOf<Float>()

        repo.deleteDictionary(GONE) { progress += it }

        assertEquals(0, db.dictionaryDao().getEntryCountForDictionary(GONE))
        assertEquals(0, ftsHits("goneword"))
        assertEquals(3, ftsHits("keepword"))
        assertEquals(listOf(KEEP), db.dictionaryInfoDao().getAllDictionaries().first().map { it.name })
        assertTrue("one report per chunk: $progress", progress.size >= 3)
        assertEquals(1f, progress.last())
        assertEquals(progress.sorted(), progress)
    }

    private companion object {
        const val KEEP = "Jitendex"
        const val GONE = "Encyclopedia Light (clean)"
        const val GONE_ROWS = 45_000
    }
}
