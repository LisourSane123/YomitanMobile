package com.yomitanmobile.data.anki

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.ichi2.anki.FlashCardsContract
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reads the AnkiDroid collection and builds a lookup of the Japanese words it
 * already contains, so the bulk deck generator never creates a card the user
 * is already studying.
 *
 * Deliberately note-type agnostic: instead of knowing about specific decks it
 * takes EVERY field of every scanned note, throws away anything that isn't a
 * short Japanese string, and indexes what's left. That makes it work with
 * Core 2k/6k/10k (`Vocabulary-Kanji`, `Vocabulary-Kana`,
 * `Vocabulary-Furigana`), Kaishi 1.5k (`Word`, `Word Reading`), this app's own
 * `Yomitan-Mobile-v8` notes and any hand-rolled note type, without a per-deck
 * field mapping.
 *
 * Furigana notation (`食[た]べる`, the format Core/Kaishi use in their reading
 * fields) is indexed under BOTH the plain expression and the plain reading.
 */
@Singleton
class AnkiCollectionIndex @Inject constructor(
    @ApplicationContext private val context: Context
) {

    /**
     * A word is "already in the collection" when its written form matches an
     * indexed field. Kana-only words also match on the reading, since for them
     * there is no kanji form to disambiguate homophones with — the same rule
     * [com.yomitanmobile.util.JlptVocabulary] uses.
     */
    data class Index(
        private val keys: Set<String>,
        val noteCount: Int,
        val available: Boolean
    ) {
        fun contains(expression: String, reading: String): Boolean {
            if (!available) return false
            val expr = AnkiNoteFieldIndexer.normalizeKey(expression)
            if (expr.isNotEmpty() && expr in keys) return true
            val read = AnkiNoteFieldIndexer.normalizeKey(reading)
            if (read.isEmpty()) return false
            // Only fall back to the reading when there is no kanji form that
            // could belong to a different word.
            return (expr.isEmpty() || AnkiNoteFieldIndexer.isKanaOnly(expr)) && read in keys
        }

        companion object {
            val EMPTY = Index(emptySet(), 0, available = false)
        }
    }

    /**
     * Scans the collection.
     *
     * @param deckNames restricts the scan to these decks; empty scans
     * everything, which is what you want for a duplicate check (the user may
     * keep Core in one deck and their mining in another).
     */
    suspend fun build(
        deckNames: List<String> = emptyList(),
        maxNotes: Int = MAX_NOTES
    ): Index = withContext(Dispatchers.IO) {
        if (!hasPermission()) {
            Log.i(TAG, "Anki read permission missing — duplicate check disabled")
            return@withContext Index.EMPTY
        }

        // The provider interprets `selection` as an Anki search string, but
        // which strings it accepts has varied across AnkiDroid versions — a
        // rejected search throws or returns nothing. Try the narrow search
        // first, then progressively blunter ones, and take the first that
        // actually yields notes.
        val searches = buildSearches(deckNames)
        for (search in searches) {
            val scan = scanNotes(search, maxNotes) ?: continue
            // An empty result from a match-everything search more likely means
            // "this provider version didn't understand the syntax" than "the
            // collection is empty", so try the next form. A deck-restricted
            // search matching nothing is a real answer and is kept as-is.
            val isGenericSearch = search == ALL_NOTES_SEARCH || search.isNullOrEmpty()
            if (scan.noteCount == 0 && isGenericSearch && search != searches.last()) {
                Log.i(TAG, "Search '$search' matched no notes; trying a broader one")
                continue
            }
            Log.i(TAG, "Indexed ${scan.noteCount} notes -> ${scan.keyCount} word keys")
            return@withContext scan.index
        }
        Index.EMPTY
    }

    private class ScanResult(val index: Index, val noteCount: Int, val keyCount: Int)

    /** Runs one search; null means the provider refused it. */
    private fun scanNotes(search: String?, maxNotes: Int): ScanResult? {
        val keys = HashSet<String>(4096)
        var notes = 0
        return try {
            val projection = arrayOf(FlashCardsContract.Note._ID, FlashCardsContract.Note.FLDS)
            val cursor = context.contentResolver.query(
                FlashCardsContract.Note.CONTENT_URI,
                projection,
                search,
                null,
                null
            ) ?: run {
                Log.w(TAG, "Note provider returned a null cursor for search='$search'")
                return null
            }
            cursor.use {
                val fldsIndex = it.getColumnIndex(FlashCardsContract.Note.FLDS)
                if (fldsIndex < 0) {
                    Log.w(TAG, "Note provider returned no ${FlashCardsContract.Note.FLDS} column")
                    return null
                }
                while (it.moveToNext() && notes < maxNotes) {
                    notes++
                    val flds = it.getString(fldsIndex) ?: continue
                    AnkiNoteFieldIndexer.collectKeysFromNote(flds, keys)
                }
            }
            ScanResult(Index(keys, notes, available = true), notes, keys.size)
        } catch (e: Exception) {
            // Older AnkiDroid builds, a revoked permission or a locked
            // collection all land here. The generator degrades to "no
            // duplicate check" and says so in the UI rather than failing.
            Log.w(TAG, "Collection scan failed for search='$search'", e)
            null
        }
    }

    private fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, AnkiCardCreator.PERMISSION) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * Search strings to try, narrowest first. `deck:*` is the documented
     * match-everything search; the empty string and `null` are the fallbacks
     * for provider versions that reject it.
     */
    private fun buildSearches(deckNames: List<String>): List<String?> {
        val decks = deckNames.map { it.trim() }.filter { it.isNotEmpty() }
        val deckSearch = if (decks.isEmpty()) null else decks.joinToString(" OR ") { name ->
            // Anki search syntax: quotes wrap the name, inner quotes escape.
            "deck:\"${name.replace("\"", "\\\"")}\""
        }
        return listOfNotNull(deckSearch, ALL_NOTES_SEARCH, "", null)
    }

    private companion object {
        const val TAG = "AnkiCollectionIndex"
        /** Anki search that matches every note in the collection. */
        const val ALL_NOTES_SEARCH = "deck:*"
        /** Safety valve for very large collections. */
        const val MAX_NOTES = 200_000
    }
}
