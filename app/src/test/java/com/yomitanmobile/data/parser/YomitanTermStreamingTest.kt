package com.yomitanmobile.data.parser

import com.yomitanmobile.data.local.entity.DictionaryEntry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Term banks are decoded off the zip stream rather than read whole.
 *
 * The reason is not tidiness: reading a bank into one String capped the
 * parser at 48 MB per file, and the Wiktionary conversions the app offers
 * for download blow straight past that — kty-es-en's first bank is 76 MB and
 * kty-en-en's is 170 MB. Both used to fail the import with "file exceeds
 * limit" after the user had already waited through the download.
 */
class YomitanTermStreamingTest {

    @Test
    fun `a bank larger than one batch is emitted in several batches`() = runBlocking {
        val parser = YomitanDictionaryParser()
        val entries = (1..25_000).joinToString(",") { i ->
            """["word$i","word$i","","",0,["meaning $i"],$i,[]]"""
        }
        val zipBytes = createZip(
            mapOf(
                "index.json" to """{"title":"Big","format":"3","revision":"1"}""",
                "term_bank_1.json" to "[$entries]"
            )
        )

        val batchSizes = mutableListOf<Int>()
        val all = mutableListOf<DictionaryEntry>()
        val result = parser.parseFromZipStreaming(
            inputStream = ByteArrayInputStream(zipBytes),
            onBatch = { batch, _ ->
                batchSizes.add(batch.size)
                all.addAll(batch)
            }
        )

        // Nothing is lost or double-counted by the incremental emit.
        assertEquals(25_000, all.size)
        assertEquals(25_000, result.entriesCount)
        assertTrue(
            "expected several bounded batches, got $batchSizes",
            batchSizes.size > 1 && batchSizes.all { it <= 10_000 }
        )
    }

    @Test
    fun `form-of entries keep the base word and say which form it is`() = runBlocking {
        // The shape kty-es-en uses for the ~1.1M inflected headwords that
        // make up most of the file: the definition is not prose but a
        // reference to the base form plus grammatical tags.
        val parser = YomitanDictionaryParser()
        val zipBytes = createZip(
            mapOf(
                "index.json" to """{"title":"ES","format":"3","revision":"1","sourceLanguage":"es"}""",
                "term_bank_1.json" to
                    """[["hablando","hablando","","",0,[["hablar",["gerund"]]],1,[]]]"""
            )
        )

        val entries = mutableListOf<DictionaryEntry>()
        val result = parser.parseFromZipStreaming(
            inputStream = ByteArrayInputStream(zipBytes),
            onBatch = { batch, _ -> entries.addAll(batch) }
        )

        assertEquals("es", result.sourceLanguage)
        val definition = entries.single().definition
        assertTrue("base form missing from $definition", definition.contains("hablar"))
        assertTrue("form label missing from $definition", definition.contains("gerund"))
    }

    private fun createZip(files: Map<String, String>): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            files.forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray())
                zip.closeEntry()
            }
        }
        return output.toByteArray()
    }
}
