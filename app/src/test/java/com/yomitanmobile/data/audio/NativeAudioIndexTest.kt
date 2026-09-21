package com.yomitanmobile.data.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeAudioIndexTest {

    /** Two rows shaped like ka_data.csv: quoted JSON in a quoted CSV field. */
    private val csv = "kanji,kname,kstroke,examples,radical\n" +
        "述,jutsu-no(beru),8,\"[ [ \"\"述語（じゅつご）\"\", \"\"predicate\"\" ], " +
        "[ \"\"述懐する（じゅっかいする）\"\", \"\"to reminisce\"\" ], " +
        "[ \"\"述べる（のべる）\"\", \"\"to state, to mention\"\" ] ]\",辶\n" +
        "足,ashi,7,\"[ [ \"\"足跡（そくせき/あしあと）\"\", \"\"footprint\"\" ], " +
        "[ \"\"述懐（じゅっかい）\"\", \"\"reminiscence\"\" ] ]\",足\n"

    @Test
    fun `examples are lettered in order onto the kanji's file prefix`() {
        val recordings = KanjiAlive.recordings(csv)
        assertEquals(
            listOf(
                KanjiAlive.Recording("述語", "じゅつご", "jutsu-no(beru)_06_a.aac"),
                KanjiAlive.Recording("述懐する", "じゅっかいする", "jutsu-no(beru)_06_b.aac"),
                KanjiAlive.Recording("述べる", "のべる", "jutsu-no(beru)_06_c.aac"),
                // 足跡 carries two readings for one recording, so it is left out —
                // and the letter still counts it: 述懐 is ashi's SECOND file.
                KanjiAlive.Recording("述懐", "じゅっかい", "ashi_06_b.aac")
            ),
            recordings
        )
    }

    @Test
    fun `a word is found by its spelling and reading`() {
        val index = NativeAudioIndex.build(KanjiAlive.recordings(csv))
        assertEquals("jutsu-no(beru)_06_a.aac", index.find("述語", "じゅつご"))
        assertEquals("jutsu-no(beru)_06_c.aac", index.find("述べる", "のべる"))
    }

    @Test
    fun `the exact word always beats a suru verb's recording of it`() {
        val index = NativeAudioIndex.build(KanjiAlive.recordings(csv))
        // 述懐 exists twice: said alone (ashi_06_b) and inside 述懐する.
        assertEquals("ashi_06_b.aac", index.find("述懐", "じゅっかい"))
    }

    @Test
    fun `a suru verb's recording answers for its noun when nothing else does`() {
        val onlyVerb = listOf(KanjiAlive.Recording("受験する", "じゅけんする", "juken.aac"))
        val index = NativeAudioIndex.build(onlyVerb)
        assertEquals("juken.aac", index.find("受験", "じゅけん"))
        assertEquals("juken.aac", index.find("受験する", "じゅけんする"))
    }

    @Test
    fun `a kanji word is never answered by its spelling alone`() {
        // Kanji alive recorded 足跡 as そくせき; あしあと is the same spelling.
        val index = NativeAudioIndex.build(listOf(KanjiAlive.Recording("足跡", "そくせき", "sokuseki.aac")))
        assertEquals("sokuseki.aac", index.find("足跡", "そくせき"))
        assertNull(index.find("足跡", "あしあと"))
    }

    @Test
    fun `a kanji word is never answered by a homophone`() {
        // 橋, 箸 and 端 are all はし, with three accents.
        val index = NativeAudioIndex.build(listOf(KanjiAlive.Recording("橋", "はし", "hashi.aac")))
        assertNull(index.find("箸", "はし"))
        assertNull(index.find("端", "はし"))
    }

    @Test
    fun `a kana word is found by its kana, in either script`() {
        val index = NativeAudioIndex.build(listOf(KanjiAlive.Recording("たばこ", "たばこ", "tabako.aac")))
        assertEquals("tabako.aac", index.find("たばこ", "たばこ"))
        assertEquals("tabako.aac", index.find("タバコ", "タバコ"))
    }

    @Test
    fun `an irregular reading marked with a star is one recording`() {
        val starred = "kanji,kname,examples\n紅,kou-beni,\"[ [ \"\"*紅葉（もみじ）\"\", \"\"autumn leaves\"\" ] ]\"\n"
        assertEquals(listOf(KanjiAlive.Recording("紅葉", "もみじ", "kou-beni_06_a.aac")), KanjiAlive.recordings(starred))
    }

    @Test
    fun `the index survives being written and read back, pair keys included`() {
        val index = NativeAudioIndex.build(KanjiAlive.recordings(csv))
        val again = NativeAudioIndex.parse(index.serialize())
        assertEquals(index.size, again.size)
        assertTrue(index.serialize().contains('\t')) // the pair keys carry a tab
        for ((word, reading) in listOf("述語" to "じゅつご", "述懐" to "じゅっかい", "述懐する" to "じゅっかいする")) {
            assertEquals(index.find(word, reading), again.find(word, reading))
        }
    }

    @Test
    fun `csv fields keep quotes, commas and line breaks`() {
        val rows = KanjiAlive.parseCsv("a,\"b, \"\"c\"\"\nd\",e\r\nf,g,h\n")
        assertEquals(listOf(listOf("a", "b, \"c\"\nd", "e"), listOf("f", "g", "h")), rows)
    }
}
