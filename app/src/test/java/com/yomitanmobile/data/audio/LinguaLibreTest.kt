package com.yomitanmobile.data.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test
import java.io.File

class LinguaLibreTest {

    private val tsv = "?t\t?s\t?f\n" +
        "\"house\"\t<https://lingualibre.org/entity/Q10>\t<http://commons.wikimedia.org/wiki/Special:FilePath/LL-Q1860%20%28eng%29-Small-house.wav>\n" +
        "\"house\"\t<https://lingualibre.org/entity/Q20>\t<http://commons.wikimedia.org/wiki/Special:FilePath/LL-Q1860%20%28eng%29-Busy-house.wav>\n" +
        "\"in-house\"\t<https://lingualibre.org/entity/Q30>\t<http://commons.wikimedia.org/wiki/Special:FilePath/LL-Q1860%20%28eng%29-Snowwsquire-in-house.wav>\n" +
        "\"dog\"\t<https://lingualibre.org/entity/Q20>\t<http://commons.wikimedia.org/wiki/Special:FilePath/LL-Q1860%20%28eng%29-Busy-dog.wav>\n" +
        "\"cat\"\t<https://lingualibre.org/entity/Q20>\t<http://commons.wikimedia.org/wiki/Special:FilePath/LL-Q1860%20%28eng%29-Busy-cat.wav>\n" +
        "\"Monday\"\t<https://lingualibre.org/entity/Q10>\t<http://commons.wikimedia.org/wiki/Special:FilePath/LL-Q1860%20%28eng%29-Small-Monday.wav>\n" +
        "\"say \\\"hi\\\"\"\t<https://lingualibre.org/entity/Q10>\t<http://commons.wikimedia.org/wiki/Special:FilePath/LL-Q1860%20%28eng%29-Small-say%20hi.wav>\n" +
        "broken row\n"

    @Test
    fun `the export is read column by column, escapes and all`() {
        val records = LinguaLibre.parseTsv(tsv)
        assertEquals(7, records.size)
        assertEquals(LinguaLibre.Record("house", "Q10", "LL-Q1860 (eng)-Small-house.wav"), records.first())
        assertEquals("say \"hi\"", records.last().word)
    }

    @Test
    fun `the word is the record's own, not read off the file name`() {
        val index = LinguaLibre.buildIndex(LinguaLibre.parseTsv(tsv))
        // "Snowwsquire-in-house" is in-house, never house.
        assertEquals("LL-Q1860 (eng)-Snowwsquire-in-house.wav", index.find("in-house"))
        assertTrue(index.find("house")!!.endsWith("-house.wav") && !index.find("house")!!.contains("Snowwsquire"))
    }

    @Test
    fun `the speaker who recorded the most words says a word several recorded`() {
        val index = LinguaLibre.buildIndex(LinguaLibre.parseTsv(tsv))
        // Q10 and Q20 both recorded three words: a tie, which goes to the
        // lower id, so every device picks the same voice.
        assertEquals("LL-Q1860 (eng)-Small-house.wav", index.find("house"))
        // One more word for Q20 and the busier speaker takes it.
        val more = LinguaLibre.parseTsv(tsv + "\"cow\"\t<https://lingualibre.org/entity/Q20>\t<http://commons.wikimedia.org/wiki/Special:FilePath/LL-Q1860%20%28eng%29-Busy-cow.wav>\n")
        assertEquals("LL-Q1860 (eng)-Busy-house.wav", LinguaLibre.buildIndex(more).find("house"))
    }

    @Test
    fun `a dictionary's casing finds a recording made in another`() {
        val index = LinguaLibre.buildIndex(LinguaLibre.parseTsv(tsv))
        assertEquals("LL-Q1860 (eng)-Small-Monday.wav", index.find("monday"))
        assertEquals("LL-Q1860 (eng)-Busy-dog.wav", index.find("Dog"))
        assertNull(index.find("horse"))
    }

    @Test
    fun `the index survives being written and read back`() {
        val index = LinguaLibre.buildIndex(LinguaLibre.parseTsv(tsv))
        val again = LinguaLibre.Index.parse(index.serialize())
        assertEquals(index.size, again.size)
        for (w in listOf("house", "in-house", "Monday", "monday", "say \"hi\"")) assertEquals(index.find(w), again.find(w))
    }

    @Test
    fun `urls and cache names are safe`() {
        assertEquals(
            "https://commons.wikimedia.org/wiki/Special:FilePath/LL-Q1860_%28eng%29-Small-house.wav",
            LinguaLibre.downloadUrl("LL-Q1860 (eng)-Small-house.wav")
        )
        val name = LinguaLibre.cacheName("LL-Q1860 (eng)-Small-house.wav")
        assertTrue(name, Regex("ll_[0-9a-f]{20}\\.wav").matches(name))
        assertTrue(LinguaLibre.looksLikeAudio("RIFF....".toByteArray()))
        assertFalse(LinguaLibre.looksLikeAudio("<!DOCTYPE html>".toByteArray()))
    }

    /** The real export, when pointed at it: -Dll.tsv=<the SPARQL TSV>. */
    @Test
    fun `the real English export indexes`() {
        val file = File(System.getProperty("ll.tsv").orEmpty())
        Assume.assumeTrue(file.isFile)
        val records = LinguaLibre.parseTsv(file.readText())
        val index = LinguaLibre.buildIndex(records)
        println("LL export: ${records.size} records, ${index.size} words; house=${index.find("house")} English=${index.find("English")}")
        assertTrue(records.size > 100_000)
        assertTrue(index.find("house") != null && index.find("through") != null)
    }
}
