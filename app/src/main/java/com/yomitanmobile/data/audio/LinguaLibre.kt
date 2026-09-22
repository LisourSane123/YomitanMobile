package com.yomitanmobile.data.audio

import com.yomitanmobile.domain.model.AppLanguage
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest

/**
 * Lingua Libre: words recorded by native speakers and published on Wikimedia
 * Commons under CC BY-SA 4.0 — 101 205 English recordings and 17 348 Spanish
 * ones, against a thousand Japanese (which is why Japanese has Kanji alive
 * instead, see [KanjiAlive]).
 *
 * Measured against English: 98% of the thousand commonest words have a
 * recording, 83% of the five thousand commonest, 46% of the English-Polish
 * Wiktionary's headwords.
 *
 * Too many files to download them all, so the pack is split in two. The INDEX
 * — which word has which file — comes from Lingua Libre's own database in one
 * SPARQL query (a few seconds, 16 MB before compression); walking Commons'
 * category instead took 220 requests and ran straight into its rate limit. A
 * RECORDING is fetched the first time its word is wanted and kept.
 *
 * The database is also why the index is exact: each record stores its word
 * (P7) apart from its file. A file name alone cannot be read back reliably —
 * `LL-Q1860 (eng)-Snowwsquire-in-house.wav` looks like "house" by a speaker
 * called "Snowwsquire-in", and is "in-house".
 *
 * No Android here, so the parsing and the choice of speaker are tested on the
 * real export on a desktop JVM.
 */
object LinguaLibre {

    const val SPARQL_ENDPOINT = "https://lingualibre.org/bigdata/namespace/wdq/sparql"

    /** The attribution CC BY-SA 4.0 asks for; each file names its speaker. */
    const val CREDIT = "Lingua Libre (lingualibre.org) via Wikimedia Commons, CC BY-SA 4.0 — speakers named in each file"

    /** Lingua Libre's own item for each language it can serve here. */
    fun languageItem(language: AppLanguage): String? = when (language) {
        AppLanguage.ENGLISH -> "Q22"
        AppLanguage.SPANISH -> "Q386"
        // Kanji alive serves Japanese: Lingua Libre has about a thousand words.
        AppLanguage.JAPANESE -> null
    }

    /** Every record of one language: word (P7), speaker (P5), Commons file (P3). */
    fun query(languageItem: String): String = """
        PREFIX prop: <https://lingualibre.org/prop/direct/>
        PREFIX entity: <https://lingualibre.org/entity/>
        SELECT ?t ?s ?f WHERE {
          ?r prop:P2 entity:Q2 ; prop:P4 entity:$languageItem ; prop:P7 ?t ; prop:P5 ?s ; prop:P3 ?f .
        }
    """.trimIndent()

    /** The endpoint URL for [query], as a plain GET. */
    fun queryUrl(languageItem: String): String =
        "$SPARQL_ENDPOINT?query=" + URLEncoder.encode(query(languageItem), "UTF-8")

    data class Record(val word: String, val speaker: String, val fileName: String)

    /**
     * The endpoint's TSV: a header, then `"word"\t<speaker IRI>\t<file IRI>`.
     * A literal may carry escapes (`\"`, `\\`) or a language tag; a row that
     * does not have all three columns is skipped rather than guessed at.
     */
    fun parseTsv(text: String): List<Record> = text.lineSequence().drop(1).mapNotNull { line ->
        val columns = line.split('\t')
        if (columns.size != 3) return@mapNotNull null
        val word = literal(columns[0]) ?: return@mapNotNull null
        val speaker = iri(columns[1])?.substringAfterLast('/') ?: return@mapNotNull null
        val file = iri(columns[2])?.substringAfterLast('/')
            ?.let { URLDecoder.decode(it.replace("+", "%2B"), "UTF-8") }
            ?: return@mapNotNull null
        if (word.isBlank() || file.isBlank()) null else Record(word.trim(), speaker, file)
    }.toList()

    private fun literal(value: String): String? {
        val v = value.trim()
        if (!v.startsWith('"')) return null
        val end = v.lastIndexOf('"')
        if (end <= 0) return null
        return v.substring(1, end).replace("\\\"", "\"").replace("\\\\", "\\")
    }

