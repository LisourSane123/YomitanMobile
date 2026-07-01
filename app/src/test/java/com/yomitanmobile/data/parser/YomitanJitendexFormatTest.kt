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

        // 4. Furigana segments preserve the ruby readings that the flat jp
        //    string drops, so the detail screen can reveal them on tap.
        val segs = examples[0].segments
        assertTrue("segments should be populated: $segs", segs.isNotEmpty())
        assertTrue(
            "segments should include (食 → た): $segs",
            segs.any { it.text == "食" && it.reading == "た" }
        )
        assertTrue(
            "segments should include (果 → くだ): $segs",
            segs.any { it.text == "果" && it.reading == "くだ" }
        )
        assertEquals(
            "segments concatenated must reproduce jp",
            examples[0].jp,
            segs.joinToString("") { it.text }
        )
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
    fun furiganaSegmentsSurviveNestedExampleWrapper() = runBlocking {
        // Real Jitendex sometimes wraps example-sentence-a/-b under an extra
        // <div> instead of making them direct children of the example-sentence
        // box. The side lookup must recurse so furigana is still captured.
        val termJson = """
            [[
              "飲む",
              "のむ",
              "★",
              "v5m",
              100,
              [{
                "type": "structured-content",
                "content": {
                  "tag": "div",
                  "data": {"content": "sense-group"},
                  "content": [
                    {"tag":"span","data":{"code":"v5m","content":"part-of-speech-info"},"content":"5-dan"},
                    {"tag":"div","data":{"content":"sense"},"content":[
                      {"tag":"ul","data":{"content":"glossary"},"content":{"tag":"li","content":"to drink"}},
                      {"tag":"div","data":{"content":"extra-info"},"content":{"tag":"div","content":{"tag":"div","data":{"content":"example-sentence"},"content":{"tag":"div","content":[
                        {"tag":"div","data":{"content":"example-sentence-a"},"content":{"tag":"span","lang":"ja","content":[
                          {"tag":"ruby","content":["水",{"tag":"rt","content":"みず"}]},"を",
                          {"tag":"ruby","content":["飲",{"tag":"rt","content":"の"}]},"む。"
                        ]}},
                        {"tag":"div","data":{"content":"example-sentence-b"},"content":{"tag":"span","lang":"en","content":"I drink water."}}
                      ]}}}}
                    ]}
                  ]
                }
              }],
              1234567,
              ""
            ]]
        """.trimIndent()

        val entry = parseSingleEntry(termJson)
        val examples = json.decodeFromString(examplesSerializer, entry.examplesJson)

        assertEquals(1, examples.size)
        assertEquals("水を飲む。", examples[0].jp)
        assertEquals("I drink water.", examples[0].en)
        val segs = examples[0].segments
        assertTrue("segments populated for nested example: $segs", segs.isNotEmpty())
        assertTrue("includes (水→みず): $segs", segs.any { it.text == "水" && it.reading == "みず" })
        assertTrue("includes (飲→の): $segs", segs.any { it.text == "飲" && it.reading == "の" })
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

    /**
     * Regression for the user-reported bug: a sense whose extra-info block
     * carries human-prose note text alongside the example sentence was
     * silently dropped, because the sense walker only picked up the
     * glossary <ul> and ignored everything else. After the fix, the note
     * text is emitted as a marker-prefixed entry in the definitions JSON,
     * so the mapper-side NotesExtractor can route it to the Notes card.
     */
    @Test
    fun captureNotesFromExtraInfoBlock() = runBlocking {
        val termJson = """
            [[
              "但し",
              "ただし",
              "",
              "conj",
              0,
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
                          {"tag":"ul","data":{"content":"glossary"},"content":{"tag":"li","content":"but, however"}},
                          {"tag":"div","data":{"content":"extra-info"},"content":[
                            {"tag":"div","content":"Usually written using kana alone."},
                            {"tag":"div","data":{"content":"example-sentence"},"content":[
                              {"tag":"div","data":{"content":"example-sentence-a"},"content":{"tag":"span","lang":"ja","content":"暑かった。但し、湿気はなかった。"}},
                              {"tag":"div","data":{"content":"example-sentence-b"},"content":{"tag":"span","lang":"en","content":"It was hot. However, there was no humidity."}}
                            ]}
                          ]}
                        ]}
                      ]}
                    ]
                  }
                }]
              }],
              0,
              ""
            ]]
        """.trimIndent()

        val entry = parseSingleEntry(termJson)
        val defs = json.decodeFromString(
            ListSerializer(String.serializer()),
            entry.definition
        )

        // The gloss is preserved. The note text from extra-info is appended
        // as a separate entry with the NOTE_MARKER prefix.
        val marker = com.yomitanmobile.util.NotesExtractor.NOTE_MARKER
        assertTrue("expected a gloss entry, got $defs", defs.any { it == "but, however" })
        assertTrue(
            "expected a marker-tagged note for the extra-info text, got $defs",
            defs.any { it == "${marker}Usually written using kana alone." }
        )

        // The example sentence is still extracted via the separate examples
        // path — extra-info filtering must not eat the example.
        val examples = json.decodeFromString(examplesSerializer, entry.examplesJson)
        assertEquals(1, examples.size)
        assertEquals("暑かった。但し、湿気はなかった。", examples[0].jp)
    }

    /**
     * Shape A coverage. Real Jitendex usually puts a single
     * `div[data-content=sense-group]` directly at the top of the
     * structured-content tree — no `sense-groups` ul wrapper, no `<ol>`
     * around senses. Before the 2026-05-23 rewrite the walker only matched
     * Shape B + <ol>, so most real entries fell through to legacy flat-text
     * extraction. This test pins the canonical Shape A.
     */
    @Test
    fun parsesShapeASingleSenseGroup() = runBlocking {
        val termJson = """
            [[
              "紫丁香花",
              "むらさきはしどい",
              "",
              "n",
              0,
              [{
                "type": "structured-content",
                "content": [
                  {"tag":"div","data":{"content":"sense-group"},"content":[
                    {"tag":"span","title":"noun","data":{"class":"tag","code":"n","content":"part-of-speech-info"},"content":"noun"},
                    {"tag":"div","data":{"content":"sense"},"content":[
                      {"tag":"ul","data":{"content":"glossary"},"content":{"tag":"li","content":"lilac (Syringa vulgaris)"}}
                    ]}
                  ]}
                ]
              }],
              0,
              ""
            ]]
        """.trimIndent()

        val entry = parseSingleEntry(termJson)
        val defs = json.decodeFromString(
            ListSerializer(String.serializer()),
            entry.definition
        )
        assertEquals(listOf("lilac (Syringa vulgaris)"), defs)
        assertTrue("POS should include noun: ${entry.partsOfSpeech}", entry.partsOfSpeech.contains("n"))
    }

    /**
     * `sense-note` is Jitendex's dedicated container for usage notes ("from
     * 毯子", "literally X", etc.). Its `*-label` div carries the chip prefix
     * and `*-content` carries the body. Both must end up in the notes list,
     * never in the meaning column.
     */
    @Test
    fun extractsSenseNoteBox() = runBlocking {
        val termJson = """
            [[
              "緞通",
              "だんつう",
              "",
              "n",
              0,
              [{
                "type": "structured-content",
                "content": [
                  {"tag":"div","data":{"content":"sense-group"},"content":[
                    {"tag":"span","data":{"class":"tag","code":"n","content":"part-of-speech-info"},"content":"noun"},
                    {"tag":"div","data":{"content":"sense"},"content":[
                      {"tag":"ul","data":{"content":"glossary"},"content":[
                        {"tag":"li","content":"cotton carpet"},
                        {"tag":"li","content":"jute rug"}
                      ]},
                      {"tag":"div","data":{"content":"extra-info"},"content":[
                        {"tag":"div","data":{"class":"extra-box","content":"sense-note"},"content":[
                          {"tag":"div","data":{"class":"extra-label","content":"sense-note-label"},"content":"Note"},
                          {"tag":"div","data":{"class":"extra-content","content":"sense-note-content"},"content":"from 毯子"}
                        ]}
                      ]}
                    ]}
                  ]}
                ]
              }],
              0,
              ""
            ]]
        """.trimIndent()

        val entry = parseSingleEntry(termJson)
        val defs = json.decodeFromString(
            ListSerializer(String.serializer()),
            entry.definition
        )
        val marker = com.yomitanmobile.util.NotesExtractor.NOTE_MARKER

        // Multiple <li> items inside a single sense's glossary collapse into
        // one joined definition string (one sense → one numbered meaning).
        assertTrue(
            "expected joined glosses to survive, got $defs",
            defs.contains("cotton carpet; jute rug")
        )
        assertTrue(
            "expected sense-note routed via NOTE_MARKER, got $defs",
            defs.any { it == "${marker}Note: from 毯子" }
        )
        // The note text must not appear inside any gloss entry.
        for (d in defs) {
            if (!d.startsWith(marker)) {
                assertFalse("gloss '$d' leaked the note body", d.contains("毯子"))
            }
        }
    }

    /**
     * `xref` boxes hold cross-references: a `reference-label` (e.g. "See
     * also") and one or more `<a>` link spans pointing to other entries.
     * The format helper prefers the link text(s); the sibling `xref-glossary`
     * is the linked entry's own gloss and is used as a fallback only.
     */
    @Test
    fun extractsXrefBox() = runBlocking {
        val termJson = """
            [[
              "紫丁香花",
              "むらさきはしどい",
              "",
              "n",
              0,
              [{
                "type": "structured-content",
                "content": [
                  {"tag":"div","data":{"content":"sense-group"},"content":[
                    {"tag":"span","data":{"class":"tag","code":"n","content":"part-of-speech-info"},"content":"noun"},
                    {"tag":"div","data":{"content":"sense"},"content":[
                      {"tag":"ul","data":{"content":"glossary"},"content":{"tag":"li","content":"lilac (Syringa vulgaris)"}},
                      {"tag":"div","data":{"content":"extra-info"},"content":{"tag":"div","content":{"tag":"div","data":{"class":"extra-box","content":"xref"},"content":[
                        {"tag":"div","data":{"content":"xref-content"},"content":[
                          {"tag":"span","lang":"en","data":{"content":"reference-label"},"content":"See also"},
                          {"tag":"a","lang":"ja","href":"?query=%E3%83%A9%E3%82%A4%E3%83%A9%E3%83%83%E3%82%AF&wildcards=off","content":"ライラック"}
                        ]},
                        {"tag":"div","data":{"content":"xref-glossary"},"content":"lilac (Syringa vulgaris)"}
                      ]}}}
                    ]}
                  ]}
                ]
              }],
              0,
              ""
            ]]
        """.trimIndent()

        val entry = parseSingleEntry(termJson)
        val defs = json.decodeFromString(
            ListSerializer(String.serializer()),
            entry.definition
        )
        val marker = com.yomitanmobile.util.NotesExtractor.NOTE_MARKER

        assertTrue(defs.contains("lilac (Syringa vulgaris)"))
        assertTrue(
            "expected xref routed via marker with link text, got $defs",
            defs.any { it == "${marker}See also: ライラック" }
        )
    }

    /**
     * `lang-source` carries etymology ("English: \"line robbing\"", "wasei").
     * It uses the same labelled-box layout as `sense-note`, so the same
     * helper should format it.
     */
    @Test
    fun extractsLangSourceBox() = runBlocking {
        val termJson = """
            [[
              "ラインロビング",
              "ラインロビング",
              "",
              "n",
              0,
              [{
                "type": "structured-content",
                "content": [
                  {"tag":"div","data":{"content":"sense-group"},"content":[
                    {"tag":"span","data":{"class":"tag","code":"n","content":"part-of-speech-info"},"content":"noun"},
                    {"tag":"div","data":{"content":"sense"},"content":[
                      {"tag":"ul","data":{"content":"glossary"},"content":{"tag":"li","content":"adding a product line"}},
                      {"tag":"div","data":{"content":"extra-info"},"content":{"tag":"div","data":{"class":"extra-box","content":"lang-source"},"content":[
                        {"tag":"div","data":{"class":"extra-label","content":"lang-source-label"},"content":"Language of Origin"},
                        {"tag":"div","data":{"class":"extra-content","content":"lang-source-content"},"content":[
                          "English: \"line robbing\"",
                          {"tag":"span","data":{"class":"tag","content":"lang-source-wasei"},"content":"wasei"}
                        ]}
                      ]}}
                    ]}
                  ]}
                ]
              }],
              0,
              ""
            ]]
        """.trimIndent()

        val entry = parseSingleEntry(termJson)
        val defs = json.decodeFromString(
            ListSerializer(String.serializer()),
            entry.definition
        )
        val marker = com.yomitanmobile.util.NotesExtractor.NOTE_MARKER

        assertTrue(defs.contains("adding a product line"))
        // The label is "Language of Origin" (taken from lang-source-label).
        // The content starts with `English: "line robbing"`. The trailing
        // "wasei" span is a tag chip — collectUsageTags filters it out so
        // the body is just the etymology text.
        val noteEntry = defs.firstOrNull { it.startsWith(marker) && it.contains("Language of Origin") }
        assertTrue("expected lang-source note, got $defs", noteEntry != null)
        assertTrue(
            "expected etymology body, got '$noteEntry'",
            noteEntry!!.contains("English") && noteEntry.contains("line robbing")
        )
    }

    /**
     * `info-gloss` is an encyclopedic explanation ("park with miniature
     * buildings, models, etc."). Same labelled-box shape as `sense-note`.
     */
    @Test
    fun extractsInfoGlossBox() = runBlocking {
        val termJson = """
            [[
              "ミニチュアパーク",
              "ミニチュアパーク",
              "",
              "n",
              0,
              [{
                "type": "structured-content",
                "content": [
                  {"tag":"div","data":{"content":"sense-group"},"content":[
                    {"tag":"span","data":{"class":"tag","code":"n","content":"part-of-speech-info"},"content":"noun"},
                    {"tag":"div","data":{"content":"sense"},"content":[
                      {"tag":"ul","data":{"content":"glossary"},"content":{"tag":"li","content":"miniature park"}},
                      {"tag":"div","data":{"content":"extra-info"},"content":{"tag":"div","content":{"tag":"div","data":{"class":"extra-box","content":"info-gloss"},"content":[
                        {"tag":"div","data":{"class":"extra-label","content":"info-gloss-label"},"content":"Explanation"},
                        {"tag":"div","data":{"class":"extra-content","content":"info-gloss-content"},"content":"park with miniature buildings, models, etc."}
                      ]}}}
                    ]}
                  ]}
                ]
              }],
              0,
              ""
            ]]
        """.trimIndent()

        val entry = parseSingleEntry(termJson)
        val defs = json.decodeFromString(
            ListSerializer(String.serializer()),
            entry.definition
        )
        val marker = com.yomitanmobile.util.NotesExtractor.NOTE_MARKER

        assertEquals(
            listOf(
                "miniature park",
                "${marker}Explanation: park with miniature buildings, models, etc."
            ),
            defs
        )
    }

    /**
     * Top-level `forms` and `attribution` siblings of `sense-group` must
     * not pollute the meaning column. Before the rewrite the parser fell
     * through to legacy flat-text extraction and concatenated form variants
     * onto the gloss; now sense-aware extraction is selective.
     */
    @Test
    fun ignoresTopLevelFormsAndAttribution() = runBlocking {
        val termJson = """
            [[
              "ローリスク",
              "ローリスク",
              "",
              "n",
              0,
              [{
                "type": "structured-content",
                "content": [
                  {"tag":"div","data":{"content":"sense-group"},"content":[
                    {"tag":"span","data":{"class":"tag","code":"n","content":"part-of-speech-info"},"content":"noun"},
                    {"tag":"div","data":{"content":"sense"},"content":[
                      {"tag":"ul","data":{"content":"glossary"},"content":{"tag":"li","content":"low-risk"}}
                    ]}
                  ]},
                  {"tag":"div","data":{"content":"forms"},"content":[
                    {"tag":"span","data":{"class":"tag","content":"forms-label"},"content":"forms"},
                    {"tag":"ul","content":[
                      {"tag":"li","content":"ローリスク"},
                      {"tag":"li","content":"ロー・リスク"}
                    ]}
                  ]},
                  {"tag":"div","data":{"content":"attribution"},"content":{"tag":"a","href":"https://example.org","content":"JMdict"}}
                ]
              }],
              0,
              ""
            ]]
        """.trimIndent()

        val entry = parseSingleEntry(termJson)
        val defs = json.decodeFromString(
            ListSerializer(String.serializer()),
            entry.definition
        )

        assertEquals(listOf("low-risk"), defs)
        // Nothing about forms / attribution should leak.
        assertFalse(defs.any { it.contains("ローリスク") && it != "low-risk" })
        assertFalse(defs.any { it.contains("JMdict") })
        assertFalse(defs.any { it.contains("forms") })
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
