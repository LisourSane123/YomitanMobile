package com.yomitanmobile.domain.model

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.yomitanmobile.data.local.database.AppDatabase
import com.yomitanmobile.data.local.entity.DictionaryInfo
import com.yomitanmobile.data.local.entity.JlptTag
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class LevelScaleTest {

    @Test
    fun `JLPT keeps the names and tags decks were made with`() {
        val jlpt = LevelScale.forLanguage(AppLanguage.JAPANESE)!!
        assertEquals(listOf(5, 4, 3, 2, 1), jlpt.levels)
        assertEquals("JLPT N5", jlpt.deckName(5))
        assertEquals("jlpt-n5", jlpt.tag(5))
    }

    @Test
    fun `English decks are CEFR, easiest first`() {
        val cefr = LevelScale.forLanguage(AppLanguage.ENGLISH)!!
        assertEquals(listOf("A1", "A2", "B1", "B2", "C1", "C2"), cefr.levels.map(cefr::label))
        assertEquals("CEFR B2", cefr.deckName(CefrLevel.B2.dbValue))
        assertEquals("cefr-b2", cefr.tag(CefrLevel.B2.dbValue))
        assertNull(LevelScale.forLanguage(AppLanguage.SPANISH))
    }

    @Test
    fun `tag counts are per language where the numbers overlap`() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            db.dictionaryInfoDao().insert(DictionaryInfo(name = "JLPT", language = "ja"))
            db.dictionaryInfoDao().insert(DictionaryInfo(name = "CEFR-J", language = "en"))
            // 3 is JLPT N3 and CEFR B2 at once.
            db.jlptTagDao().insertAll(
                listOf(
                    JlptTag(expression = "勉強", reading = "べんきょう", dictionary = "JLPT", level = 3),
                    JlptTag(expression = "consider", reading = "", dictionary = "CEFR-J", level = 3),
                    JlptTag(expression = "achieve", reading = "", dictionary = "CEFR-J", level = 3)
                )
            )
            assertEquals(1, db.jlptTagDao().countForLevel(3, "ja"))
            assertEquals(2, db.jlptTagDao().countForLevel(3, "en"))
        } finally {
            db.close()
        }
    }
}
