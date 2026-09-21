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
        /**
         * @param readingCountsAlone the word is normally written in kana (see
         * [com.yomitanmobile.domain.usecase.WordFilterRules.isUsuallyKana]), so
         * its reading identifies it even though the candidate carries a kanji
         * spelling. Without this, 下さい looked missing to a collection holding
         * ください and the generator made a card the user already had.
         */
        fun contains(
            expression: String,
            reading: String,
            readingCountsAlone: Boolean = false
        ): Boolean = containsAny(listOf(expression), reading, readingCountsAlone)

        /**
         * Same question for a word that has several written forms.
         *
         * One dictionary entry carries every spelling of the word (JMdict
         * lists 持って来る, 持ってくる and もって来る together) while the deck
         * holds whichever one its author happened to type. Comparing only the
         * primary headword therefore reported "not in your collection" for
         * compound verbs the user had been studying for months — the mixed
         * kanji/kana spellings are exactly where decks disagree.
         *
         * @param expressions the word's written forms, the primary one FIRST:
         * it is the one that decides whether a bare reading match is allowed.
         */
        fun containsAny(
            expressions: List<String>,
            reading: String,
            readingCountsAlone: Boolean = false
        ): Boolean {
            if (!available) return false
            val read = AnkiNoteFieldIndexer.normalizeKey(reading)
            val spellings = expressions
                .map { AnkiNoteFieldIndexer.normalizeKey(it) }
                .filter { it.isNotEmpty() }

            for (expr in spellings) {
                if (expr in keys) return true
                if (read.isEmpty()) continue
                // Mixed spellings of the SAME word, derived from the reading:
                // 持って来る + もってくる also means 持ってくる and もって来る.
                // They still carry a kanji block, so they identify the word as
                // precisely as the headword does — unlike the bare reading,
                // which stays subject to the homophone rule below.
                for (variant in KanaSpellingVariants.of(expr, read)) {
                    if (variant != read && variant in keys) return true
                }
            }

            if (read.isEmpty() || read !in keys) return false
            // Otherwise the reading only counts when no kanji form could point
            // at a different word: a kana-only headword, or a word the
            // dictionary says is normally written in kana anyway.
            val primary = spellings.firstOrNull().orEmpty()
            val kanjiFormIsDecisive = primary.isNotEmpty() &&
                !AnkiNoteFieldIndexer.isKanaOnly(primary) &&
                !readingCountsAlone
            return !kanjiFormIsDecisive
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
    ): Index = scan(deckNames, maxNotes).index

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
        val index: Index,
        val wordSources: Map<String, String>,
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
            val EMPTY = Scan(Index.EMPTY, emptyMap(), 0)
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
     * same [AnkiNoteFieldIndexer] + [Index.containsAny] the full scan uses
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
        val search = liveSearch(expressions, reading) ?: return@withContext null
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
    ): Set<String> = withContext(Dispatchers.IO) {
        if (!hasPermission()) return@withContext emptySet()
        val decks = buildSearches(deckNames).firstOrNull { !it.isNullOrEmpty() && it != ALL_NOTES_SEARCH }
        val search = listOfNotNull(decks, MATURE_SEARCH).joinToString(" ")
        val scan = scanNotes(search, maxNotes) ?: return@withContext emptySet()
        Log.i(TAG, "Mature sweep: ${scan.noteCount} notes -> ${scan.wordCount} word keys")
        scan.wordSources.keys
    }

    /** Runs one search; null means the provider refused it. */
    private fun scanNotes(search: String?, maxNotes: Int): Scan? {
        val sources = LinkedHashMap<String, String>(4096)
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
                Index(sources.keys.toSet(), notes, available = true),
                sources,
                notes,
                truncated,
                notesPerModel
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

        /**
         * The Anki search [liveContainsAny] runs, or null when there is
         * nothing to search for.
         *
         * Anki's search matches raw field text, and a deck may hold the word
         * as ruby — 持[も]って 来[く]る — where "持って来る" is not a
         * substring. So a spelling with kanji is searched as its kanji, each
         * required (`("持" "来")`), which every ruby and plain form of it
         * contains; a kana spelling, and the reading, as themselves. The
         * indexer then decides on whole fields, so the breadth only costs a
         * few more notes read.
         */
        internal fun liveSearch(expressions: List<String>, reading: String): String? {
            val clauses = LinkedHashSet<String>()
            for (raw in expressions + reading) {
                val word = AnkiNoteFieldIndexer.normalizeKey(raw)
                if (word.isEmpty()) continue
                val kanji = word.filter { AnkiNoteFieldIndexer.isKanji(it) }.toSet()
                clauses += if (kanji.isEmpty()) {
                    quote(word)
                } else {
                    kanji.joinToString(" ", prefix = "(", postfix = ")") { quote(it.toString()) }
                }
            }
            return clauses.takeIf { it.isNotEmpty() }?.joinToString(" OR ")
        }

        /** An Anki search term matching [text] literally anywhere in a field. */
        private fun quote(text: String): String {
            // Inside quotes Anki still reads * and _ as wildcards and \ as an
            // escape; a dictionary word never contains them, but a stray one
            // must not widen the search.
            val escaped = text.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("*", "\\*").replace("_", "\\_")
            return "\"$escaped\""
        }

        /** Anki search that matches every note in the collection. */
        private const val ALL_NOTES_SEARCH = "deck:*"

        /**
         * Anki's own definition of a mature card: an interval of 21 days or
         * more. Suspended cards are excluded — a suspended card is one the
         * user took out of rotation, whatever its interval says.
         */
        private const val MATURE_SEARCH = "prop:ivl>=21 -is:suspended"
        /** Safety valve for very large collections. */
        private const val MAX_NOTES = 200_000
    }
}
