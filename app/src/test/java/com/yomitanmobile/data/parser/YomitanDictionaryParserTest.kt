package com.yomitanmobile.data.parser

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class YomitanDictionaryParserTest {

    @Test
    fun parseFromZipStreaming_usesTemporaryDictionaryNameForBatches() = runBlocking {
        val parser = YomitanDictionaryParser()
        val termNames = mutableListOf<String>()
        val kanjiNames = mutableListOf<String>()

        val zipBytes = createZip(
            mapOf(
                "index.json" to "{\"title\":\"Test Dict\",\"format\":\"3\",\"revision\":\"1\"}",
                "term_bank_1.json" to "[[\"撮る\",\"とる\",\"\",\"\",0,[\"to photograph\"],1,[]]]",
                "kanji_bank_1.json" to "[[\"撮\",\"サツ\",\"と.る\",\"\",[\"to photograph\"]]]"
            )
        )

        val result = parser.parseFromZipStreaming(
            inputStream = ByteArrayInputStream(zipBytes),
            onBatch = { batch, _ -> termNames.addAll(batch.map { it.dictionaryName }) },
            onKanjiBatch = { batch, _ -> kanjiNames.addAll(batch.map { it.dictionaryName }) }
        )

        assertEquals("Test Dict", result.dictionaryName)
        assertEquals(listOf("temp"), termNames)
        assertEquals(listOf("temp"), kanjiNames)
    }

    @Test
    fun parseFromZipStreaming_failsForArchiveWithoutSupportedFiles() {
        val parser = YomitanDictionaryParser()
        val zipBytes = createZip(
            mapOf("readme.txt" to "hello")
        )

        val exception = assertThrows(Exception::class.java) {
            runBlocking {
                parser.parseFromZipStreaming(
                    inputStream = ByteArrayInputStream(zipBytes),
                    onBatch = { _, _ -> }
                )
            }
        }

        assertTrue(exception.message?.contains("nie zawiera", ignoreCase = true) == true)
    }

    private fun createZip(files: Map<String, String>): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            files.forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
        return output.toByteArray()
    }
}
