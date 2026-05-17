package com.yomitanmobile.data.parser

import com.yomitanmobile.data.local.entity.DictionaryEntry
import com.yomitanmobile.domain.model.ExamplePair
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Real-world Jitendex format coverage. The structure under test mirrors the
 * payload shipped in `jitendex-yomitan.zip` (release 2026.05.05.0):
 *
 *   structured-content
 *     └── ul[data-content=sense-groups]
 *           └── li[data-content=sense-group]
 *                 ├── span[data-content=part-of-speech-info, data.code=v1]
 *                 ├── span[data-content=part-of-speech-info, data.code=vt]
 *                 └── ol
 *                       └── li[data-content=sense]   (one per meaning)
 *                             ├── ul[data-content=glossary] → li gloss text
 *                             └── div[data-content=extra-info]
 *                                   └── div[data-content=example-sentence]
 *                                         ├── div[data-content=example-sentence-a] (JP, with ruby/rt)
 *                                         └── div[data-content=example-sentence-b] (EN, with footnote span)
 *
 * Three things must hold for the UI to be usable:
 *   1. POS codes (v1, vt) feed the partsOfSpeech column, NOT the gloss.
 *   2. Each example carries the index of the sense it illustrates.
 *   3. Furigana <rt> readings are stripped from the JP example text.
 */
class YomitanJitendexFormatTest {

    private val json = Json { ignoreUnknownKeys = true }
    private val examplesSerializer = ListSerializer(ExamplePair.serializer())

    @Test
    fun parsesTaberuLikeEntry() = runBlocking {
        val termJson = """
            [[
              "食べる",
              "たべる",
              "★",
              "v1",
              200,
              [{
                "type": "structured-content",
                "content": [{
                  "tag": "ul",
                  "lang": "ja",
                  "data": {"content": "sense-groups"},
                  "content": {
                    "tag": "li",
                    "data": {"content": "sense-group"},
                    "content": [
                      {"tag":"span","title":"Ichidan verb","data":{"class":"tag","code":"v1","content":"part-of-speech-info"},"content":"1-dan"},
                      {"tag":"span","title":"transitive verb","data":{"class":"tag","code":"vt","content":"part-of-speech-info"},"content":"transitive"},
                      {"tag":"ol","content":[
                        {"tag":"li","data":{"content":"sense"},"content":[
                          {"tag":"ul","data":{"content":"glossary"},"content":{"tag":"li","content":"to eat"}},
                          {"tag":"div","data":{"content":"extra-info"},"content":{"tag":"div","content":{"tag":"div","data":{"content":"example-sentence"},"content":[
                            {"tag":"div","data":{"content":"example-sentence-a"},"content":{"tag":"span","lang":"ja","content":[
                              "もっと",
                              {"tag":"ruby","content":["果",{"tag":"rt","content":"くだ"}]},
                              {"tag":"ruby","content":["物",{"tag":"rt","content":"もの"}]},
                              "を",
                              {"tag":"span","data":{"content":"example-keyword"},"content":[
                                {"tag":"ruby","content":["食",{"tag":"rt","content":"た"}]},
                                "べる"
                              ]},
                              "べきです。"
                            ]}},
                            {"tag":"div","data":{"content":"example-sentence-b"},"content":[
                              {"tag":"span","lang":"en","content":"You should eat more fruit."},
                              {"tag":"span","data":{"content":"attribution-footnote"},"content":"[1]"}
                            ]}
                          ]}}}
                        ]},
                        {"tag":"li","data":{"content":"sense"},"content":[
                          {"tag":"ul","data":{"content":"glossary"},"content":[
                            {"tag":"li","content":"to live on (e.g. a salary)"},
                            {"tag":"li","content":"to live off"}
                          ]}
                        ]}
                      ]}
                    ]
                  }
                }]
              }],
              1358280,
              ""
            ]]
        """.trimIndent()

        val entry = parseSingleEntry(termJson)

        // 1. POS should contain "ichidan verb" and "transitive verb" via the
        //    formatter, sourced from data.code in part-of-speech-info spans.
        val pos = com.yomitanmobile.util.PartsOfSpeechFormatter.format(entry.partsOfSpeech)
        assertTrue("POS should include ichidan verb: $pos", pos.contains("ichidan verb"))
        assertTrue("POS should include transitive verb: $pos", pos.contains("transitive verb"))

        // 2. POS labels must NOT bleed into the gloss text. The decoded gloss
        //    list should have exactly two entries, one per <li sense>.
        val defs = json.decodeFromString(
            ListSerializer(String.serializer()),
            entry.definition
        )
        assertEquals(2, defs.size)
        assertEquals("to eat", defs[0])
        assertTrue("Second gloss should mention 'live on': ${defs[1]}", defs[1].contains("live on"))
        assertFalse("Gloss must not contain '1-dan' badge: ${defs[0]}", defs[0].contains("1-dan"))
        assertFalse("Gloss must not contain 'transitive' badge: ${defs[0]}", defs[0].contains("transitive"))

        // 3. Examples are tied to their sense and stripped of furigana.
        val examples = json.decodeFromString(examplesSerializer, entry.examplesJson)
        assertEquals(1, examples.size)
        assertEquals(0, examples[0].definitionIndex)
        assertEquals(
            "もっと果物を食べるべきです。",
            examples[0].jp
        )
        assertEquals("You should eat more fruit.", examples[0].en)
        assertFalse("JP must not include furigana 'くだ': ${examples[0].jp}", examples[0].jp.contains("くだ"))
        assertFalse("JP must not include furigana 'た' inline: ${examples[0].jp}", examples[0].jp.startsWith("食た"))
        assertFalse("EN must not include footnote '[1]': ${examples[0].en}", examples[0].en.contains("[1]"))
    }

