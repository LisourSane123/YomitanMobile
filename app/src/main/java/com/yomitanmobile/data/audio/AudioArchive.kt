package com.yomitanmobile.data.audio

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import com.yomitanmobile.data.local.dao.AudioFileDao
import com.yomitanmobile.data.local.entity.AudioFile
import com.yomitanmobile.data.settings.AudioArchiveSettings
import com.yomitanmobile.util.KanaScript
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The user's own folder of pronunciation files, and the lookup over it.
 *
 * Why a folder rather than a download: every usable Japanese audio archive
 * (jpod101, Forvo dumps, NHK) is either licensed material or scraped, and the
 * downloader only speaks to a GitHub allowlist. This is the same shape as the
 * 国語辞典 — the app knows how to read one, the user brings it.
 *
 * Why an index rather than a search: archives run to hundreds of thousands of
 * files, and SAF cannot ask "is there a file called 食べる.mp3" without walking
 * the tree. One walk when the folder is picked, a key lookup forever after.
 *
 * What the archive is FOR is in [AudioFile]: a TTS engine reads a headword
 * with no context, so it picks a plausible reading rather than the right one
 * and flattens the pitch accent the card prints above it.
 */
@Singleton
class AudioArchive @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dao: AudioFileDao,
    private val settings: AudioArchiveSettings,
    private val nativePack: NativeAudioPack
) {

    /** A file that pronounces the word asked for. */
    data class Match(val uri: Uri, val fileName: String)

    data class IndexResult(val files: Int, val keys: Int, val failed: Boolean = false)

    /** Distinct indexed files, for the settings screen. */
    fun observeFileCount(): Flow<Int> = dao.observeFileCount()

    suspend fun fileCount(): Int = withContext(Dispatchers.IO) {
        runCatching { dao.fileCount() }.getOrDefault(0)
    }

    suspend fun folderLabel(): String? = settings.folderLabel()

    /**
     * Takes persistable read permission on [treeUri] and walks it.
     *
     * The previous index is replaced only once the walk has produced files, so
     * a cancelled or unreadable pick leaves the working archive alone — the
     * same rule the Anki collection scan follows.
     */
    suspend fun index(treeUri: Uri): IndexResult = withContext(Dispatchers.IO) {
        try {
            context.contentResolver.takePersistableUriPermission(
                treeUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (e: SecurityException) {
            Log.w(TAG, "Could not persist permission for $treeUri", e)
        }

        val rows = ArrayList<AudioFile>()
        val files = try {
            walk(treeUri, rows)
        } catch (e: Exception) {
            Log.w(TAG, "Walking the archive failed", e)
            return@withContext IndexResult(0, 0, failed = true)
        }

        if (files == 0) return@withContext IndexResult(0, 0, failed = true)

        dao.clear()
        rows.chunked(CHUNK).forEach { dao.insertAll(it) }
        settings.setFolder(treeUri.toString(), labelOf(treeUri))
        IndexResult(files, rows.size)
    }

    /** Forgets the archive. The files themselves are never touched. */
    suspend fun forget() = withContext(Dispatchers.IO) {
        dao.clear()
        settings.clear()
    }

    /**
     * The best recording of a person saying the word, or null when there is
     * none — the caller then synthesises one.
     *
     * The user's own folder first: they chose it. Then the native-speaker
     * pack ([NativeAudioPack]), when it is installed. Every caller that wants
     * a word said comes through here, so this order is the app's order.
     *
     * Matching in the folder is deliberately generous about script: an
     * archive that spells a loanword's file in hiragana still answers for a
     * katakana headword.
     */
    suspend fun find(expression: String, reading: String): Match? =
        withContext(Dispatchers.IO) {
            val keys = AudioKeys.lookupKeys(expression, reading)
            if (keys.isEmpty()) return@withContext null
            runCatching { dao.findBest(keys) }.getOrNull()?.let { row ->
                return@withContext Match(Uri.parse(row.uri), row.fileName)
            }
            nativePack.find(expression, reading)?.let { file -> Match(Uri.fromFile(file), file.name) }
        }

    /**
     * Copies a match into the cache so it can be handed to AnkiDroid, which
     * cannot read a URI granted to this app. Null when the file is gone — an
     * archive on a removed SD card, or a folder the user has since moved.
     */
    suspend fun copyToCache(match: Match): File? = withContext(Dispatchers.IO) {
        try {
            val extension = match.fileName.substringAfterLast('.', "mp3")
            val dir = File(context.cacheDir, "anki_audio").apply { mkdirs() }
            val target = File(dir, "yomitan_archive_${match.uri.hashCode()}.$extension")
            context.contentResolver.openInputStream(match.uri)?.use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            } ?: return@withContext null
            target
        } catch (e: Exception) {
            Log.w(TAG, "Could not read ${match.fileName} out of the archive", e)
            null
        }
    }

    /** Depth-first walk over the SAF tree, appending rows. Returns file count. */
    private fun walk(treeUri: Uri, into: MutableList<AudioFile>): Int {
        val rootId = DocumentsContract.getTreeDocumentId(treeUri)
        var files = 0
        // An explicit stack rather than recursion: these archives nest a
        // folder per kana row per word and run several levels deep.
        val pending = ArrayDeque<Pair<String, String>>()
        pending.addLast(rootId to "")
        var visited = 0

        while (pending.isNotEmpty() && visited < MAX_DIRECTORIES) {
            val (documentId, parentName) = pending.removeFirst()
            visited++
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, documentId)
            context.contentResolver.query(
                children,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE
                ),
                null, null, null
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    val childId = cursor.getString(0)
                    val name = cursor.getString(1) ?: continue
                    val mime = cursor.getString(2) ?: ""
                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                        pending.addLast(childId to name)
                        continue
                    }
                    if (!isAudio(name)) continue
                    val rows = AudioKeys.keysFor(name, parentName)
                    if (rows.isEmpty()) continue
                    val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, childId)
                    files++
                    rows.forEach { (key, priority) ->
                        into += AudioFile(
                            key = key,
                            uri = uri.toString(),
                            fileName = name,
                            priority = priority
                        )
                    }
                }
            }
        }
        return files
    }

    private fun isAudio(name: String): Boolean =
        AUDIO_EXTENSIONS.any { name.endsWith(it, ignoreCase = true) }

    private fun labelOf(treeUri: Uri): String =
        DocumentsContract.getTreeDocumentId(treeUri).substringAfterLast('/')
            .ifBlank { treeUri.lastPathSegment.orEmpty() }

    companion object {
        private const val TAG = "AudioArchive"
        private const val CHUNK = 500

        /** A runaway tree (a whole SD card picked by mistake) stops here. */
        private const val MAX_DIRECTORIES = 50_000

        private val AUDIO_EXTENSIONS =
            listOf(".mp3", ".ogg", ".opus", ".m4a", ".aac", ".wav", ".flac")
    }
}

