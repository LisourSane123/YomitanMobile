package com.yomitanmobile.data.mapper

import com.yomitanmobile.data.local.entity.DictionaryEntry
import org.junit.Assert.assertEquals
import org.junit.Test

class DictionaryMapperTest {

    @Test
    fun toDomainExtractsUsuallyKanaTagFromJitendexPrefix() {
        // Mirror what the parser writes to the DB for 但し (JSON array of
        // definition strings, each with the "(usually kana) " prefix).
        val entry = DictionaryEntry(
            expression = "但し",
            reading = "ただし",
            definition = """["(usually kana) but, however, on the other hand","(usually kana) provided that"]""",
            partsOfSpeech = "conj"
        )

        val domain = entry.toDomain()

        assertEquals(listOf("usually kana"), domain.usageTags)
        assertEquals(
            listOf("but, however, on the other hand", "provided that"),
            domain.definitions
        )
    }

    @Test
    fun toDomainExtractsFormalTag() {
        val entry = DictionaryEntry(
            expression = "生ずる",
            reading = "しょうずる",
            definition = """["(formal) to come into existence"]""",
            partsOfSpeech = "vz"
        )

        val domain = entry.toDomain()

        assertEquals(listOf("formal"), domain.usageTags)
        assertEquals(listOf("to come into existence"), domain.definitions)
    }

    @Test
    fun toDomainLeavesPlainDefinitionsAlone() {
        val entry = DictionaryEntry(
            expression = "食べる",
            reading = "たべる",
            definition = """["to eat","to live on"]"""
        )

        val domain = entry.toDomain()

        assertEquals(emptyList<String>(), domain.usageTags)
        assertEquals(listOf("to eat", "to live on"), domain.definitions)
    }
}