    private fun iri(value: String): String? {
        val v = value.trim()
        return if (v.startsWith('<') && v.endsWith('>')) v.substring(1, v.length - 1) else null
    }

    /**
     * Word → file. When several people recorded a word, the one who recorded
     * the MOST words overall says it: cards made over months then mostly share
     * a voice, instead of changing speaker from one card to the next. Ties go
     * to the lower speaker id, so every device builds the same index.
     */
    fun buildIndex(records: List<Record>): Index {
        val volume = records.groupingBy { it.speaker }.eachCount()
        val best = HashMap<String, Record>(records.size)
        for (record in records) {
            val current = best[record.word]
            if (current == null || better(record, current, volume)) best[record.word] = record
        }
        return Index(best.mapValues { it.value.fileName })
    }

    private fun better(a: Record, b: Record, volume: Map<String, Int>): Boolean {
        val va = volume[a.speaker] ?: 0
        val vb = volume[b.speaker] ?: 0
        if (va != vb) return va > vb
        if (a.speaker != b.speaker) return speakerNumber(a.speaker) < speakerNumber(b.speaker)
        return a.fileName < b.fileName
    }

    private fun speakerNumber(id: String): Long = id.removePrefix("Q").toLongOrNull() ?: Long.MAX_VALUE

    /** Word → Commons file name, case kept, with a case-folded fallback. */
    class Index(private val byWord: Map<String, String>) {
        /**
         * Case-folded fallback. A word recorded in lowercase owns its folded
         * key ("polish" the adjective); another casing ("Polish") only fills
         * a key nobody owns.
         */
        private val byLowercase: Map<String, String> by lazy {
            HashMap<String, String>().apply {
                for ((word, file) in byWord) if (word == word.lowercase()) put(word, file)
                for ((word, file) in byWord) putIfAbsent(word.lowercase(), file)
            }
        }

        val size: Int get() = byWord.size

        /**
         * The file that says [word]: the spelling as written first, then any
         * casing — a dictionary writes "Monday", a speaker may have recorded
         * "monday". The reading is not used: in these languages the word IS
         * how it is looked up.
         */
        fun find(word: String): String? {
            val w = word.trim()
            if (w.isEmpty()) return null
            return byWord[w] ?: byLowercase[w.lowercase()]
        }

        /** One `word\tfile` line per word — words and file names hold no tabs. */
        fun serialize(): String = buildString {
            for ((word, file) in byWord) append(word).append('\t').append(file).append('\n')
        }

        companion object {
            fun parse(text: String): Index = Index(
                text.lineSequence().mapNotNull { line ->
                    val tab = line.indexOf('\t')
                    if (tab <= 0) null else line.substring(0, tab) to line.substring(tab + 1)
                }.toMap()
            )
        }
    }

    /** Where Commons serves a file; it redirects to upload.wikimedia.org. */
    fun downloadUrl(fileName: String): String =
        "https://commons.wikimedia.org/wiki/Special:FilePath/" +
            URLEncoder.encode(fileName.replace(' ', '_'), "UTF-8").replace("+", "%20")

    /** A cache file name that is safe on any file system and stable per file. */
    fun cacheName(fileName: String): String {
        val digest = MessageDigest.getInstance("SHA-1").digest(fileName.toByteArray())
        val hash = digest.joinToString("") { "%02x".format(it) }.take(20)
        val extension = fileName.substringAfterLast('.', "wav").lowercase().filter { it.isLetterOrDigit() }.take(5)
        return "ll_$hash.$extension"
    }

    /** RIFF/WAVE, Ogg or MP3 — what a Commons audio file can be, and not an HTML error page. */
    fun looksLikeAudio(head: ByteArray): Boolean {
        if (head.size < 4) return false
        val ascii = String(head, 0, 4, Charsets.ISO_8859_1)
        return ascii == "RIFF" || ascii == "OggS" || ascii.startsWith("ID3") ||
            (head[0] == 0xFF.toByte() && (head[1].toInt() and 0xE0) == 0xE0)
    }
}