    @Test
    fun multipleSensesEachKeepTheirOwnExample() = runBlocking {
        val termJson = """
            [[
              "聞く",
              "きく",
              "★",
              "v5k vt",
              100,
              [{
                "type": "structured-content",
                "content": [{
                  "tag": "ul",
                  "data": {"content": "sense-groups"},
                  "content": {
                    "tag": "li",
                    "data": {"content": "sense-group"},
                    "content": [
                      {"tag":"span","data":{"class":"tag","code":"v5k","content":"part-of-speech-info"},"content":"5-dan"},
                      {"tag":"span","data":{"class":"tag","code":"vt","content":"part-of-speech-info"},"content":"transitive"},
                      {"tag":"ol","content":[
                        {"tag":"li","data":{"content":"sense"},"content":[
                          {"tag":"ul","data":{"content":"glossary"},"content":{"tag":"li","content":"to hear"}},
                          {"tag":"div","data":{"content":"example-sentence"},"content":[
                            {"tag":"div","data":{"content":"example-sentence-a"},"content":{"tag":"span","lang":"ja","content":[
                              {"tag":"ruby","content":["音",{"tag":"rt","content":"おと"}]},"を",
                              {"tag":"ruby","content":["聞",{"tag":"rt","content":"き"}]},"く。"
                            ]}},
                            {"tag":"div","data":{"content":"example-sentence-b"},"content":{"tag":"span","lang":"en","content":"I hear a sound."}}
                          ]}
                        ]},
                        {"tag":"li","data":{"content":"sense"},"content":[
                          {"tag":"ul","data":{"content":"glossary"},"content":{"tag":"li","content":"to ask"}},
                          {"tag":"div","data":{"content":"example-sentence"},"content":[
                            {"tag":"div","data":{"content":"example-sentence-a"},"content":{"tag":"span","lang":"ja","content":[
                              {"tag":"ruby","content":["道",{"tag":"rt","content":"みち"}]},"を",
                              {"tag":"ruby","content":["聞",{"tag":"rt","content":"き"}]},"く。"
                            ]}},
                            {"tag":"div","data":{"content":"example-sentence-b"},"content":{"tag":"span","lang":"en","content":"I ask for directions."}}
                          ]}
                        ]}
                      ]}
                    ]
                  }
                }]
              }],
              1591110,
              ""
            ]]
        """.trimIndent()

        val entry = parseSingleEntry(termJson)
        val examples = json.decodeFromString(examplesSerializer, entry.examplesJson)

        assertEquals(2, examples.size)
        assertEquals(0, examples[0].definitionIndex)
        assertEquals("音を聞く。", examples[0].jp)
        assertEquals("I hear a sound.", examples[0].en)
        assertEquals(1, examples[1].definitionIndex)
        assertEquals("道を聞く。", examples[1].jp)
        assertEquals("I ask for directions.", examples[1].en)
    }

