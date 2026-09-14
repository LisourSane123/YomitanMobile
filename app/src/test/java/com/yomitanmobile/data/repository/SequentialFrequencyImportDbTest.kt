package com.yomitanmobile.data.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.yomitanmobile.data.local.database.AppDatabase
import com.yomitanmobile.data.local.entity.DictionaryEntry
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
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Several frequency lists installed back to back — what the install queue does
 * when the user picks four of them in one sitting.
 *
 * Each import writes its rows under a shared temp name and renames them at the
 * end, and each one clears stale temp rows on the way in, so "does list two
 * survive list three" is a question worth asking of the real import path
 * rather than of the queue that calls it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SequentialFrequencyImportDbTest {

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
            jlptTagDao = db.jlptTagDao(),
            parser = YomitanDictionaryParser(),
            database = db,
            languageSettings = LanguageSettings(ApplicationProvider.getApplicationContext()),
            frequencySettings = FrequencySettings(ApplicationProvider.getApplicationContext())
        )
    }

    @After
    fun tearDown() = db.close()

    /** A frequency meta dictionary of the shape the real lists ship. */
    private fun frequencyZip(title: String, ranks: Map<String, Int>): ByteArray {
        val meta = ranks.entries.joinToString(",\n") { (word, rank) ->
            """["$word", "freq", {"reading": "", "frequency": $rank}]"""
        }
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("index.json"))
            zip.write("""{"title":"$title","format":"3","revision":"1"}""".toByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("term_meta_bank_1.json"))
            zip.write("[\n$meta\n]".toByteArray())
            zip.closeEntry()
        }
        return out.toByteArray()
    }

    private suspend fun import(bytes: ByteArray) =
        repo.importDictionary(ByteArrayInputStream(bytes))

    @Test
    fun `three lists installed in a row all survive, each with its own ranks`() = runBlocking {
        db.dictionaryDao().insertAll(
            listOf("喋る", "朕", "本").map {
                DictionaryEntry(expression = it, reading = "", definition = "[]", dictionaryName = "JMdict")
            }
        )

        assertTrue(import(frequencyZip("CEJC-LUW", mapOf("喋る" to 400, "本" to 120))).success)
        assertTrue(import(frequencyZip("BCCWJ", mapOf("喋る" to 9000, "朕" to 21000, "本" to 80))).success)
        assertTrue(import(frequencyZip("JPDBv2", mapOf("本" to 300))).success)

        // Every list is still there under its own name — an import does not
        // take the previous one's rows with it.
        val forHon = repo.getFrequencies("本", "").associate { it.dictionary to it.rank }
        assertEquals(mapOf("CEJC-LUW" to 120, "BCCWJ" to 80, "JPDBv2" to 300), forHon)

        // …and all three are offered to the frequency screen.
        assertEquals(
            listOf("BCCWJ", "CEJC-LUW", "JPDBv2"),
            db.frequencyDao().observeDictionaries().first()
        )

        // The rollup ran after the last import, over everything: with no
        // leading list chosen, the best rank anywhere.
        assertEquals(
            80,
            db.dictionaryDao().getEntriesByExpressions(listOf("本"), "ja").single().frequency
        )
        assertEquals(
            21000,
            db.dictionaryDao().getEntriesByExpressions(listOf("朕"), "ja").single().frequency
        )
    }
}
