package com.yomitanmobile.domain.usecase

import com.yomitanmobile.domain.model.AppLanguage
import com.yomitanmobile.domain.model.MergedWordEntry
import com.yomitanmobile.domain.model.ScanToken
import com.yomitanmobile.domain.model.TextScanFilters
import com.yomitanmobile.domain.model.TextScanPlan
import com.yomitanmobile.domain.model.TextScanSkipReason
import com.yomitanmobile.domain.model.TextScanSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The same planner, the same rule order, English rules: what the chain drops
 * for an English book and what it keeps.
 */
class LatinScanPlannerTest {

    private val source = TextScanSource(
        fileName = "book.epub",
        formatLabel = "EPUB",
        charsetName = "UTF-8",
        characterCount = 5000,
        partCount = 12
    )

    private fun entry(
        expression: String,
        frequency: Int = 4000,
        definitions: List<String> = listOf("gloss"),
        partsOfSpeech: List<String> = emptyList()
    ) = MergedWordEntry(
        primaryId = 0,
        primaryExpression = expression,
        reading = expression,
        definitions = definitions,
        alternativeExpressions = emptyList(),
        frequency = frequency,
        partsOfSpeech = partsOfSpeech,
        dictionaryName = "kty-en-pl",
        usageTags = emptyList()
    )

    private fun plan(
        words: List<ScanToken>,
        entries: Map<String, MergedWordEntry>,
        filters: TextScanFilters = TextScanFilters(),
        isInAnki: (MergedWordEntry) -> Boolean = { false }
    ): TextScanPlan = TextScanPlanner.plan(
        sources = listOf(source),
        words = words,
        entries = entries,
        filters = filters,
        totalTokenCount = words.sumOf { it.occurrences },
        isInAnki = isInAnki,
        rules = LatinScanRules(AppLanguage.ENGLISH)
    )

    @Test
    fun `the closed classes go and the vocabulary stays`() {
        val result = plan(
            words = listOf(
                ScanToken("the", 900), ScanToken("would", 120), ScanToken("of", 400),
                ScanToken("reluctant", 6)
            ),
            entries = mapOf(
                "the" to entry("the", frequency = 1),
                "would" to entry("would", frequency = 60),
                "of" to entry("of", frequency = 2),
                "reluctant" to entry("reluctant", frequency = 9000)
            )
        )
        assertEquals(listOf("reluctant"), result.selected.map { it.entry.primaryExpression })
        assertEquals(3, result.skipped[TextScanSkipReason.FUNCTION_WORD])
    }

    @Test
    fun `chapter numbers, figures and stray letters are noise`() {
        val result = plan(
            words = listOf(
                ScanToken("xvii", 9), ScanToken("1920s", 3), ScanToken("b", 5),
                ScanToken("shh", 4), ScanToken("rhythm", 4)
            ),
            entries = mapOf(
                "xvii" to entry("xvii", frequency = 0),
                "shh" to entry("shh", frequency = 0),
                // A real word with no vowel letter: the rule must not take it.
                "rhythm" to entry("rhythm", frequency = 8000)
            )
        )
        assertEquals(listOf("rhythm"), result.selected.map { it.entry.primaryExpression })
        assertEquals(4, result.skipped[TextScanSkipReason.NOISE])
    }

    @Test
    fun `a word the text only ever capitalises is a name unless the corpus knows it`() {
        val result = plan(
            words = listOf(
                // Elizabeth: 583 uses, 349 of them a mid-sentence capital.
                ScanToken("Darcy", 40, nameHits = 35),
                // Capitalised as often, but an everyday word (lady, 1 186).
                ScanToken("lady", 20, nameHits = 15),
                // Rare and sometimes capitalised — "Chapter" in a heading.
                ScanToken("chapter", 88, nameHits = 28),
                // Rare, and never capitalised mid-sentence.
                ScanToken("parlour", 12, nameHits = 0)
            ),
            entries = mapOf(
                "Darcy" to entry("Darcy", frequency = 0),
                "lady" to entry("lady", frequency = 1186),
                "chapter" to entry("chapter", frequency = 2237),
                "parlour" to entry("parlour", frequency = 0)
            ),
            filters = TextScanFilters(includeUnranked = true)
        )
        val kept = result.selected.map { it.entry.primaryExpression }
        assertTrue(kept.containsAll(listOf("lady", "chapter", "parlour")))
        assertFalse("Darcy" in kept)
        assertEquals(1, result.skipped[TextScanSkipReason.PROPER_NAME])
    }

