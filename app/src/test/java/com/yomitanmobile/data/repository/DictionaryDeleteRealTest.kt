package com.yomitanmobile.data.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.yomitanmobile.data.local.database.AppDatabase
import com.yomitanmobile.data.parser.YomitanDictionaryParser
import com.yomitanmobile.data.settings.FrequencySettings
import com.yomitanmobile.data.settings.LanguageSettings
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Deleting a real dictionary from an on-disk database, timed. Skipped unless
 * -Ddelete.zip names a Yomitan zip (e.g. the cleaned PixivLight).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class DictionaryDeleteRealTest {

    @Test
    fun importThenDelete() = runBlocking {
        val zip = System.getProperty("delete.zip")?.let(::File)
        assumeTrue(zip != null && zip.isFile)
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        context.deleteDatabase("delete-real.db")
        val db = Room.databaseBuilder(context, AppDatabase::class.java, "delete-real.db").build()
        val repo = DictionaryRepositoryImpl(
            dictionaryDao = db.dictionaryDao(), dictionaryInfoDao = db.dictionaryInfoDao(), kanjiDao = db.kanjiDao(),
            frequencyDao = db.frequencyDao(), jlptTagDao = db.jlptTagDao(), parser = YomitanDictionaryParser(), database = db,
            languageSettings = LanguageSettings(context), frequencySettings = FrequencySettings(context)
        )
        var t = System.currentTimeMillis()
        val result = zip!!.inputStream().buffered().use { repo.importDictionary(it) {} }
        println("import: $result in ${System.currentTimeMillis() - t} ms")
        val name = repo.getImportedDictionaries().first().single().name
        val rows = db.dictionaryDao().getEntryCountForDictionary(name)
        println("installed '$name': $rows rows")

        t = System.currentTimeMillis()
        var last = -1
        repo.deleteDictionary(name) { p ->
            val pct = (p * 100).toInt()
            if (pct / 10 != last / 10) println("  ${pct}% after ${System.currentTimeMillis() - t} ms")
            last = pct
        }
        println("delete: ${System.currentTimeMillis() - t} ms")
        assertEquals(0, db.dictionaryDao().getEntryCountForDictionary(name))
        assertEquals(emptyList<Any>(), repo.getImportedDictionaries().first())
        val fts = db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM dictionary_entries_fts").use { it.moveToFirst(); it.getInt(0) }
        println("fts rows left: $fts")
        db.close()
    }
}
