package com.yomitanmobile.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MergedWordEntryTest {

    @Test
    fun mergeEntries_separatesHomophonesWithSameReading() {
        val entries = listOf(
            WordEntry(
                id = 1,
                expression = "取る",
                reading = "とる",
                definitions = listOf("to take"),
                frequency = 500
            ),
            WordEntry(
                id = 2,
                expression = "撮る",
                reading = "とる",
                definitions = listOf("to photograph"),
                frequency = 3000
            )
        )

        val merged = MergedWordEntry.mergeEntries(entries)

        assertEquals(2, merged.size)

        val take = merged.first { it.primaryExpression == "取る" }
        val photo = merged.first { it.primaryExpression == "撮る" }

        assertEquals(listOf(1L), take.entryIds)
        assertEquals(listOf("to take"), take.definitions)

        assertEquals(listOf(2L), photo.entryIds)
        assertEquals(listOf("to photograph"), photo.definitions)
    }

    @Test
    fun mergeEntries_mergesOnlyExactExpressionAndReadingPair() {
        val entries = listOf(
            WordEntry(
                id = 10,
                expression = "撮る",
                reading = "とる",
                definitions = listOf("to photograph"),
                frequency = 4000,
                partsOfSpeech = "verb"
            ),
            WordEntry(
                id = 11,
                expression = "撮る",
                reading = "とる",
                definitions = listOf("to shoot a photo"),
                frequency = 2500,
                pitchAccent = "1",
                dictionaryName = "TestDict"
            )
        )

        val merged = MergedWordEntry.mergeEntries(entries)

        assertEquals(1, merged.size)
        val single = merged.first()

        assertEquals("撮る", single.primaryExpression)
        assertEquals("とる", single.reading)
        assertEquals(11L, single.primaryId)
        assertTrue(single.entryIds.containsAll(listOf(10L, 11L)))
        assertTrue(single.definitions.containsAll(listOf("to photograph", "to shoot a photo")))
        assertEquals("1", single.pitchAccent)
        assertEquals("TestDict", single.dictionaryName)
    }

    @Test
    fun mergeEntries_remapsExampleDefinitionIndexAfterGlossDedup() {
        // definitions[0] and [2] are the same gloss and [1] is blank, so the
        // merged list collapses to ["to open", "to become vacant"]. An example
        // indexed at the pre-merge position 3 must be re-pointed at the merged
        // position 1 — otherwise it would render under the wrong meaning (or
        // fall off the end).
        val entry = WordEntry(
            id = 1,
            expression = "空く",
            reading = "あく",
            definitions = listOf("to open", "", "to open", "to become vacant"),
            examples = listOf(
                ExamplePair(jp = "戸が空く", en = "The door opens", definitionIndex = 0),
                ExamplePair(jp = "席が空く", en = "A seat frees up", definitionIndex = 3)
            )
        )

        val merged = MergedWordEntry.mergeEntries(listOf(entry)).single()

        assertEquals(listOf("to open", "to become vacant"), merged.definitions)

        val byJp = merged.examples.associateBy { it.jp }
        assertEquals(0, byJp.getValue("戸が空く").definitionIndex)
        assertEquals(1, byJp.getValue("席が空く").definitionIndex)
    }

    @Test
    fun mergeEntries_unattachableExampleIndexResetsToMinusOne() {
        // An example that points at a gloss which gets blank-filtered away has
        // no valid merged target; it must degrade to -1 (unattached) rather
        // than latch onto an unrelated meaning.
        val entry = WordEntry(
            id = 2,
            expression = "test",
            reading = "test",
            definitions = listOf("real gloss", ""),
            examples = listOf(
                ExamplePair(jp = "sentence", en = "translation", definitionIndex = 1)
            )
        )

        val merged = MergedWordEntry.mergeEntries(listOf(entry)).single()

        assertEquals(listOf("real gloss"), merged.definitions)
        assertEquals(-1, merged.examples.single().definitionIndex)
    }

    @Test
    fun mergeEntries_separatesDifferentReadingsOfSameExpression() {
        val entries = listOf(
            WordEntry(
                id = 20,
                expression = "生",
                reading = "せい",
                definitions = listOf("life")
            ),
            WordEntry(
                id = 21,
                expression = "生",
                reading = "なま",
                definitions = listOf("raw")
            )
        )

        val merged = MergedWordEntry.mergeEntries(entries)

        assertEquals(2, merged.size)
        assertTrue(merged.any { it.reading == "せい" && it.entryIds == listOf(20L) })
        assertTrue(merged.any { it.reading == "なま" && it.entryIds == listOf(21L) })
    }
}