    @Test
    fun `an inflection filed as its own headword collapses onto the word`() {
        val result = plan(
            words = listOf(
                ScanToken("said", 406), ScanToken("say", 210),
                // "moth" is what the -er rule offers for "mother"; the
                // frequency lists are what refuse it.
                ScanToken("mother", 134), ScanToken("moth", 2)
            ),
            entries = mapOf(
                "said" to entry("said", frequency = 100),
                "say" to entry("say", frequency = 132),
                "mother" to entry("mother", frequency = 568),
                "moth" to entry("moth", frequency = 12000)
            )
        )
        val counts = result.selected.associate { it.entry.primaryExpression to it.occurrences }
        assertEquals(616, counts["say"])
        assertFalse("said" in counts)
        assertEquals(134, counts["mother"])
        assertEquals(2, counts["moth"])
    }

    @Test
    fun `a form whose base is in the collection is not a card`() {
        val result = plan(
            words = listOf(ScanToken("children", 12)),
            entries = mapOf("children" to entry("children")),
            isInAnki = { it.primaryExpression == "child" }
        )
        assertTrue(result.selected.isEmpty())
        assertEquals(1, result.skipped[TextScanSkipReason.ALREADY_IN_ANKI])
    }

    @Test
    fun `a word already in the collection is not made again`() {
        val result = plan(
            words = listOf(ScanToken("knife", 7), ScanToken("hedgehog", 3)),
            entries = mapOf("knife" to entry("knife"), "hedgehog" to entry("hedgehog")),
            isInAnki = { it.primaryExpression == "knife" }
        )
        assertEquals(listOf("hedgehog"), result.selected.map { it.entry.primaryExpression })
        assertEquals(1, result.skipped[TextScanSkipReason.ALREADY_IN_ANKI])
    }

    @Test
    fun `every word is counted exactly once`() {
        val words = listOf(
            ScanToken("the", 900), ScanToken("xvii", 2), ScanToken("unheard", 4),
            ScanToken("unknownword", 1), ScanToken("hedgehog", 3)
        )
        val result = plan(
            words = words,
            entries = mapOf(
                "the" to entry("the", frequency = 1),
                "xvii" to entry("xvii", frequency = 0),
                "unheard" to entry("unheard", frequency = 30000),
                "hedgehog" to entry("hedgehog")
            )
        )
        assertEquals(words.size, result.selected.size + result.skipped.values.sum())
    }

    @Test
    fun `the kana filters do nothing for a language without kana`() {
        val filters = TextScanFilters(skipPlainKana = true, skipKatakana = true)
        val result = plan(
            words = listOf(ScanToken("hedgehog", 3)),
            entries = mapOf("hedgehog" to entry("hedgehog")),
            filters = filters
        )
        assertEquals(1, result.selected.size)
    }

    @Test
    fun `spanish conjugations become one card for the verb`() {
        // What the scan sees after ScanEntryResolver has followed each form to
        // its lemma: three tokens, one entry.
        val hablar = entry("hablar", frequency = 400)
        val result = TextScanPlanner.plan(
            sources = listOf(source),
            words = listOf(
                ScanToken("hablando", 12), ScanToken("hablé", 5), ScanToken("hablar", 3)
            ),
            entries = mapOf("hablando" to hablar, "hablé" to hablar, "hablar" to hablar),
            filters = TextScanFilters(),
            totalTokenCount = 20,
            rules = LatinScanRules(AppLanguage.SPANISH)
        )
        assertEquals(1, result.selected.size)
        assertEquals("hablar", result.selected.single().entry.primaryExpression)
        assertEquals(20, result.selected.single().occurrences)
    }

    @Test
    fun `spanish uses its own closed classes`() {
        val result = TextScanPlanner.plan(
            sources = listOf(source),
            words = listOf(ScanToken("estaba", 80), ScanToken("cordillera", 5)),
            entries = mapOf(
                "estaba" to entry("estaba", frequency = 90),
                "cordillera" to entry("cordillera", frequency = 15000)
            ),
            filters = TextScanFilters(),
            totalTokenCount = 85,
            rules = LatinScanRules(AppLanguage.SPANISH)
        )
        assertEquals(listOf("cordillera"), result.selected.map { it.entry.primaryExpression })
    }
}
