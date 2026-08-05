package com.yomitanmobile.data.anki

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.yomitanmobile.data.local.database.AppDatabase
import com.yomitanmobile.data.local.entity.AnkiCollectionWord
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The duplicate guard as the mining screen and the JLPT generator actually use
 * it: reading the STORED scan, without an AnkiDroid provider in sight.
 *
 * The rules pinned here are the ones that decide whether a card gets skipped,
 * so getting them wrong either floods the collection with duplicates or
 * silently swallows words the user wanted.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AnkiCollectionStoreDbTest {

    private lateinit var db: AppDatabase
    private lateinit var store: AnkiCollectionStore

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries().build()
        store = AnkiCollectionStore(AnkiCollectionIndex(context), db.ankiCollectionWordDao())
    }

    @After
    fun tearDown() = db.close()

    private fun store(vararg words: String) = runBlocking {
        db.ankiCollectionWordDao().replaceAll(
            words.map { AnkiCollectionWord(word = it, source = "Core 2k", scannedAt = 1L) }
        )
    }

    @Test
    fun withoutAStoredScanNothingIsTreatedAsDuplicate() = runBlocking {
        // "Not scanned" must never read as "you already have everything" —
        // that would silently block every export.
        assertFalse(store.contains("食べる", "たべる"))
        assertFalse(store.hasStoredScan())
    }

    @Test
    fun matchesTheWrittenForm() = runBlocking {
        store("食べる")
        assertTrue(store.contains("食べる", "たべる"))
        assertFalse(store.contains("飲む", "のむ"))
    }

    @Test
    fun kanaOnlyWordsAlsoMatchOnTheReading() = runBlocking {
        store("たべる")
        // No kanji form to disambiguate, so the reading is a safe match.
        assertTrue(store.contains("", "たべる"))
        assertTrue(store.contains("たべる", "たべる"))
    }

    @Test
    fun homophonesWithDifferentKanjiAreNotConfused() = runBlocking {
        // The collection has 聞く. 効く reads the same but is a different word,
        // and matching on the reading would wrongly skip it.
        store("聞く", "きく")
        assertTrue(store.contains("聞く", "きく"))
        assertFalse(store.contains("効く", "きく"))
    }

    @Test
    fun clearingDropsEveryStoredWord() = runBlocking {
        store("学校")
        assertTrue(store.contains("学校", "がっこう"))
        store.clear()
        assertFalse(store.contains("学校", "がっこう"))
    }

    @Test
    fun rescanReplacesRatherThanAccumulates() = runBlocking {
        store("旧い単語")
        store("新しい単語")
        assertFalse(store.contains("旧い単語", ""))
        assertTrue(store.contains("新しい単語", ""))
    }
}
