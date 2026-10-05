package com.yomitanmobile.data.anki

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.ichi2.anki.FlashCardsContract
import com.yomitanmobile.util.EnglishLemmatizer
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
     * Scans the collection.
     *
     * @param deckNames restricts the scan to these decks; empty scans
     * everything, which is what you want for a duplicate check (the user may
     * keep Core in one deck and their mining in another).
     */
    suspend fun build(
        deckNames: List<String> = emptyList(),
        maxNotes: Int = MAX_NOTES
    ): AnkiCollectionMatch.Index = scan(deckNames, maxNotes).index

    /** Full sweep, including the per-note-type breakdown. */
    suspend fun scan(
        deckNames: List<String> = emptyList(),
        maxNotes: Int = MAX_NOTES
    ): Scan = withContext(Dispatchers.IO) {
        if (!hasPermission()) {
            Log.i(TAG, "Anki read permission missing — duplicate check disabled")
            return@withContext Scan.EMPTY
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
            Log.i(TAG, "Indexed ${scan.noteCount} notes -> ${scan.wordCount} word keys")
            return@withContext scan
        }
        Scan.EMPTY
    }

    /**
     * Result of one provider sweep: the lookup index plus, for every word, the
     * note type it was first seen in. The note type is not used for matching —
     * it exists so the scan screen can show WHERE the matches came from, which
     * is the only way to tell "the provider returned nothing" apart from "the
     * collection really has no Japanese notes".
     */
    class Scan(
        val index: AnkiCollectionMatch.Index,
        val wordSources: Map<String, String>,
        /**
         * Words per note id, so a later sweep can ask the provider for IDS
         * ALONE and still know which words it found. Reading `flds` again and
         * putting every note back through the indexer is the expensive half of
         * a sweep — the fields are HTML blobs and the indexer is regex work —
         * and the answer is already here.
         */
        val wordsByNote: Map<Long, Set<String>> = emptyMap(),
        val noteCount: Int,
        /**
         * The safety valve cut the sweep short, so the index describes only
         * part of the collection. Silence here turns into false "you don't
         * have this word yet" answers on a very large collection, which is the
         * one thing the duplicate check must never say.
         */
        val truncated: Boolean = false,
        /**
         * Notes per note type across the whole sweep.
         *
         * Not for matching — it is what lets the scan screen show a collection
         * that has accumulated hundreds of near-identical note types, which is
         * exactly what this app did to one real collection before
         * `getOrCreateModel` learned to stop.
         */
        val notesPerModel: Map<String, Int> = emptyMap()
    ) {
        val wordCount: Int get() = wordSources.size

        companion object {
            val EMPTY = Scan(AnkiCollectionMatch.Index.EMPTY, emptyMap(), emptyMap(), 0)
        }
    }

    /**
     * Whether AnkiDroid holds a note for this word RIGHT NOW — asked of the
     * collection itself, not of the stored scan.
     *
     * The stored scan is only as current as the last time the user ran it,
     * and an empty one means "not checked": after a reinstall, before the first
     * scan, or for a card added since (from another device, from the desktop,
     * by hand) mining let the word through, and one real collection ended up
     * with seven words mined twice. A single word is cheap to ask about
     * directly: [liveSearch] fetches the few notes that could hold it, and the
     * same [AnkiNoteFieldIndexer] + [AnkiCollectionMatch.Index.containsAny] the full scan uses
     * decides, so "a duplicate" means exactly what it means everywhere else.
     *
     * @return true / false when AnkiDroid answered; null when it could not
     * (no permission, a search the provider rejected, or a candidate set too
     * large to be sure of an absence) — the caller then falls back to the
     * stored scan rather than to "no duplicate".
     */
    suspend fun liveContainsAny(
        expressions: List<String>,
        reading: String,
        readingCountsAlone: Boolean = false
    ): Boolean? = withContext(Dispatchers.IO) {
        if (!hasPermission()) return@withContext null
        val search = AnkiCollectionMatch.liveSearch(expressions, reading) ?: return@withContext null
        val scan = scanNotes(search, LIVE_MAX_NOTES) ?: return@withContext null
        val found = scan.index.containsAny(expressions, reading, readingCountsAlone)
        when {
            found -> true
            // A cut-off sweep can prove presence, never absence.
            scan.truncated -> null
            else -> false
        }
    }

    /**
     * The words whose cards are MATURE — interval of three weeks or more, and
     * not suspended.
     *
     * A second sweep rather than a per-note card lookup: AnkiDroid's provider
     * has no bulk card query, so asking each note for its cards would be one
     * binder round trip per note, tens of thousands of them. The search string
     * does the same job on Anki's side in one pass.
     *
     * Why it is worth a sweep at all: "I have a card for this" and "I know
     * this" are different claims, and the app has been making the first while
     * meaning the second — in the duplicate check, in the "you know X% of this
     * text" figure, and in the kanji coverage bar. A card added yesterday
     * counts for nothing.
     *
     * Empty when the provider refuses the search (older AnkiDroid), which
     * degrades to the old behaviour: everything counts as known, never the
     * other way round.
     */
    suspend fun scanMature(
        deckNames: List<String> = emptyList(),
        maxNotes: Int = MAX_NOTES
    ): Set<String> = scanState(MATURE_SEARCH, emptyMap(), deckNames, maxNotes)

    /**
     * The words whose cards are in a given state — matched by Anki's own
     * search, because the provider has no bulk card query and asking each note
     * for its cards is one binder round trip per note.
     *
     * [wordsByNote] is the map a full scan already built ([Scan.wordsByNote]).
     * With it the sweep reads note IDS only; without it it falls back to
     * reading and re-indexing fields, which is what this did for maturity
     * before and what a caller with no scan in hand still needs.
     *
     * Empty when the provider refuses the search (older AnkiDroid), which
     * degrades to the old behaviour: nothing is claimed, never the other way
     * round.
     */
    suspend fun scanState(
        stateSearch: String,
        wordsByNote: Map<Long, Set<String>>,
        deckNames: List<String> = emptyList(),
        maxNotes: Int = MAX_NOTES
    ): Set<String> = withContext(Dispatchers.IO) {
        if (!hasPermission()) return@withContext emptySet()
        val decks = buildSearches(deckNames).firstOrNull { !it.isNullOrEmpty() && it != ALL_NOTES_SEARCH }
        val search = listOfNotNull(decks, stateSearch).joinToString(" ")
        if (wordsByNote.isEmpty()) {
            val scan = scanNotes(search, maxNotes) ?: return@withContext emptySet()
            Log.i(TAG, "Sweep '$stateSearch': ${scan.noteCount} notes -> ${scan.wordCount} word keys")
            return@withContext scan.wordSources.keys
        }
        val ids = noteIds(search, maxNotes) ?: return@withContext emptySet()
        val out = HashSet<String>(ids.size * 2)
        for (id in ids) out += wordsByNote[id].orEmpty()
        Log.i(TAG, "Sweep '$stateSearch': ${ids.size} notes -> ${out.size} word keys (ids only)")
        out
    }

    /**
     * Note ids matching one search. The cheap half of a sweep: a one-column
     * projection instead of every note's fields.
     */
    private fun noteIds(search: String?, maxNotes: Int): Set<Long>? = try {
        val out = HashSet<Long>(4096)
        val cursor = context.contentResolver.query(
            FlashCardsContract.Note.CONTENT_URI,
            arrayOf(FlashCardsContract.Note._ID),
            search,
            null,
            null
        ) ?: run {
            Log.w(TAG, "Note provider returned a null cursor for search='$search'")
            return null
        }
        cursor.use {
            val idIndex = it.getColumnIndex(FlashCardsContract.Note._ID)
            if (idIndex < 0) return null
            while (it.moveToNext() && out.size < maxNotes) {
                val id = runCatching { it.getLong(idIndex) }.getOrNull()
                    ?: it.getString(idIndex)?.toLongOrNull()
                    ?: continue
                out += id
            }
        }
        out
    } catch (e: Exception) {
        Log.w(TAG, "Id sweep failed for search='$search'", e)
        null
    }

    /** Runs one search; null means the provider refused it. */
    private fun scanNotes(search: String?, maxNotes: Int): Scan? {
        val sources = LinkedHashMap<String, String>(4096)
        val byNote = HashMap<Long, Set<String>>(4096)
        val perNote = HashSet<String>(16)
        // Notes per note type. Free here — the sweep already reads every
        // note's model id — and the only way to see a collection's note-type
        // hygiene without one provider query per note type.
        val notesPerModel = HashMap<String, Int>()
        var notes = 0
        return try {
            val modelNames = loadModelNames()
            val projection = arrayOf(
                FlashCardsContract.Note._ID,
                FlashCardsContract.Note.FLDS,
                FlashCardsContract.Note.MID
            )
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
                val idIndex = it.getColumnIndex(FlashCardsContract.Note._ID)
                val fldsIndex = it.getColumnIndex(FlashCardsContract.Note.FLDS)
                if (fldsIndex < 0) {
                    Log.w(TAG, "Note provider returned no ${FlashCardsContract.Note.FLDS} column")
                    return null
                }
                val midIndex = it.getColumnIndex(FlashCardsContract.Note.MID)
                while (it.moveToNext() && notes < maxNotes) {
                    notes++
                    val flds = it.getString(fldsIndex) ?: continue
                    val noteType = if (midIndex >= 0) {
                        modelNames[runCatching { it.getLong(midIndex) }.getOrNull()].orEmpty()
                    } else {
                        ""
                    }
                    if (noteType.isNotEmpty()) {
                        notesPerModel[noteType] = (notesPerModel[noteType] ?: 0) + 1
                    }
                    perNote.clear()
                    AnkiNoteFieldIndexer.collectKeysFromNote(flds, perNote)
                    if (idIndex >= 0 && perNote.isNotEmpty()) {
                        val id = runCatching { it.getLong(idIndex) }.getOrNull()
                        if (id != null) byNote[id] = HashSet(perNote)
                    }
                    // First note type wins: a word shared by Core and a mining
                    // deck is reported once, under whichever was scanned first.
                    for (key in perNote) sources.putIfAbsent(key, noteType)
                }
            }
            val truncated = notes >= maxNotes
            if (truncated) {
                Log.w(TAG, "Collection scan stopped at the $maxNotes-note ceiling")
            }
            Scan(
                index = AnkiCollectionMatch.Index(sources.keys.toSet(), notes, available = true),
                wordSources = sources,
                wordsByNote = byNote,
                noteCount = notes,
                truncated = truncated,
                notesPerModel = notesPerModel
            )
        } catch (e: Exception) {
            // Older AnkiDroid builds, a revoked permission or a locked
            // collection all land here. The generator degrades to "no
            // duplicate check" and says so in the UI rather than failing.
            Log.w(TAG, "Collection scan failed for search='$search'", e)
            null
        }
    }

    /**
     * Model id → note type name. One extra provider query for the whole
     * collection; if it fails the scan still works, just without labels.
     */
    private fun loadModelNames(): Map<Long, String> = try {
        val out = HashMap<Long, String>()
        context.contentResolver.query(
            FlashCardsContract.Model.CONTENT_URI,
            arrayOf(FlashCardsContract.Model._ID, FlashCardsContract.Model.NAME),
            null,
            null,
            null
        )?.use { cursor ->
            val idIndex = cursor.getColumnIndex(FlashCardsContract.Model._ID)
            val nameIndex = cursor.getColumnIndex(FlashCardsContract.Model.NAME)
            if (idIndex >= 0 && nameIndex >= 0) {
                while (cursor.moveToNext()) {
                    val id = cursor.getString(idIndex)?.toLongOrNull() ?: continue
                    out[id] = cursor.getString(nameIndex).orEmpty()
                }
            }
        }
        out
    } catch (e: Exception) {
        Log.w(TAG, "Reading note types failed; scan continues without labels", e)
        emptyMap()
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

    companion object {
        private const val TAG = "AnkiCollectionIndex"

        /**
         * A live check reads at most this many notes. A word common enough to
         * appear in more (a one-kana particle in every example sentence) gets
         * a null answer instead of a guess.
         */
        private const val LIVE_MAX_NOTES = 5_000

        /** Anki search that matches every note in the collection. */
        private const val ALL_NOTES_SEARCH = "deck:*"

        /**
         * Anki's own definition of a mature card: an interval of 21 days or
         * more. Suspended cards are excluded — a suspended card is one the
         * user took out of rotation, whatever its interval says.
         */
        const val MATURE_SEARCH = "prop:ivl>=21 -is:suspended"

        /**
         * A card the user has actually started: not new, and not suspended.
         *
         * The middle claim between "I have a card for this" and "I know this".
         * A new card is a card that has never been shown — its word is in the
         * collection and means nothing yet — and a suspended one was taken out
         * of rotation on purpose, so both are out. That is the set a study list
         * should be built from, and it needs no toggle for the suspended half:
         * `-is:new -is:suspended` says it once.
         */
        const val STUDIED_SEARCH = "-is:new -is:suspended"
        /** Safety valve for very large collections. */
        private const val MAX_NOTES = 200_000
    }
}
