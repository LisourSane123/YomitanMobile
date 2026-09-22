package com.yomitanmobile.data.download

import com.yomitanmobile.data.local.dao.FrequencyUpdate
import com.yomitanmobile.data.parser.YomitanDictionaryParser
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.GZIPOutputStream

class FrequencyListConverterTest {

    /** What the app's own importer reads back out of a converted list. */
    private fun imported(zip: ByteArray): Pair<Map<String, Int>, String?> = runBlocking {
        val ranks = HashMap<String, Int>()
        val result = YomitanDictionaryParser().parseFromZipStreaming(
            inputStream = zip.inputStream(),
            onBatch = { _, _ -> },
            onMetaBatch = { updates: List<FrequencyUpdate>, _ -> updates.forEach { ranks[it.expression] = it.frequency } }
        )
        ranks to result.sourceLanguage
    }

    @Test
    fun `subtitle counts become ranks, contraction tails and numbers dropped`() {
        val text = "you 28787591\ni 27086011\n's 14291013\nthe 22761659\n't 9628970\n2 900000\nof 8915110\n"
        val words = FrequencyListConverter.rankedWords(FrequencyListConverter.Format.SUBTITLE_COUNTS, text.byteInputStream())
        assertEquals(listOf("you", "i", "the", "of"), words)
    }

    @Test
    fun `a wordfreq pack is read bucket by bucket, header skipped`() {
        // [ {"format": "cB", "version": 1}, ["the", "a"], [], ["dog"] ]
        val pack = byteArrayOf(0x94.toByte()) +
            byteArrayOf(0x82.toByte()) + str("format") + str("cB") + str("version") + byteArrayOf(0x01) +
            byteArrayOf(0x92.toByte()) + str("the") + str("a") +
            byteArrayOf(0x90.toByte()) +
            byteArrayOf(0x91.toByte()) + str("dog")
        val gz = ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(pack) } }.toByteArray()
        val words = FrequencyListConverter.rankedWords(FrequencyListConverter.Format.WORDFREQ_MSGPACK, gz.inputStream())
        assertEquals(listOf("the", "a", "dog"), words)
    }

    @Test
    fun `the importer reads the converted list, with the dictionary's spellings`() {
        val zip = FrequencyListConverter.toYomitanZip("wordfreq (EN)", "test", "en", "CC BY-SA 4.0", listOf("i", "tv", "english", "monday"))
        val (ranks, language) = imported(zip)
        assertEquals("en", language)
        assertEquals(1, ranks["i"])
        assertEquals(1, ranks["I"])
        assertEquals(2, ranks["TV"])
        assertEquals(3, ranks["English"])
        assertEquals(4, ranks["Monday"])
        // Long words get no all-caps form: ENGLISH is no headword.
        assertTrue("ENGLISH" !in ranks)
    }

    @Test
    fun `a spelling another word already claimed keeps the commoner word's rank`() {
        // "us" (rank 1) claims "US"; the later "US" in the list does not move it.
        val zip = FrequencyListConverter.toYomitanZip("t", "t", "en", "", listOf("us", "dog", "US"))
        assertEquals(1, imported(zip).first["US"])
    }

    private fun str(s: String): ByteArray {
        val bytes = s.toByteArray()
        return byteArrayOf((0xA0 or bytes.size).toByte()) + bytes
    }

    /**
     * The real release files, when pointed at them:
     * `-Dfreq.src.dir=<dir with large_en.msgpack.gz, en_50k.txt>`.
     */
    @Test
    fun `the real lists convert and read back`() {
        val dir = File(System.getProperty("freq.src.dir").orEmpty())
        Assume.assumeTrue(File(dir, "large_en.msgpack.gz").isFile && File(dir, "en_50k.txt").isFile)
        val wordfreq = File(dir, "large_en.msgpack.gz").inputStream().use {
            FrequencyListConverter.rankedWords(FrequencyListConverter.Format.WORDFREQ_MSGPACK, it)
        }
        val subtitles = File(dir, "en_50k.txt").inputStream().use {
            FrequencyListConverter.rankedWords(FrequencyListConverter.Format.SUBTITLE_COUNTS, it)
        }
        assertEquals(listOf("the", "to", "and", "of", "a"), wordfreq.take(5))
        assertEquals(listOf("you", "i", "the", "to", "a"), subtitles.take(5))
        val (ranks, _) = imported(FrequencyListConverter.toYomitanZip("wordfreq (EN)", "t", "en", "", wordfreq))
        println("wordfreq: ${wordfreq.size} words, ${ranks.size} spellings; English=${ranks["English"]} TV=${ranks["TV"]}")
        assertTrue(ranks.size > wordfreq.size)
    }
}
