package com.yomitanmobile.data.anki

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.util.Log
import com.yomitanmobile.domain.model.CardProfile
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Writes a deck as an `.apkg` file instead of through AnkiDroid's provider.
 *
 * Two things this buys that the provider cannot:
 *
 *  • **One note type, guaranteed.** The provider can refuse a template write,
 *    and the recovery path for that refusal is what minted 1 113 note types in
 *    a real collection. A file we write ourselves has exactly the note type,
 *    templates and CSS the app designed.
 *  • **Suspended cards.** `AddContentApi` has no way to suspend anything;
 *    `cards.queue` in a file we write is simply a column. That is what makes
 *    "create these, but don't show me the ones I already know" possible at
 *    all.
 *
 * It also costs something, and the cost is real: a file knows nothing about
 * the collection it will be imported into, so the duplicate check is only as
 * good as it was at the moment of generating. Anki's own import will still
 * match notes by their first field, which catches the common case.
 *
 * Schema 11 (see [ApkgSchema]) — the layout every Anki and AnkiDroid still
 * imports.
 */
@Singleton
class ApkgWriter @Inject constructor(
    @ApplicationContext private val context: Context
) {

    /** One note on its way into the file. */
    data class Note(
        val fields: Array<String>,
        val tags: Set<String> = emptySet(),
        /** Written as `queue = -1`: the card exists but is never shown. */
        val suspended: Boolean = false
    ) {
        override fun equals(other: Any?) = this === other
        override fun hashCode() = System.identityHashCode(this)
    }

    data class Result(val notes: Int, val suspended: Int, val media: Int)

    /**
     * Builds the package and streams it into [target].
     *
     * @param media file name as it appears in `[sound:…]` → the file itself.
     */
    suspend fun write(
        target: Uri,
        notes: List<Note>,
        profile: CardProfile,
        deckName: String,
        css: String,
        frontTemplate: String,
        backTemplate: String,
        media: Map<String, File> = emptyMap()
    ): kotlin.Result<Result> = withContext(Dispatchers.IO) {
        if (notes.isEmpty()) {
            return@withContext kotlin.Result.failure(IllegalArgumentException("No notes to write"))
        }
        // The collection is built as a real file first: SQLite cannot be
        // written into a stream, and a document URI is a stream.
        val work = File(context.cacheDir, "apkg").apply { mkdirs() }
        val collection = File(work, "collection.anki2")
        collection.delete()

        try {
            val suspendedCount = buildCollection(
                collection, notes, profile, deckName, css, frontTemplate, backTemplate
            )
            context.contentResolver.openOutputStream(target)?.use { out ->
                ZipOutputStream(out.buffered()).use { zip ->
                    zip.putNextEntry(ZipEntry("collection.anki2"))
                    collection.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()

                    // Media is numbered, and `media` maps the number to the
                    // name the card's [sound:…] refers to. That indirection is
                    // the format's, not ours.
                    val mediaMap = JSONObject()
                    media.entries.forEachIndexed { index, (name, file) ->
                        if (!file.exists()) return@forEachIndexed
                        mediaMap.put(index.toString(), name)
                        zip.putNextEntry(ZipEntry(index.toString()))
                        file.inputStream().use { it.copyTo(zip) }
                        zip.closeEntry()
                    }
                    zip.putNextEntry(ZipEntry("media"))
                    zip.write(mediaMap.toString().toByteArray(Charsets.UTF_8))
                    zip.closeEntry()
                }
            } ?: return@withContext kotlin.Result.failure(
                IllegalStateException("Could not open the destination file")
            )

            kotlin.Result.success(Result(notes.size, suspendedCount, media.size))
        } catch (e: Exception) {
            Log.w(TAG, "Writing the package failed", e)
            kotlin.Result.failure(e)
        } finally {
            collection.delete()
        }
    }

    /** Fills a fresh collection database. Returns how many cards were suspended. */
    private fun buildCollection(
        file: File,
        notes: List<Note>,
        profile: CardProfile,
        deckName: String,
        css: String,
        frontTemplate: String,
        backTemplate: String
    ): Int {
        val db = SQLiteDatabase.openOrCreateDatabase(file, null)
        var suspended = 0
        try {
            db.execSQL(
                """
                CREATE TABLE col (
                    id integer primary key, crt integer not null, mod integer not null,
                    scm integer not null, ver integer not null, dty integer not null,
                    usn integer not null, ls integer not null, conf text not null,
                    models text not null, decks text not null, dconf text not null,
                    tags text not null
                )
                """
            )
            db.execSQL(
                """
                CREATE TABLE notes (
                    id integer primary key, guid text not null, mid integer not null,
                    mod integer not null, usn integer not null, tags text not null,
                    flds text not null, sfld integer not null, csum integer not null,
                    flags integer not null, data text not null
                )
                """
            )
            db.execSQL(
                """
                CREATE TABLE cards (
                    id integer primary key, nid integer not null, did integer not null,
                    ord integer not null, mod integer not null, usn integer not null,
                    type integer not null, queue integer not null, due integer not null,
                    ivl integer not null, factor integer not null, reps integer not null,
                    lapses integer not null, left integer not null, odue integer not null,
                    odid integer not null, flags integer not null, data text not null
                )
                """
            )
            db.execSQL(
                """
                CREATE TABLE revlog (
                    id integer primary key, cid integer not null, usn integer not null,
                    ease integer not null, ivl integer not null, lastIvl integer not null,
                    factor integer not null, time integer not null, type integer not null
                )
                """
            )
            db.execSQL(
                "CREATE TABLE graves (usn integer not null, oid integer not null, " +
                    "type integer not null)"
            )
            db.execSQL("CREATE INDEX ix_notes_usn on notes (usn)")
            db.execSQL("CREATE INDEX ix_cards_usn on cards (usn)")
            db.execSQL("CREATE INDEX ix_cards_nid on cards (nid)")
            db.execSQL("CREATE INDEX ix_cards_sched on cards (did, queue, due)")
            db.execSQL("CREATE INDEX ix_notes_csum on notes (csum)")
            db.execSQL("CREATE INDEX ix_revlog_cid on revlog (cid)")
            db.execSQL("CREATE INDEX ix_revlog_usn on revlog (usn)")

            val now = System.currentTimeMillis()
            val modelId = now
            val deckId = now + 1
            // Anki counts days from the collection's creation; four in the
            // morning is what its own scheduler uses as the day boundary.
            val created = now / 1000 - (now / 1000) % 86_400 + 4 * 3600

            db.execSQL(
                "INSERT INTO col VALUES (1, ?, ?, ?, ?, 0, -1, 0, ?, ?, ?, ?, '{}')",
                arrayOf(
                    created,
                    now / 1000,
                    now,
                    ApkgSchema.SCHEMA_VERSION,
                    ApkgSchema.collectionConfig(deckId).toString(),
                    JSONObject().put(
                        modelId.toString(),
                        ApkgSchema.model(
                            modelId, profile, css, frontTemplate, backTemplate, deckId, now
                        )
                    ).toString(),
                    ApkgSchema.decks(deckId, deckName, now).toString(),
                    ApkgSchema.deckConfig(now).toString()
                )
            )

            db.beginTransaction()
            try {
                notes.forEachIndexed { index, note ->
                    val noteId = now + index * 2L
                    val cardId = noteId + 1
                    val first = note.fields.firstOrNull().orEmpty()
                    db.execSQL(
                        "INSERT INTO notes VALUES (?, ?, ?, ?, -1, ?, ?, ?, ?, 0, '')",
                        arrayOf(
                            noteId,
                            ApkgSchema.guid(first, noteId),
                            modelId,
                            now / 1000,
                            // Anki's tag format: space separated, and padded
                            // with spaces so `tags like '% x %'` matches.
                            if (note.tags.isEmpty()) "" else note.tags.joinToString(" ", " ", " "),
                            ApkgSchema.joinFields(note.fields),
                            ApkgSchema.stripHtml(first),
                            ApkgSchema.fieldChecksum(first)
                        )
                    )
                    if (note.suspended) suspended++
                    db.execSQL(
                        "INSERT INTO cards VALUES (?, ?, ?, 0, ?, -1, 0, ?, ?, 0, 0, 0, 0, 0, 0, 0, 0, '')",
                        arrayOf(
                            cardId,
                            noteId,
                            deckId,
                            now / 1000,
                            if (note.suspended) {
                                ApkgSchema.QUEUE_SUSPENDED
                            } else {
                                ApkgSchema.QUEUE_NEW
                            },
                            // Due is the position a new card is introduced at,
                            // and the list arrives already in the order the
                            // planner decided — most useful word first.
                            index + 1
                        )
                    )
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        } finally {
            db.close()
        }
        return suspended
    }

    companion object {
        private const val TAG = "ApkgWriter"
        const val EXTENSION = "apkg"
        const val MIME_TYPE = "application/octet-stream"
    }
}
