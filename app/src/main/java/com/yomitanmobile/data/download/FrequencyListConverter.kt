package com.yomitanmobile.data.download

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.InputStream
import java.util.zip.GZIPInputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Turns a word-frequency list published in someone else's format into a
 * Yomitan frequency dictionary, which the import path already knows how to
 * store, rank, roll up and show.
 *
 * Why convert: no English or Spanish frequency list exists in Yomitan's
 * format — the ecosystem is Japanese — while two good open ones exist in
 * their own: wordfreq's (a balanced mix of Wikipedia, books, subtitles, news
 * and social media) and the OpenSubtitles counts of FrequencyWords (spoken
 * language). Both CC BY-SA 4.0.
 *
 * No Android here, so it is tested on the real files on a desktop JVM.
 */
object FrequencyListConverter {

    enum class Format {
        /**
         * wordfreq's `cB` pack: gzip'd msgpack of `[header, bucket0, bucket1…]`,
         * each bucket the words one centibel rarer than the last. Order of
         * appearance is the rank.
         */
        WORDFREQ_MSGPACK,

        /** FrequencyWords: one `word count` pair per line, commonest first. */
        SUBTITLE_COUNTS
    }

    /** The words of [input], commonest first, cleaned — see [clean]. */
    fun rankedWords(format: Format, input: InputStream): List<String> = clean(
        when (format) {
            Format.WORDFREQ_MSGPACK -> wordfreqWords(GZIPInputStream(input.buffered()))
            Format.SUBTITLE_COUNTS -> input.bufferedReader(Charsets.UTF_8).lineSequence()
                .mapNotNull { it.trim().substringBefore(' ').takeIf(String::isNotEmpty) }
                .toList()
        }
    )

    /**
     * What a vocabulary card can be about: a token with a letter in it, not a
     * contraction's tail. Subtitle counts split "don't" into "don" and "'t",
     * and wordfreq keeps numbers and symbols; both would take a rank from a
     * real word. Ranks are renumbered after, so "Top 1K" means a thousand words.
     */
    internal fun clean(words: List<String>): List<String> {
        val seen = HashSet<String>(words.size * 2)
        return words.filter { word ->
            word.any { it.isLetter() } && !word.startsWith("'") && !word.startsWith("’") && seen.add(word)
        }
    }

    /**
     * The spellings a dictionary may list a word under. The lists are
     * lowercase; a dictionary writes "English", "Monday", "I", "TV". Without
     * the variants 11 702 headwords of the English-Polish Wiktionary could
     * never take a rank. Emitted here rather than folded in SQL, because a
     * case-insensitive join would slow the Japanese rollup for nothing.
     *
     * A variant another list word already claimed keeps that word's rank —
     * the list is walked commonest first.
     */
    internal fun spellings(word: String): List<String> {
        val out = LinkedHashSet<String>(3)
        out += word
        out += word.replaceFirstChar { it.titlecase() }
        if (word.length <= 4) out += word.uppercase()
        return out.toList()
    }

    /**
     * A Yomitan frequency dictionary (index.json + term_meta_bank_N.json) for
     * [words], rank = position in the list. [sourceLanguage] goes into
     * index.json, so the list lands in its language whatever the app was set
     * to while it was being installed.
     */
    fun toYomitanZip(
        title: String,
        revision: String,
        sourceLanguage: String,
        attribution: String,
        words: List<String>
    ): ByteArray {
        val rows = ArrayList<Pair<String, Int>>(words.size * 2)
        val claimed = HashSet<String>(words.size * 3)
        words.forEachIndexed { index, word ->
            for (spelling in spellings(word)) if (claimed.add(spelling)) rows += spelling to index + 1
        }
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            val index = buildJsonObject {
                put("title", title)
                put("revision", revision)
                put("format", 3)
                put("frequencyMode", "rank-based")
                put("sourceLanguage", sourceLanguage)
                put("attribution", attribution)
            }
            zip.putNextEntry(ZipEntry("index.json"))
            zip.write(index.toString().toByteArray())
            zip.closeEntry()
            rows.chunked(BANK_SIZE).forEachIndexed { bank, chunk ->
                zip.putNextEntry(ZipEntry("term_meta_bank_${bank + 1}.json"))
                val json = JsonArray(chunk.map { (spelling, rank) ->
                    JsonArray(listOf(JsonPrimitive(spelling), JsonPrimitive("freq"), JsonPrimitive(rank)))
                })
                zip.write(json.toString().toByteArray())
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private const val BANK_SIZE = 10_000

    // ---- wordfreq's msgpack, just enough of it ------------------------------

    private fun wordfreqWords(input: InputStream): List<String> {
        val reader = MsgPack(DataInputStream(input))
        val buckets = reader.arrayHeader()
        require(buckets >= 1) { "empty wordfreq pack" }
        reader.skip() // the header map: {"format": "cB", "version": 1}
        val words = ArrayList<String>(400_000)
        repeat(buckets - 1) {
            repeat(reader.arrayHeader()) { words += reader.string() }
        }
        return words
    }

    /** The subset of msgpack a wordfreq pack uses: arrays, maps, strings, ints. */
    internal class MsgPack(private val input: DataInputStream) {

        fun arrayHeader(): Int {
            val b = input.readUnsignedByte()
            return when {
                b and 0xF0 == 0x90 -> b and 0x0F
                b == 0xDC -> input.readUnsignedShort()
                b == 0xDD -> input.readInt()
                else -> error("expected a msgpack array, got 0x%02x".format(b))
            }
        }

        fun string(): String {
            val b = input.readUnsignedByte()
            val length = when {
                b and 0xE0 == 0xA0 -> b and 0x1F
                b == 0xD9 -> input.readUnsignedByte()
                b == 0xDA -> input.readUnsignedShort()
                b == 0xDB -> input.readInt()
                else -> error("expected a msgpack string, got 0x%02x".format(b))
            }
            val bytes = ByteArray(length)
            input.readFully(bytes)
            return String(bytes, Charsets.UTF_8)
        }

        /** Skips one value of any of the kinds a pack's header can hold. */
        fun skip() {
            val b = input.readUnsignedByte()
            when {
                b <= 0x7F || b >= 0xE0 || b == 0xC0 || b == 0xC2 || b == 0xC3 -> Unit
                b and 0xF0 == 0x80 -> repeat((b and 0x0F) * 2) { skip() }
                b and 0xF0 == 0x90 -> repeat(b and 0x0F) { skip() }
                b and 0xE0 == 0xA0 -> input.skipBytes(b and 0x1F)
                b == 0xCC || b == 0xD0 -> input.skipBytes(1)
                b == 0xCD || b == 0xD1 -> input.skipBytes(2)
                b == 0xCE || b == 0xD2 || b == 0xCA -> input.skipBytes(4)
                b == 0xCF || b == 0xD3 || b == 0xCB -> input.skipBytes(8)
                b == 0xD9 -> input.skipBytes(input.readUnsignedByte())
                b == 0xDA -> input.skipBytes(input.readUnsignedShort())
                b == 0xDC -> repeat(input.readUnsignedShort()) { skip() }
                b == 0xDE -> repeat(input.readUnsignedShort() * 2) { skip() }
                else -> error("unsupported msgpack type 0x%02x in the header".format(b))
            }
        }
    }
}
