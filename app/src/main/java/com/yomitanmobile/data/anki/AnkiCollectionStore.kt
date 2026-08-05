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

    data class ScanSummary(
        val noteCount: Int,
        val wordCount: Int,
        val scannedAt: Long,
        /** False when the provider could not be read at all. */
        val available: Boolean
    ) {
        companion object {
            val UNAVAILABLE = ScanSummary(0, 0, 0L, available = false)
        }
    }

    private val cacheLock = Mutex()
    @Volatile
    private var cachedWords: Set<String>? = null

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
            val now = System.currentTimeMillis()
            val rows = scan.wordSources.map { (word, source) ->
                AnkiCollectionWord(word = word, source = source, scannedAt = now)
            }
            dao.replaceAll(rows)
            cacheLock.withLock { cachedWords = rows.mapTo(HashSet(rows.size)) { it.word } }
            ScanSummary(
                noteCount = scan.noteCount,
                wordCount = rows.size,
                scannedAt = now,
                available = true
            )
        }

    /** True when a scan has been stored at least once. */
    suspend fun hasStoredScan(): Boolean = words().isNotEmpty()

    /**
     * Whether the collection already contains this word. Mirrors
     * [AnkiCollectionIndex.Index.contains]: the written form matches directly,
     * and the reading only counts for kana-only words, where there is no kanji
     * form that could belong to a different word.
     */
    suspend fun contains(expression: String, reading: String): Boolean {
        val words = words()
        if (words.isEmpty()) return false
        val expr = AnkiNoteFieldIndexer.normalizeKey(expression)
        if (expr.isNotEmpty() && expr in words) return true
        val read = AnkiNoteFieldIndexer.normalizeKey(reading)
        if (read.isEmpty()) return false
        return (expr.isEmpty() || AnkiNoteFieldIndexer.isKanaOnly(expr)) && read in words
    }

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
        cacheLock.withLock { cachedWords = emptySet() }
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
