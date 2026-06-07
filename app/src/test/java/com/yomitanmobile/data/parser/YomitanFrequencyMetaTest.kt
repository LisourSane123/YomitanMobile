package com.yomitanmobile.data.parser

import com.yomitanmobile.data.local.dao.FrequencyUpdate
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Frequency `term_meta_bank` parsing across the three shapes the real lists
 * ship (verified against the downloaded BCCWJ + JPDB zips). The parser must
 * extract rank, reading, and the human-facing displayValue so the per-source
 * frequency table can attribute and label each entry.
 */
class YomitanFrequencyMetaTest {

    @Test
    fun parsesRankReadingAndDisplayValueForAllShapes() = runBlocking {
        val metaJson = """
            [
              ["の",   "freq", {"reading": "の", "frequency": 1}],
              ["は",   "freq", {"value": 2, "displayValue": "2"}],
              ["十数", "freq", {"reading": "じゅうすう", "frequency": {"value": 5002, "displayValue": "5002"}}]
            ]
        """.trimIndent()

        val zipBytes = createZip(
            mapOf(
                "index.json" to "{\"title\":\"BCCWJ\",\"format\":\"3\",\"revision\":\"1\"}",
                "term_meta_bank_1.json" to metaJson
            )
        )

        val freqUpdates = mutableListOf<FrequencyUpdate>()
        YomitanDictionaryParser().parseFromZipStreaming(
            inputStream = ByteArrayInputStream(zipBytes),
            onBatch = { _, _ -> },
            onMetaBatch = { freq, _ -> freqUpdates.addAll(freq) }
        )

        assertEquals(3, freqUpdates.size)
        val byExpr = freqUpdates.associateBy { it.expression }

        // BCCWJ shape: numeric frequency, reading present, no displayValue.
        assertEquals(1, byExpr["の"]!!.frequency)
        assertEquals("の", byExpr["の"]!!.reading)
        assertTrue(byExpr["の"]!!.displayValue.isBlank())

        // JPDB rank-based, no reading: displayValue carried through.
        assertEquals(2, byExpr["は"]!!.frequency)
        assertEquals("2", byExpr["は"]!!.displayValue)

        // JPDB nested frequency object with reading + displayValue.
        assertEquals(5002, byExpr["十数"]!!.frequency)
        assertEquals("じゅうすう", byExpr["十数"]!!.reading)
        assertEquals("5002", byExpr["十数"]!!.displayValue)
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
