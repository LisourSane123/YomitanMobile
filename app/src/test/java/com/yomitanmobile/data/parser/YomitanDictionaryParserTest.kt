package com.yomitanmobile.data.parser

import com.yomitanmobile.domain.model.MeaningBlock
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
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

    private val json = Json { ignoreUnknownKeys = true }

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

    @Test
    fun parseFromZipStreaming_extractsMeaningBlocksAndExamplesFromJitendexStructuredContent() = runBlocking {
        val parser = YomitanDictionaryParser()
        val parsedEntries = mutableListOf<com.yomitanmobile.data.local.entity.DictionaryEntry>()

        val zipBytes = createZip(
            mapOf(
                "index.json" to "{\"title\":\"JitendexTest\",\"format\":\"3\",\"revision\":\"1\"}",
                "term_bank_1.json" to """[
                    [
                        "母語話者",
                        "ぼごわしゃ",
                        "",
                        "",
                        0,
                        [
                            {
                                "type": "structured-content",
                                "content": [
                                    {
                                        "tag": "div",
                                        "data": { "content": "sense-group" },
                                        "content": [
                                            {
                                                "tag": "span",
                                                "data": { "class": "tag", "code": "n", "content": "part-of-speech-info" },
                                                "content": "noun"
                                            },
                                            {
                                                "tag": "div",
                                                "data": { "content": "sense" },
                                                "content": [
                                                    {
                                                        "tag": "ul",
                                                        "data": { "content": "glossary" },
                                                        "content": { "tag": "li", "content": "native speaker" }
                                                    },
                                                    {
                                                        "tag": "div",
                                                        "data": { "content": "extra-info" },
                                                        "content": {
                                                            "tag": "div",
                                                            "data": { "class": "extra-box", "content": "example-sentence" },
                                                            "content": [
                                                                {
                                                                    "tag": "div",
                                                                    "data": { "content": "example-sentence-a" },
                                                                    "content": {
                                                                        "tag": "span",
                                                                        "lang": "ja",
                                                                        "content": [
                                                                            {
                                                                                "tag": "ruby",
                                                                                "content": ["彼", { "tag": "rt", "content": "かれ" }]
                                                                            },
                                                                            "が英語を話します。"
                                                                        ]
                                                                    }
                                                                },
                                                                {
                                                                    "tag": "div",
                                                                    "data": { "content": "example-sentence-b" },
                                                                    "content": {
                                                                        "tag": "span",
                                                                        "lang": "en",
                                                                        "content": "He speaks English."
                                                                    }
                                                                }
                                                            ]
                                                        }
                                                    }
                                                ]
                                            }
                                        ]
                                    }
                                ]
                            }
                        ],
                        1921830,
                        ""
                    ]
                ]"""
            )
        )

        parser.parseFromZipStreaming(
            inputStream = ByteArrayInputStream(zipBytes),
            onBatch = { batch, _ -> parsedEntries.addAll(batch) }
        )

        assertEquals(1, parsedEntries.size)

        val entry = parsedEntries.first()
        assertTrue(entry.partsOfSpeech.contains("n"))

        val blocks = json.decodeFromString(ListSerializer(MeaningBlock.serializer()), entry.definition)
        assertEquals(1, blocks.size)
        assertTrue(blocks.first().meaning.contains("native speaker"))
        assertTrue(blocks.first().examples.first().sentenceHtml.contains("<ruby>彼<rt>かれ</rt></ruby>"))
        assertEquals("He speaks English.", blocks.first().examples.first().translation)
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