/**
 * Everything the archive guesses about file naming.
 *
 * Kept apart from the SAF walk so it can be tested on the JVM: this is the
 * part that breaks when someone brings an archive shaped differently, and
 * names are the only thing worth asserting on.
 *
 * There is no standard for those names, so both halves of a path are read and
 * neither is assumed to hold a particular thing:
 *
 *   食べる.mp3          → expression
 *   食べる_たべる.mp3    → expression + reading
 *   たべる - 食べる.mp3  → the same, written the other way round
 *   食べる/たべる.mp3    → folder is the expression, file the reading
 *
 * Every Japanese piece becomes a key of its own and a pair becomes a third,
 * stronger key. A bare reading is indexed and matched too — unlike the
 * dictionary lookup, where きく must not answer for 聞く, a homophone's
 * recording IS the right pronunciation of both.
 */
internal object AudioKeys {

    const val PRIORITY_PAIR = 0
    const val PRIORITY_EXPRESSION = 1
    const val PRIORITY_READING = 2

    private val SEPARATORS = arrayOf("_", "-", "–", "—", "・", "、", ",")

    /** Keys for one file, with their priorities. */
    fun keysFor(fileName: String, parentName: String): List<Pair<String, Int>> {
        val base = fileName.substringBeforeLast('.')
        return keysForPieces(splitPieces(base) + splitPieces(parentName))
    }

