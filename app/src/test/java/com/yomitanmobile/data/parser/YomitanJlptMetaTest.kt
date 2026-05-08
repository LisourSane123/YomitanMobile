package com.yomitanmobile.data.parser

import com.yomitanmobile.data.local.dao.FrequencyUpdate
import com.yomitanmobile.data.local.dao.JlptUpdate
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * The yomitan-jlpt-vocab dictionary smuggles JLPT levels through the
 * `term_meta_bank` `freq` channel: each entry's `frequency.value` is -1 and
 * `frequency.displayValue` is "N1"-"N5". The parser must route these to the
 * onJlptBatch callback rather than letting them pollute the frequency
 * column.
 */
class YomitanJlptMetaTest {

    @Test
    fun routesJlptDisplayValueToJlptBatch() = runBlocking {
        val metaJson = """
            [
              ["相",   "freq", {"reading": "あい",   "frequency": {"value": -1, "displayValue": "N1"}}],
              ["食べる", "freq", {"reading": "たべる", "frequency": {"value": -1, "displayValue": "N5"}}],
              ["昨日", "freq", {"reading": "きのう", "frequency": {"value": 1234, "displayValue": "1234"}}]
            ]
        """.trimIndent()

        val zipBytes = createZip(
            mapOf(
                "index.json" to "{\"title\":\"JLPT\",\"format\":\"3\",\"revision\":\"1\"}",
                "term_meta_bank_1.json" to metaJson
            )
        )

        val freqUpdates = mutableListOf<FrequencyUpdate>()
        val jlptUpdates = mutableListOf<JlptUpdate>()

        YomitanDictionaryParser().parseFromZipStreaming(
            inputStream = ByteArrayInputStream(zipBytes),
            onBatch = { _, _ -> },
            onMetaBatch = { freq, _ -> freqUpdates.addAll(freq) },
            onJlptBatch = { jlpt -> jlptUpdates.addAll(jlpt) }
        )

        // JLPT entries should land in jlptUpdates, NOT freqUpdates.
        assertEquals(2, jlptUpdates.size)
        val byExpr = jlptUpdates.associateBy { it.expression }
        assertEquals(1, byExpr["相"]!!.level)
        assertEquals("あい", byExpr["相"]!!.reading)
        assertEquals(5, byExpr["食べる"]!!.level)

        // Real frequency entries stay in the freq channel.
        assertEquals(1, freqUpdates.size)
        assertEquals("昨日", freqUpdates[0].expression)
        assertEquals(1234, freqUpdates[0].frequency)
    }

    @Test
    fun mixedJlptAndPlainFrequencyDoNotCrossWires() = runBlocking {
        // Confirms that a freq entry without displayValue still parses as a
        // plain frequency, and that a value:-1 with no displayValue is
        // dropped (parseFrequencyValue returns -1, which is filtered out).
        val metaJson = """
            [
              ["A", "freq", 100],
              ["B", "freq", {"value": 200}],
              ["C", "freq", {"reading": "c", "frequency": -1}],
              ["D", "freq", {"reading": "d", "frequency": {"value": -1, "displayValue": "N3"}}]
            ]
        """.trimIndent()

        val zipBytes = createZip(
            mapOf(
                "index.json" to "{\"title\":\"Mixed\",\"format\":\"3\",\"revision\":\"1\"}",
                "term_meta_bank_1.json" to metaJson
            )
        )

        val freqUpdates = mutableListOf<FrequencyUpdate>()
        val jlptUpdates = mutableListOf<JlptUpdate>()

        YomitanDictionaryParser().parseFromZipStreaming(
            inputStream = ByteArrayInputStream(zipBytes),
            onBatch = { _, _ -> },
            onMetaBatch = { freq, _ -> freqUpdates.addAll(freq) },
            onJlptBatch = { jlpt -> jlptUpdates.addAll(jlpt) }
        )

        val freqExprs = freqUpdates.map { it.expression }.toSet()
        assertTrue("A should be a freq update: $freqExprs", "A" in freqExprs)
        assertTrue("B should be a freq update: $freqExprs", "B" in freqExprs)
        assertTrue("C (negative) must NOT be a freq update: $freqExprs", "C" !in freqExprs)
        assertTrue("D must NOT be a freq update: $freqExprs", "D" !in freqExprs)

        assertEquals(1, jlptUpdates.size)
        assertEquals("D", jlptUpdates[0].expression)
        assertEquals(3, jlptUpdates[0].level)
    }

    private fun createZip(files: Map<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            files.forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }
}
