package com.yomitanmobile.data.local.database

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Upgrading an existing install, end to end: a real v16 database file is
 * built from the committed schema, filled with the kind of rows a user
 * already has, and then opened through Room so the real migrations run.
 *
 * Every other database test starts from an empty in-memory database created
 * at the current version, so none of them touch a migration at all. That is
 * the one failure mode with no workaround for the user: a wrong migration
 * throws on first launch after the update and the app cannot start.
 *
 * v16 is the last version before language existed, which makes it the
 * version every current install is on.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MigrationFrom16DbTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val dbName = "migration_from_16.db"
    private lateinit var dbFile: File

    @Before
    fun setUp() {
        dbFile = context.getDatabasePath(dbName)
        dbFile.parentFile?.mkdirs()
        if (dbFile.exists()) dbFile.delete()
    }

    @After
    fun tearDown() {
        if (dbFile.exists()) dbFile.delete()
    }

    /** Creates the v16 database exactly as the committed schema defines it. */
    private fun createV16Database() {
        val schema = File("schemas/com.yomitanmobile.data.local.database.AppDatabase/16.json")
        assertTrue("committed v16 schema is missing: ${schema.absolutePath}", schema.exists())

        val root = Json.parseToJsonElement(schema.readText()).jsonObject["database"]!!.jsonObject
        val statements = root["entities"]!!.jsonArray.flatMap { entity ->
            val obj = entity.jsonObject
            val table = obj["tableName"]!!.jsonPrimitive.content
            val create = obj["createSql"]!!.jsonPrimitive.content
                .replace("\${TABLE_NAME}", table)
            val indices = obj["indices"]?.jsonArray.orEmpty().map {
                it.jsonObject["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", table)
            }
            listOf(create) + indices
        }

        val callback = object : SupportSQLiteOpenHelper.Callback(16) {
            override fun onCreate(db: SupportSQLiteDatabase) {
                statements.forEach { db.execSQL(it) }
                // Room stores its schema hash here and refuses to open a
                // database whose identity it cannot confirm.
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS room_master_table " +
                        "(id INTEGER PRIMARY KEY, identity_hash TEXT)"
                )
                db.execSQL(
                    "INSERT OR REPLACE INTO room_master_table (id, identity_hash) VALUES " +
                        "(42, '${root["identityHash"]!!.jsonPrimitive.content}')"
                )
                // A word the user already had before the upgrade.
                db.execSQL(
                    "INSERT INTO dictionary_entries " +
                        "(expression, reading, definition, frequency, pitch_accent, " +
                        " parts_of_speech, dictionary_name, sequence_number, " +
                        " example_sentence, example_sentence_translation, audio_file, " +
                        " jlpt_level, examples_json) " +
                        "VALUES ('学校', 'がっこう', '[\"school\"]', 100, '', 'n', " +
                        " 'Jitendex', 0, '', '', '', 5, '')"
                )
                // …and the user data that had no language before v19.
                db.execSQL(
                    "INSERT INTO favorite_words " +
                        "(expression, reading, definition_preview, entry_id, added_date) " +
                        "VALUES ('学校', 'がっこう', 'school', 0, 1)"
                )
                db.execSQL("INSERT INTO search_history (`query`, timestamp) VALUES ('学校', 1)")
            }

            override fun onUpgrade(db: SupportSQLiteDatabase, old: Int, new: Int) = Unit
        }

        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(dbName)
                .callback(callback)
                .build()
        )
        helper.writableDatabase.use { it.version = 16 }
    }

    @Test
    fun `a v16 install upgrades and keeps its dictionary`() = runBlocking {
        createV16Database()

        val db = Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            .addMigrations(
                AppDatabase.MIGRATION_16_17,
                AppDatabase.MIGRATION_17_18,
                AppDatabase.MIGRATION_18_19
            )
            .allowMainThreadQueries()
            .build()

        try {
            // Opening at all is most of the assertion: Room validates the
            // migrated schema against the current entities and throws if a
            // column, index or the FTS table definition disagrees.
            val entries = db.dictionaryDao().searchExact("学校", "ja").first()
            assertEquals(1, entries.size)

            // Migration 16→17 defaults existing rows to Japanese. If that
            // default and AppLanguage.DEFAULT ever disagreed, the upgraded
            // user would open the app to an empty dictionary.
            assertEquals("ja", entries.single().language)

            // 17→18 rebuilt the FTS index; the definition search has to still
            // find the row it was rebuilt from.
            val byDefinition = db.dictionaryDao().searchByDefinition("school*", "ja").first()
            assertEquals(listOf("学校"), byDefinition.map { it.expression })

            // 18→19: favourites and history survive and stay Japanese, so the
            // upgraded user still sees the words they starred.
            assertEquals(
                listOf("学校"),
                db.favoriteWordDao().getAllFavorites("ja").first().map { it.expression }
            )
            assertEquals(
                listOf("学校"),
                db.searchHistoryDao().getRecentSearches("ja", 20).first().map { it.query }
            )
            // …and are invisible from another language.
            assertTrue(db.favoriteWordDao().getAllFavorites("en").first().isEmpty())
        } finally {
            db.close()
        }
    }
}