    /**
     * Keys for a recording whose word is KNOWN rather than read off a file
     * name — a pack that ships a word list beside romanised file names.
     * Same keys, same priorities as a file called `expression_reading`.
     */
    fun keysForWord(expression: String, reading: String): List<Pair<String, Int>> =
        keysForPieces(listOf(expression, reading))

    private fun keysForPieces(raw: List<String>): List<Pair<String, Int>> {
        val pieces = raw
            .map { it.trim() }
            .filter { it.isNotEmpty() && isJapanese(it) }
            .distinct()
        if (pieces.isEmpty()) return emptyList()

        val keys = LinkedHashMap<String, Int>()
        // A pair — whichever way round it was written — is the strongest claim
        // the file makes, so both orderings are stored and the lookup asks for
        // the one it wants.
        if (pieces.size >= 2) {
            for (a in pieces) for (b in pieces) {
                if (a == b) continue
                keys.putIfAbsent(normalize(pairKey(a, b)), PRIORITY_PAIR)
            }
        }
        for (piece in pieces) {
            val key = normalize(piece)
            // A kana-only piece is a reading, anything carrying kanji is a
            // spelling — and a spelling identifies a word far better, so it
            // outranks the reading when both are on offer.
            val priority = if (hasKanji(piece)) PRIORITY_EXPRESSION else PRIORITY_READING
            val existing = keys[key]
            if (existing == null || priority < existing) keys[key] = priority
        }
        return keys.map { it.key to it.value }
    }

    /** The keys a lookup asks for. Priority in the table decides the winner. */
    fun lookupKeys(expression: String, reading: String): List<String> {
        val keys = LinkedHashSet<String>()
        val expressions = variants(expression)
        val readings = variants(reading)
        // A kana headword is its own reading, and pairing its two scripts
        // against each other would ask for a key meaning "食べる written as
        // 食べる" — noise that no archive files anything under.
        if (expression.trim() != reading.trim()) {
            for (e in expressions) for (r in readings) {
                if (e.isBlank() || r.isBlank() || e == r) continue
                keys += normalize(pairKey(e, r))
            }
        }
        expressions.filter { it.isNotBlank() }.forEach { keys += normalize(it) }
        readings.filter { it.isNotBlank() }.forEach { keys += normalize(it) }
        // Bounded well under SQLite's 999-parameter ceiling: variants() returns
        // at most three spellings per side.
        return keys.toList()
    }

    /** Both scripts of a kana string, so archive and dictionary can disagree. */
    private fun variants(value: String): List<String> {
        val trimmed = value.trim()
        if (trimmed.isEmpty()) return emptyList()
        val out = LinkedHashSet<String>()
        out += trimmed
        if (trimmed.any { KanaScript.isKatakana(it) }) out += KanaScript.toHiragana(trimmed)
        if (trimmed.any { KanaScript.isHiragana(it) }) out += KanaScript.toKatakana(trimmed)
        return out.toList()
    }

    private fun splitPieces(value: String): List<String> =
        value.split(*SEPARATORS).flatMap { it.split(" - ") }

    private fun pairKey(first: String, second: String) = "$first\t$second"

    private fun normalize(value: String) = value.replace(" ", "").replace("　", "")

    private fun isJapanese(value: String) = value.any {
        KanaScript.isHiragana(it) || KanaScript.isKatakana(it) || isKanji(it)
    }

    fun hasKanji(value: String) = value.any { isKanji(it) }

    /** A spelling+reading key, as [keysFor] and [lookupKeys] build them. */
    fun isPairKey(key: String) = '\t' in key

    private fun isKanji(ch: Char) = ch in '一'..'鿿'
}
