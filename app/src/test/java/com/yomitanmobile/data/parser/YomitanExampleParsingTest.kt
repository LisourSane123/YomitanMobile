package com.yomitanmobile.data.parser

import com.yomitanmobile.data.local.entity.DictionaryEntry
import com.yomitanmobile.domain.model.ExamplePair
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Verifies the parser's defensive extraction of Jitendex-style example sentence
 * pairs from structured-content definitions. Each test crafts a small term_bank
 * JSON with a specific structural variation and asserts the resulting
 * DictionaryEntry has the expected examplesJson + flat definition.
 */
class YomitanExampleParsingTest {

    private val json = Json { ignoreUnknownKeys = true }
    private val examplesSerializer = ListSerializer(ExamplePair.serializer())

    @Test
    fun extractsExamplePairWhenContainerMarkedExample() = runBlocking {
        val structuredContent = """
            {
              "type": "structured-content",
              "content": [
                "to listen; to hear",
                {
                  "tag": "div",
                  "data": {"content": "example"},
                  "content": [
                    {"tag": "div", "lang": "ja", "content": "彼の意見を聞きました。"},
                    {"tag": "div", "lang": "en", "content": "I listened to his opinion."}
                  ]
                }
              ]
            }
        """.trimIndent()

        val entry = parseSingleEntry("聞く", "きく", structuredContent)
        val examples = decodeExamples(entry)

        assertEquals(1, examples.size)
        assertEquals("彼の意見を聞きました。", examples[0].jp)
        assertEquals("I listened to his opinion.", examples[0].en)
        assertEquals("彼の意見を聞きました。", entry.exampleSentence)
        assertEquals("I listened to his opinion.", entry.exampleSentenceTranslation)
    }

    @Test
    fun extractsExampleWithPluralMarkerVariant() = runBlocking {
        // Jitendex has used multiple variants of the marker: "example",
        // "example-sentence", "example-sentences". Substring match handles all.
        val structuredContent = """
            {
              "type": "structured-content",
              "content": [
                {
                  "tag": "section",
                  "data": {"content": "example-sentences"},
                  "content": [
                    {"tag": "p", "lang": "ja", "content": "本を読みます。"},
                    {"tag": "p", "lang": "en", "content": "I read books."}
                  ]
                }
              ]
            }
        """.trimIndent()

        val entry = parseSingleEntry("読む", "よむ", structuredContent)
        val examples = decodeExamples(entry)

        assertEquals(1, examples.size)
        assertEquals("本を読みます。", examples[0].jp)
        assertEquals("I read books.", examples[0].en)
    }

    @Test
    fun extractsMultipleExamplesInOrder() = runBlocking {
        val structuredContent = """
            {
              "type": "structured-content",
              "content": [
                "to eat",
                {
                  "tag": "div",
                  "data": {"content": "example"},
                  "content": [
                    {"tag": "div", "lang": "ja", "content": "ご飯を食べる。"},
                    {"tag": "div", "lang": "en", "content": "Eat a meal."}
                  ]
                },
                {
                  "tag": "div",
                  "data": {"content": "example"},
                  "content": [
                    {"tag": "div", "lang": "ja", "content": "リンゴを食べた。"},
                    {"tag": "div", "lang": "en", "content": "I ate an apple."}
                  ]
                }
              ]
            }
        """.trimIndent()

        val entry = parseSingleEntry("食べる", "たべる", structuredContent)
        val examples = decodeExamples(entry)

        assertEquals(2, examples.size)
        assertEquals("ご飯を食べる。", examples[0].jp)
        assertEquals("Eat a meal.", examples[0].en)
        assertEquals("リンゴを食べた。", examples[1].jp)
        assertEquals("I ate an apple.", examples[1].en)
        // Legacy single-pair fields mirror the FIRST example
        assertEquals("ご飯を食べる。", entry.exampleSentence)
        assertEquals("Eat a meal.", entry.exampleSentenceTranslation)
    }

    @Test
    fun fallsBackToScriptHeuristicWhenLangAttributeMissing() = runBlocking {
        // Container has no lang on its children — script detection should still
        // route the kana/kanji string to JP and the ASCII string to EN.
        val structuredContent = """
            {
              "type": "structured-content",
              "content": [
                {
                  "tag": "div",
                  "data": {"content": "example"},
                  "content": [
                    "学校に行きます。",
                    "I go to school."
                  ]
                }
              ]
            }
        """.trimIndent()

        val entry = parseSingleEntry("行く", "いく", structuredContent)
        val examples = decodeExamples(entry)

        assertEquals(1, examples.size)
        assertEquals("学校に行きます。", examples[0].jp)
        assertEquals("I go to school.", examples[0].en)
    }

