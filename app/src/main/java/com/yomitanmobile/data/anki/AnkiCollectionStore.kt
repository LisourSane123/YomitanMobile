package com.yomitanmobile.data.anki

import android.util.Log
import com.yomitanmobile.data.local.dao.AnkiCollectionWordDao
import com.yomitanmobile.data.local.dao.AnkiSourceCount
import com.yomitanmobile.data.local.entity.AnkiCollectionWord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The app's answer to "do I already have a card for this word?".
 *
 * [AnkiCollectionIndex] can only answer that by reading the whole AnkiDroid
 * collection through a content provider — seconds of work, and impossible when
 * the permission was revoked or AnkiDroid is mid-sync. So the scan runs when
 * the user asks for it, the result is written to `anki_collection_words`, and
 * every later check (word detail screen, JLPT deck generator) reads the stored
 * copy through an in-memory set.
 *
 * The stored answer is deliberately conservative: an empty store means "not
 * checked", never "you have nothing", so a missing scan can never cause a
 * false "already have it" and silently swallow a card the user wanted.
 */
@Singleton
class AnkiCollectionStore @Inject constructor(
    private val index: AnkiCollectionIndex,
    private val dao: AnkiCollectionWordDao
) {

    /**
     * How much of the collection a question is about.
     *
     * [ANY] is the stored scan as it is — every word a card exists for, which
     * is what the duplicate check must use: a suspended or never-seen card is
     * still a card, and re-mining its word would make a pair.
     *
     * [STUDIED] and [MATURE] are for the opposite kind of question, the one a
     * study list asks. Both exclude suspended cards by construction (see
     * [AnkiCollectionIndex.STUDIED_SEARCH] / MATURE_SEARCH), so "leave the
     * suspended ones out" needs no separate switch.
     */
    enum class Scope { ANY, STUDIED, MATURE }

    data class ScanSummary(
        val noteCount: Int,
        val wordCount: Int,
        /** Of [wordCount], how many have a mature card. 0 on an old provider. */
        val matureWordCount: Int = 0,
        val scannedAt: Long,
        /** False when the provider could not be read at all. */
        val available: Boolean,
        /** True when the sweep hit its note ceiling and covered only part. */
        val truncated: Boolean = false,
        /**
         * Note types this app created that a collection should only ever have
         * one of, with how many notes each holds.
         *
         * A refused template write used to mint `Yomitan-Mobile-v8-1`,
         * `-2`… on every export; one real collection ended up with 1 113 of
         * them, 1 029 holding a single note. The cause is fixed, but the
         * damage stays until someone merges them, and nothing in the app could
         * even show it.
         */
        val strayNoteTypes: List<StrayNoteType> = emptyList()
    ) {
        companion object {
            val UNAVAILABLE = ScanSummary(0, 0, 0, 0L, available = false)
        }
    }

    /** One near-duplicate note type this app left behind. See [ScanSummary]. */
    data class StrayNoteType(val name: String, val notes: Int)

    private val cacheLock = Mutex()
    @Volatile
    private var cachedWords: Set<String>? = null
    @Volatile
    private var cachedMature: Set<String>? = null
    private var cachedStudied: Set<String>? = null

    /** Word count of the stored scan, for badges and settings rows. */
    fun observeWordCount(): Flow<Int> = dao.observeCount()

    /**
     * Rescans the collection and replaces the stored copy.
     *
     * A scan that comes back unavailable (no permission, provider refused)
     * leaves the previous result untouched — the old data is stale at worst,
     * while wiping it would turn every known duplicate back into a new card.
     */
    suspend fun refresh(deckNames: List<String> = emptyList()): ScanSummary =
        withContext(Dispatchers.IO) {
            val scan = index.scan(deckNames)
            if (!scan.index.available) {
                Log.w(TAG, "Collection scan unavailable; keeping the previous result")
                return@withContext ScanSummary.UNAVAILABLE
            }
            // Two more sweeps, and both are cheap because the scan above
            // already knows which words each NOTE holds: they ask the provider
            // for ids alone instead of reading and re-indexing every note's
            // fields a second and third time.
            suspend fun sweep(name: String, search: String): Set<String> =
                runCatching { index.scanState(search, scan.wordsByNote, deckNames) }
                    .getOrElse {
                        Log.w(TAG, "$name sweep failed; claiming nothing", it)
                        emptySet()
                    }
            val mature = sweep("Maturity", AnkiCollectionIndex.MATURE_SEARCH)
            val studied = sweep("Started-cards", AnkiCollectionIndex.STUDIED_SEARCH)

            val now = System.currentTimeMillis()
            val rows = scan.wordSources.map { (word, source) ->
                AnkiCollectionWord(
                    word = word,
                    source = source,
                    scannedAt = now,
                    mature = word in mature,
                    studied = word in studied || word in mature
                )
            }
            dao.replaceAll(rows)
            cacheLock.withLock {
                cachedWords = rows.mapTo(HashSet(rows.size)) { it.word }
                cachedMature = null
                cachedStudied = null
            }
            ScanSummary(
                noteCount = scan.noteCount,
                wordCount = rows.size,
                matureWordCount = rows.count { it.mature },
                scannedAt = now,
                available = true,
                truncated = scan.truncated,
                strayNoteTypes = strayNoteTypes(scan.notesPerModel)
            )
        }

    /**
     * Note types of ours beyond the one there should be, worst first.
     *
     * Matched by name rather than by field list: the strays are exactly the
     * ones whose name is the canonical one plus a numeric suffix, because that
     * is how AnkiDroid names a model it was asked to create twice.
     */
    private fun strayNoteTypes(notesPerModel: Map<String, Int>): List<StrayNoteType> {
        val canonical = com.yomitanmobile.domain.model.CardProfile.entries.map { it.modelName }
        return notesPerModel
            .filterKeys { name ->
                canonical.any { base -> name != base && name.startsWith("$base-") }
            }
            .map { (name, notes) -> StrayNoteType(name, notes) }
            .sortedByDescending { it.notes }
    }

    /**
     * Records words this app just wrote into AnkiDroid, so the stored scan
     * stays true without a full rescan.
     *
     * The bulk generators deliberately keep their cards out of
     * `exported_words` (they are not mined, and counting them would swamp the
     * mining statistics), which left the collection scan as the ONLY thing
     * that knows those words exist — and it does not, until the user happens
     * to rescan. Generating a level twice therefore produced a deck full of
     * cards the user had just created. Appending here closes that window.
     */
    suspend fun addWords(words: Collection<String>, source: String) =
        withContext(Dispatchers.IO) {
            if (words.isEmpty()) return@withContext
            // Nothing stored yet means "never scanned", and that is a state
            // the rest of the code reads as "no duplicate check". Writing a
            // handful of generated words would turn it into "scanned, and your
            // collection contains exactly these" — a far worse lie.
            if (!hasStoredScan()) return@withContext
            val now = System.currentTimeMillis()
            val rows = words
                .map { AnkiNoteFieldIndexer.normalizeKey(it) }
                .filter { it.isNotEmpty() }
                .distinct()
                .map { AnkiCollectionWord(word = it, source = source, scannedAt = now) }
            runCatching { dao.insertAll(rows) }
                .onFailure { Log.w(TAG, "Recording generated words failed", it) }
                .onSuccess {
                    cacheLock.withLock {
                        cachedWords = cachedWords?.plus(rows.map { row -> row.word })
                    }
                }
        }

    /**
     * What the stored scan knows, for screens that want to say whether the
     * duplicate check had anything to work with. Word count, not note count:
     * one note yields both its written form and its reading.
     */
    suspend fun storedScanInfo(): StoredScanInfo = withContext(Dispatchers.IO) {
        runCatching { StoredScanInfo(dao.count(), dao.lastScannedAt()) }
            .getOrElse { StoredScanInfo(0, 0L) }
    }

    data class StoredScanInfo(val wordCount: Int, val scannedAt: Long)

    /** True when a scan has been stored at least once. */
    suspend fun hasStoredScan(): Boolean = words().isNotEmpty()

    /**
     * Whether the collection already contains this word. Mirrors
     * [AnkiCollectionIndex.Index.contains]: the written form matches directly,
     * and the reading only counts for kana-only words, where there is no kanji
     * form that could belong to a different word.
     */
    suspend fun contains(
        expression: String,
        reading: String,
        readingCountsAlone: Boolean = false
    ): Boolean = containsAny(listOf(expression), reading, readingCountsAlone)

    /**
     * Same check across every written form of the word — see
     * [AnkiCollectionIndex.Index.containsAny]. The primary spelling goes
     * first.
     */
    suspend fun containsAny(
        expressions: List<String>,
        reading: String,
        readingCountsAlone: Boolean = false
    ): Boolean {
        val words = words()
        if (words.isEmpty()) return false
        return AnkiCollectionIndex.Index(words, 0, available = true)
            .containsAny(expressions, reading, readingCountsAlone)
    }

    /**
     * The check to run right before writing ONE card: AnkiDroid asked
     * directly ([AnkiCollectionIndex.liveContainsAny]), the stored copy only
     * when it cannot answer.
     *
     * [containsAny] alone is only as current as the last scan, and an empty
     * store answers "no" to everything — so after a reinstall, before a first
     * scan, or for a card added since from another device, mining let the word
     * through a second time. The live question costs one provider query.
     */
    suspend fun containsAnyNow(
        expressions: List<String>,
        reading: String,
        readingCountsAlone: Boolean = false
    ): Boolean = index.liveContainsAny(expressions, reading, readingCountsAlone)
        ?: containsAny(expressions, reading, readingCountsAlone)

    /** Non-suspending variant for callers that already loaded the set. */
    suspend fun asIndex(): AnkiCollectionIndex.Index {
        val words = words()
        return AnkiCollectionIndex.Index(
            keys = words,
            noteCount = 0,
            available = words.isNotEmpty()
        )
    }

    suspend fun page(limit: Int, offset: Int): List<AnkiCollectionWord> =
        withContext(Dispatchers.IO) { runCatching { dao.getPage(limit, offset) }.getOrDefault(emptyList()) }

    suspend fun search(query: String, limit: Int = 200): List<AnkiCollectionWord> =
        withContext(Dispatchers.IO) {
            runCatching { dao.search(query.trim(), limit) }.getOrDefault(emptyList())
        }

    suspend fun countsBySource(): List<AnkiSourceCount> =
        withContext(Dispatchers.IO) { runCatching { dao.countsBySource() }.getOrDefault(emptyList()) }

    suspend fun clear() = withContext(Dispatchers.IO) {
        runCatching { dao.deleteAll() }
        cacheLock.withLock {
            cachedWords = emptySet()
            cachedMature = emptySet()
            cachedStudied = emptySet()
        }
    }

    /**
     * Words the collection holds a MATURE card for. See [AnkiCollectionWord].
     *
     * Empty means "nothing is known to be mature", which is also what an older
     * provider and a pre-maturity scan produce — so callers use it to state a
     * second figure, never to decide that a word is missing.
     */
    suspend fun matureWords(): Set<String> {
        cachedMature?.let { return it }
        return cacheLock.withLock {
            cachedMature ?: withContext(Dispatchers.IO) {
                runCatching { dao.getMatureWords().toHashSet() }
                    .getOrElse {
                        Log.w(TAG, "Reading mature words failed", it)
                        emptySet()
                    }
            }.also { cachedMature = it }
        }
    }

    /**
     * Words on a card that has been started — neither new nor suspended. Empty
     * when the provider would not answer that search, exactly like
     * [matureWords]; a caller states a figure with it, never withholds one.
     */
    suspend fun studiedWords(): Set<String> {
        cachedStudied?.let { return it }
        return cacheLock.withLock {
            cachedStudied ?: withContext(Dispatchers.IO) {
                runCatching { dao.getStudiedWords().toHashSet() }
                    .getOrElse {
                        Log.w(TAG, "Reading started-card words failed", it)
                        emptySet()
                    }
            }.also { cachedStudied = it }
        }
    }

    private suspend fun words(scope: Scope): Set<String> = when (scope) {
        Scope.ANY -> words()
        Scope.STUDIED -> studiedWords()
        Scope.MATURE -> matureWords()
    }

    /**
     * Every kanji that appears in a word the collection holds.
     *
     * "Known" here means exactly what the stored scan means elsewhere: there
     * is a card carrying this character. It is not a claim about recall — see
     * the note on the scan itself — but it is the only honest answer the app
     * can give without reading review history.
     */
    suspend fun knownKanji(matureOnly: Boolean = false): Set<String> =
        knownKanji(if (matureOnly) Scope.MATURE else Scope.ANY)

    suspend fun knownKanji(scope: Scope): Set<String> =
        kanjiTally(scope).counts.mapTo(HashSet()) { it.kanji }

    /**
     * Every kanji in the stored scan with how many of the user's words carry
     * it — see [KanjiTally]. One definition of "the kanji in my collection",
     * shared with [knownKanji], which is this list without the numbers.
     *
     * Reads the same cached word set, so asking for the counts costs a pass
     * over words already in memory.
     */
    suspend fun kanjiTally(matureOnly: Boolean = false): KanjiTally.KanjiTallyResult =
        kanjiTally(if (matureOnly) Scope.MATURE else Scope.ANY)

    suspend fun kanjiTally(scope: Scope): KanjiTally.KanjiTallyResult {
        val all = words(scope)
        if (all.isEmpty()) return KanjiTally.KanjiTallyResult.EMPTY
        return KanjiTally.of(all)
    }

    private suspend fun words(): Set<String> {
        cachedWords?.let { return it }
        return cacheLock.withLock {
            cachedWords ?: withContext(Dispatchers.IO) {
                runCatching { dao.getAllWords().toHashSet() }
                    .getOrElse {
                        Log.w(TAG, "Reading the stored collection scan failed", it)
                        emptySet()
                    }
            }.also { cachedWords = it }
        }
    }

    private companion object {
        const val TAG = "AnkiCollectionStore"
    }
}
