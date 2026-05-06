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

    @Test
    fun parseFromZipStreaming_extractsJlptTagsFromTermTags() = runBlocking {
        val parser = YomitanDictionaryParser()
        val parsedEntries = mutableListOf<com.yomitanmobile.data.local.entity.DictionaryEntry>()

        // Create test data with JLPT tag in termTags (field 7)
        val zipBytes = createZip(
            mapOf(
                "index.json" to "{\"title\":\"JLPTTest\",\"format\":\"3\",\"revision\":\"1\"}",
                "term_bank_1.json" to """[
                    ["食べる","たべる","verb, ichidan","v1",0,["to eat"],1,"jlpt-5, common"],
                    ["走る","はしる","verb","v5r",0,["to run"],2,"jlpt-4"],
                    ["学生","がくせい","noun","",0,["student"],3,"jlpt-3, common"]
                ]"""
            )
        )

        parser.parseFromZipStreaming(
            inputStream = ByteArrayInputStream(zipBytes),
            onBatch = { batch, _ -> 
                parsedEntries.addAll(batch)
            }
        )

        assertEquals(3, parsedEntries.size)
        
        // Verify JLPT tags are extracted and concatenated into partsOfSpeech
        val entry1 = parsedEntries[0]
        assertEquals("食べる", entry1.expression)
        assertTrue("Entry 1 partsOfSpeech should contain JLPT tag", 
            entry1.partsOfSpeech.contains("jlpt-5"))
        assertTrue("Entry 1 partsOfSpeech should contain other tags", 
            entry1.partsOfSpeech.contains("verb"))
        
        val entry2 = parsedEntries[1]
        assertEquals("走る", entry2.expression)
        assertTrue("Entry 2 partsOfSpeech should contain jlpt-4", 
            entry2.partsOfSpeech.contains("jlpt-4"))
        
        val entry3 = parsedEntries[2]
        assertEquals("学生", entry3.expression)
        assertTrue("Entry 3 partsOfSpeech should contain jlpt-3", 
            entry3.partsOfSpeech.contains("jlpt-3"))
        
        // Verify partsOfSpeech format is comma-separated as expected
        assertTrue("Format should be comma-separated", 
            entry1.partsOfSpeech.contains(", "))
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
