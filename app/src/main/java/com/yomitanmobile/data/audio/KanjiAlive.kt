package com.yomitanmobile.data.audio

import com.yomitanmobile.data.download.VerifiedDownload
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * Kanji alive's example-word recordings: 10 156 words, each read by a native
 * Japanese speaker, published under CC BY 4.0 — which is what lets the app
 * download and ship them onto cards, where the archives Yomitan users usually
 * reach for (JapanesePod101, Forvo dumps, NHK) cannot be.
 *
 * No Android here: the phone and the desktop Kindle tool read the same word
 * list and build the same index, so a card sounds the same wherever it was
 * made.
 *
 * The recordings are named by kanji, not by word — `jutsu-no(beru)_06_c.aac`
 * is the third example word of 述 — so the word list (`ka_data.csv`) is what
 * says which file says which word: column `kname` is the file prefix, and the
 * examples are lettered a, b, c… in the order the column lists them. Checked
 * against the release: all 10 156 examples map onto a file, and no file is
 * left over.
 */
object KanjiAlive {

    /**
     * AAC, not the smaller Opus zip: Android plays Opus in an Ogg file only
     * from Android 10, and the recordings end up on cards that AnkiDroid
     * plays too — AAC (ADTS, LC, 32 kHz mono) plays on every Android the app
     * supports, on Anki desktop and on AnkiWeb, for 5 MB more.
     */
    const val AUDIO_ZIP_URL = "https://media.kanjialive.com/examples_audio/audio-aac.zip"
    const val AUDIO_ZIP_SHA256 = "31bf95b8e873af4ea8cdb3d80e4d2cd5298664004fb1011ec0d5fceabd9b3299"
    const val AUDIO_ZIP_BYTES = 74_094_640L

    const val WORD_LIST_URL =
        "https://raw.githubusercontent.com/kanjialive/kanji-data-media/master/language-data/ka_data.csv"
    const val WORD_LIST_SHA256 = "7b463876a0837bfb1f754a4e1bec43617b62f92b9839bbdf060d6ad0d8dbaf65"

    /** The attribution CC BY 4.0 asks for, shown wherever the recordings are offered. */
    const val CREDIT = "Kanji alive (kanjialive.com), CC BY 4.0"

    // ---- the installed pack ------------------------------------------------
    //
    // <root>/files/*.aac    the recordings, flat, under their release names
    // <root>/index.tsv      NativeAudioIndex, written LAST: its presence is
    //                       what "installed" means, so an install cut off at
    //                       any earlier step leaves nothing that looks usable

    private const val INDEX_FILE = "index.tsv"
    private const val FILES_DIR = "files"

    fun isInstalled(root: File): Boolean = File(root, INDEX_FILE).isFile

    /** The index of an installed pack, or null. */
    fun loadIndex(root: File): NativeAudioIndex? =
        File(root, INDEX_FILE).takeIf { it.isFile }?.let { NativeAudioIndex.parse(it.readText()) }

    /** Where an indexed recording lives. */
    fun file(root: File, fileName: String): File = File(File(root, FILES_DIR), fileName)

    /**
     * Downloads the word list and the recordings (both SHA-256-pinned), keeps
     * the files the word list names, and writes the index. [open] makes the
     * connection, so the caller decides which hosts are acceptable.
     */
    fun install(
        root: File,
        open: (String) -> InputStream,
        onProgress: (done: Long, total: Long) -> Unit = { _, _ -> }
    ) {
        root.mkdirs()
        val wordList = File(root, "ka_data.csv")
        VerifiedDownload.download(WORD_LIST_URL, WORD_LIST_SHA256, wordList, open)
        val zip = File(root, "audio-aac.zip")
        if (zip.length() != AUDIO_ZIP_BYTES) {
            VerifiedDownload.download(AUDIO_ZIP_URL, AUDIO_ZIP_SHA256, zip, open) { onProgress(it, AUDIO_ZIP_BYTES) }
        }
        onProgress(AUDIO_ZIP_BYTES, AUDIO_ZIP_BYTES)

        val recordings = recordings(wordList.readText())
        val wanted = recordings.mapTo(HashSet()) { it.fileName }
        val staging = File(root, "$FILES_DIR.partial").apply { deleteRecursively(); mkdirs() }
        val stagingRoot = staging.canonicalFile
        ZipInputStream(zip.inputStream().buffered()).use { input ->
            while (true) {
                val entry = input.nextEntry ?: break
                val name = entry.name.substringAfterLast('/')
                if (entry.isDirectory || name !in wanted) continue
                val target = File(stagingRoot, name).canonicalFile
                // The names come from our own word list, but a zip entry is
                // still not allowed to write outside the folder.
                if (target.parentFile != stagingRoot) error("zip entry escapes the folder: ${entry.name}")
                target.outputStream().use { input.copyTo(it) }
            }
        }
        val missing = wanted - (staging.list()?.toSet() ?: emptySet())
        if (missing.isNotEmpty()) error("${missing.size} recordings missing from the zip, e.g. ${missing.first()}")

        val files = File(root, FILES_DIR)
        files.deleteRecursively()
        if (!staging.renameTo(files)) error("could not move the recordings into place")
        val index = File(root, "$INDEX_FILE.partial")
        index.writeText(NativeAudioIndex.build(recordings).serialize())
        if (!index.renameTo(File(root, INDEX_FILE))) error("could not write the index")
        zip.delete()
    }