    @Test
    fun multipleJpLinesJoinedSpaceSeparated() = runBlocking {
        val structuredContent = """
            {
              "type": "structured-content",
              "content": [
                {
                  "tag": "div",
                  "data": {"content": "example"},
                  "content": [
                    {"tag": "span", "lang": "ja", "content": "今日は"},
                    {"tag": "span", "lang": "ja", "content": "いい天気です。"},
                    {"tag": "div", "lang": "en", "content": "The weather is nice today."}
                  ]
                }
              ]
            }
        """.trimIndent()

        val entry = parseSingleEntry("天気", "てんき", structuredContent)
        val examples = decodeExamples(entry)

        assertEquals(1, examples.size)
        assertTrue("JP joined: ${examples[0].jp}", examples[0].jp.contains("今日は"))
        assertTrue("JP joined: ${examples[0].jp}", examples[0].jp.contains("いい天気です。"))
        assertEquals("The weather is nice today.", examples[0].en)
    }

    @Test
    fun extractsExampleNestedInUlLi() = runBlocking {
        // Some Jitendex variants wrap each example as a list item.
        val structuredContent = """
            {
              "type": "structured-content",
              "content": [
                {
                  "tag": "ul",
                  "content": [
                    {
                      "tag": "li",
                      "data": {"content": "example"},
                      "content": [
                        {"tag": "div", "lang": "ja", "content": "犬がいます。"},
                        {"tag": "div", "lang": "en", "content": "There is a dog."}
                      ]
                    }
                  ]
                }
              ]
            }
        """.trimIndent()

        val entry = parseSingleEntry("犬", "いぬ", structuredContent)
        val examples = decodeExamples(entry)

        assertEquals(1, examples.size)
        assertEquals("犬がいます。", examples[0].jp)
        assertEquals("There is a dog.", examples[0].en)
    }

    @Test
    fun definitionDoesNotIncludeExampleText() = runBlocking {
        // Critical: example sentence text must NOT leak into the flat-text
        // definition column, otherwise the meaning column shows duplicated
        // sentence content.
        val structuredContent = """
            {
              "type": "structured-content",
              "content": [
                "to swim",
                {
                  "tag": "div",
                  "data": {"content": "example"},
                  "content": [
                    {"tag": "div", "lang": "ja", "content": "プールで泳ぎます。"},
                    {"tag": "div", "lang": "en", "content": "I swim in the pool."}
                  ]
                }
              ]
            }
        """.trimIndent()

        val entry = parseSingleEntry("泳ぐ", "およぐ", structuredContent)

        assertFalse(
            "Definition should not contain the JP example: ${entry.definition}",
            entry.definition.contains("プールで泳ぎます")
        )
        assertFalse(
            "Definition should not contain the EN translation: ${entry.definition}",
            entry.definition.contains("I swim in the pool")
        )
        assertTrue(
            "Definition should still contain the actual gloss: ${entry.definition}",
            entry.definition.contains("to swim")
        )
    }

    @Test
    fun emptyExamplesJsonWhenNoExamplesPresent() = runBlocking {
        val structuredContent = "[\"a simple gloss with no examples\"]"
        val entry = parseSingleEntry("猫", "ねこ", structuredContent)

        assertEquals("", entry.examplesJson)
        assertEquals("", entry.exampleSentence)
        assertEquals("", entry.exampleSentenceTranslation)
    }

    @Test
    fun plainStringDefinitionStillSucceeds() = runBlocking {
        // Backward compat with plain JMDict (definitions are simple strings, no
        // structured-content). Should produce no examples but valid entry.
        val structuredContent = "\"to fly\""
        val entry = parseSingleEntry("飛ぶ", "とぶ", structuredContent)

        assertEquals("", entry.examplesJson)
        assertNotNull(entry)
    }

    // ---------- helpers ----------

    /**
     * Builds a one-entry term_bank with the given structured-content as the
     * definitions slot, parses it, and returns the resulting DictionaryEntry.
     */
    private suspend fun parseSingleEntry(
        expression: String,
        reading: String,
        definitionJson: String
    ): DictionaryEntry {
        val termJson = """
            [
              [
                "$expression",
                "$reading",
                "v1",
                "v1",
                0,
                [$definitionJson],
                1,
                ""
              ]
            ]
        """.trimIndent()

        val zipBytes = createZip(
            mapOf(
                "index.json" to "{\"title\":\"Test\",\"format\":\"3\",\"revision\":\"1\"}",
                "term_bank_1.json" to termJson
            )
        )

        val collected = mutableListOf<DictionaryEntry>()
        YomitanDictionaryParser().parseFromZipStreaming(
            inputStream = ByteArrayInputStream(zipBytes),
            onBatch = { batch, _ -> collected.addAll(batch) }
        )
        assertEquals("expected exactly one parsed entry", 1, collected.size)
        return collected.first()
    }

    private fun decodeExamples(entry: DictionaryEntry): List<ExamplePair> {
        if (entry.examplesJson.isBlank()) return emptyList()
        return json.decodeFromString(examplesSerializer, entry.examplesJson)
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
