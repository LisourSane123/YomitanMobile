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

    @Test
    fun mergeEntries_prefersJitendexWhenBothDictionariesExist() {
        val entries = listOf(
            WordEntry(
                id = 30,
                expression = "食べる",
                reading = "たべる",
                definitions = listOf("JMdict fallback definition"),
                dictionaryName = "JMdict (English)"
            ),
            WordEntry(
                id = 31,
                expression = "食べる",
                reading = "たべる",
                definitions = listOf("Jitendex primary definition"),
                dictionaryName = "Jitendex (JA→EN + JLPT)",
                partsOfSpeech = "v1 vt"
            )
        )

        val merged = MergedWordEntry.mergeEntries(entries)

        assertEquals(1, merged.size)

        val single = merged.first()
        assertEquals(listOf("Jitendex primary definition"), single.definitions)
        assertEquals("Jitendex (JA→EN + JLPT)", single.dictionaryName)
        assertEquals(listOf("v1 vt"), single.partsOfSpeech)
    }
}