    /** Removes everything [install] put under [root]. */
    fun uninstall(root: File) {
        root.deleteRecursively()
    }

    /** One recording and the word it says. */
    data class Recording(
        val expression: String,
        val reading: String,
        /** The file inside the zip's `audio-aac/` folder. */
        val fileName: String
    )

    /**
     * Every recording the word list names, in its order.
     *
     * An example carrying two readings — 足跡（そくせき/あしあと） — is left out:
     * there is one recording and nothing says which reading it is, and a card
     * for あしあと must not say そくせき. 33 of 10 156 are like that; the other
     * 10 123 are kept, the irregular readings marked with * included.
     */
    fun recordings(csv: String): List<Recording> {
        val rows = parseCsv(csv)
        if (rows.isEmpty()) return emptyList()
        val header = rows.first()
        val prefix = header.indexOf("kname")
        val examples = header.indexOf("examples")
        require(prefix >= 0 && examples >= 0) { "ka_data.csv without kname/examples columns" }

        val out = ArrayList<Recording>(10_200)
        for (row in rows.drop(1)) {
            val stem = row.getOrNull(prefix)?.trim().orEmpty()
            val list = row.getOrNull(examples)?.trim().orEmpty()
            if (stem.isEmpty() || list.isEmpty()) continue
            val words = Json.parseToJsonElement(list).jsonArray
            words.forEachIndexed { index, example ->
                if (index >= LETTERS.length) return@forEachIndexed
                val text = example.jsonArray.firstOrNull()?.jsonPrimitive?.content.orEmpty()
                val match = EXAMPLE.matchEntire(text.trim()) ?: return@forEachIndexed
                // A leading * marks an irregular reading (*紅葉（もみじ）,
                // *二十歳（はたち）) — one word, one recording; only the mark goes.
                val expression = match.groupValues[1].trim().removePrefix("*").removePrefix("＊")
                val reading = match.groupValues[2].trim().removePrefix("*").removePrefix("＊")
                if (AMBIGUOUS.containsMatchIn(reading) || AMBIGUOUS.containsMatchIn(expression)) {
                    return@forEachIndexed
                }
                out += Recording(expression, reading, "${stem}_06_${LETTERS[index]}.$EXTENSION")
            }
        }
        return out
    }

    /** `一年生（いちねんせい）` — the word, then its reading in full-width parentheses. */
    private val EXAMPLE = Regex("""(.+?)（(.+?)）""")

    /** Two readings side by side: not one recording's word. */
    private val AMBIGUOUS = Regex("""[/／、,]""")

    private const val LETTERS = "abcdefghijkl"
    private const val EXTENSION = "aac"

    /** RFC 4180: quoted fields, "" inside quotes, commas and newlines allowed in them. */
    internal fun parseCsv(text: String): List<List<String>> {
        val rows = ArrayList<List<String>>()
        var row = ArrayList<String>()
        val field = StringBuilder()
        var quoted = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                quoted && c == '"' && i + 1 < text.length && text[i + 1] == '"' -> { field.append('"'); i++ }
                c == '"' -> quoted = !quoted
                !quoted && c == ',' -> { row += field.toString(); field.clear() }
                !quoted && (c == '\n' || c == '\r') -> {
                    if (c == '\r' && i + 1 < text.length && text[i + 1] == '\n') i++
                    row += field.toString(); field.clear()
                    if (row.any { it.isNotEmpty() }) rows += row
                    row = ArrayList()
                }
                else -> field.append(c)
            }
            i++
        }
        if (field.isNotEmpty() || row.isNotEmpty()) {
            row += field.toString()
            if (row.any { it.isNotEmpty() }) rows += row
        }
        return rows
    }
}