    @Test
    fun usageTagsArePrependedInParentheses() = runBlocking {
        // 但し with the "uk" (usually written in kana) tag inlined in the
        // glossary <li>, matching how Jitendex emits it.
        val termJson = """
            [[
              "但し",
              "ただし",
              "★",
              "conj",
              50,
              [{
                "type": "structured-content",
                "content": [{
                  "tag": "ul",
                  "data": {"content": "sense-groups"},
                  "content": {
                    "tag": "li",
                    "data": {"content": "sense-group"},
                    "content": [
                      {"tag":"span","data":{"class":"tag","code":"conj","content":"part-of-speech-info"},"content":"conjunction"},
                      {"tag":"ol","content":[
                        {"tag":"li","data":{"content":"sense"},"content":[
                          {"tag":"ul","data":{"content":"glossary"},"content":{"tag":"li","content":[
                            {"tag":"span","title":"word usually written using kana alone","data":{"content":"tag","code":"uk"},"content":"uk"},
                            "but, however, on the other hand"
                          ]}}
                        ]}
                      ]}
                    ]
                  }
                }]
              }],
              2086960,
              ""
            ]]
        """.trimIndent()

        val entry = parseSingleEntry(termJson)
        val defs = json.decodeFromString(
            ListSerializer(String.serializer()),
            entry.definition
        )

        assertEquals(1, defs.size)
        assertEquals(
            "(usually kana) but, however, on the other hand",
            defs[0]
        )
    }

    @Test
    fun usageTagsFromMiscellanyWrapperAreCollected() = runBlocking {
        // Variant layout where the tag lives in a sibling <ul data-content="miscellany">
        // rather than inline in the gloss li. The same tag must still surface.
        val termJson = """
            [[
              "生ずる",
              "しょうずる",
              "★",
              "vz vi",
              80,
              [{
                "type": "structured-content",
                "content": [{
                  "tag": "ul",
                  "data": {"content": "sense-groups"},
                  "content": {
                    "tag": "li",
                    "data": {"content": "sense-group"},
                    "content": [
                      {"tag":"span","data":{"class":"tag","code":"vz","content":"part-of-speech-info"},"content":"zuru"},
                      {"tag":"ol","content":[
                        {"tag":"li","data":{"content":"sense"},"content":[
                          {"tag":"ul","data":{"content":"glossary"},"content":{"tag":"li","content":"to come into existence"}},
                          {"tag":"div","data":{"content":"extra-info"},"content":{
                            "tag":"ul","data":{"content":"miscellany"},"content":[
                              {"tag":"li","content":[{"tag":"span","title":"formal","data":{"content":"tag","code":"form"},"content":"form"}]}
                            ]
                          }}
                        ]}
                      ]}
                    ]
                  }
                }]
              }],
              1378200,
              ""
            ]]
        """.trimIndent()

        val entry = parseSingleEntry(termJson)
        val defs = json.decodeFromString(
            ListSerializer(String.serializer()),
            entry.definition
        )
        assertEquals(1, defs.size)
        assertEquals("(formal) to come into existence", defs[0])
    }

    private suspend fun parseSingleEntry(termJson: String): DictionaryEntry {
        val zipBytes = createZip(
            mapOf(
                "index.json" to "{\"title\":\"Jitendex.test\",\"format\":\"3\",\"revision\":\"1\"}",
                "term_bank_1.json" to termJson
            )
        )
        val collected = mutableListOf<DictionaryEntry>()
        YomitanDictionaryParser().parseFromZipStreaming(
            inputStream = ByteArrayInputStream(zipBytes),
            onBatch = { batch, _ -> collected.addAll(batch) }
        )
        return collected.single()
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
