package com.yomitanmobile.domain.usecase

import com.yomitanmobile.domain.model.MergedWordEntry
import com.yomitanmobile.domain.model.ScanToken
import com.yomitanmobile.domain.model.TextScanFilters
import com.yomitanmobile.domain.model.TextScanSkipReason
import com.yomitanmobile.domain.model.TextScanSource
import com.yomitanmobile.domain.model.FrequencyTier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 近い and 食べる with the inflections a novel actually writes — JMdict files
 * a good half of them as headwords of their own, and every one of them used to
 * be a card.
 */
class ParadigmMergeTest {

    private val taberuForms = listOf(
        "食べた", "食べて", "食べない", "食べなかった", "食べます", "食べました",
        "食べている", "食べてる", "食べられる", "食べさせる", "食べよう", "食べろ",
        "食べれば", "食べたい", "食べたくない", "食べたかった", "食べたがる",
        "食べすぎる", "食べやすい", "食べにくい", "食べながら", "食べなさい", "食べそう"
    )

    private val chikaiForms = listOf(
        "近く", "近くて", "近かった", "近くない", "近くなかった", "近ければ",
        "近さ", "近み", "近げ", "近そう", "近すぎる", "近くなる"
    )

    private fun entry(expression: String, pos: List<String>, frequency: Int = 1000) = MergedWordEntry(
        primaryId = expression.hashCode().toLong(),
        primaryExpression = expression,
        reading = expression,
        definitions = listOf("meaning"),
        alternativeExpressions = emptyList(),
        frequency = frequency,
        partsOfSpeech = pos
    )

    @Test
    fun `every inflection maps back to its dictionary form`() {
        val index = ParadigmMerge.index(listOf("食べる", "近い"))

        for (form in taberuForms) assertEquals(form, "食べる", index[form])
        for (form in chikaiForms) assertEquals(form, "近い", index[form])
    }

    @Test
    fun `a chain of forms ends on the dictionary form`() {
        val index = ParadigmMerge.index(listOf("食べる", "食べたい"))
        assertEquals("食べる", index["食べたい"])
        assertEquals("食べる", index["食べたかった"])
        assertEquals("食べる", index["食べたくない"])
    }

    @Test
    fun `a suru expression inflects on its し, not on its す`() {
        val index = ParadigmMerge.index(listOf("気にする", "お願いする"))
        assertEquals("気にする", index["気にしない"])
        assertEquals("気にする", index["気にして"])
        assertEquals("お願いする", index["お願いします"])
    }

    @Test
    fun `a paradigm does not swallow a word of its own`() {
        // する generates できる; 食べる generates nothing 見る could claim.
        val index = ParadigmMerge.index(listOf("する", "食べる", "見る"))
        assertEquals(null, index["できる"])
        assertEquals(null, index["見る"])
        assertEquals("する", index["した"])
    }

    @Test
    fun `the deck collapses onto one card per word`() {
        val words = listOf("食べる") + taberuForms + listOf("近い") + chikaiForms
        val entries = words.associateWith { word ->
            when (word) {
                "食べる" -> entry("食べる", listOf("v1, vt"))
                "近い" -> entry("近い", listOf("adj-i"))
                // JMdict tags these as words of their own — 食べたい and
                // 近くない are adj-i, 食べすぎる is v1 — which is exactly the
                // case that used to keep them out of the merge.
                "食べたい", "食べたくない", "食べたかった", "近くない", "近くなかった",
                "食べやすい", "食べにくい" -> entry(word, listOf("adj-i"))
                "食べすぎる", "食べたがる", "食べられる", "食べさせる", "近すぎる", "近くなる",
                "近がる" -> entry(word, listOf("v1"))
                else -> entry(word, listOf("n"))
            }
        }
        val plan = TextScanPlanner.plan(
            sources = listOf(TextScanSource("book.epub", "EPUB", "UTF-8", 1000, 1)),
            words = words.map { ScanToken(it, occurrences = 2) },
            entries = entries,
            filters = TextScanFilters(tier = FrequencyTier.ALL, minOccurrences = 1),
            totalTokenCount = words.size * 2
        )

        assertEquals(
            setOf("食べる", "近い"),
            plan.selected.map { it.entry.primaryExpression }.toSet()
        )
        // Every occurrence of every form is counted on the word itself.
        assertEquals(
            (taberuForms.size + 1) * 2,
            plan.selected.first { it.entry.primaryExpression == "食べる" }.occurrences
        )
    }

    @Test
    fun `an inflection of a word already in Anki is not a card`() {
        // The deck never sees 近い; the collection has it.
        val plan = TextScanPlanner.plan(
            sources = listOf(TextScanSource("book.epub", "EPUB", "UTF-8", 1000, 1)),
            words = listOf(ScanToken("近く", occurrences = 20), ScanToken("食べたい", occurrences = 5)),
            entries = mapOf(
                "近く" to entry("近く", listOf("n, adv"), frequency = 348),
                "食べたい" to entry("食べたい", listOf("adj-i"), frequency = 5000)
            ),
            filters = TextScanFilters(tier = FrequencyTier.ALL, minOccurrences = 1),
            totalTokenCount = 25,
            isInAnki = { it.primaryExpression in setOf("近い", "食べる") }
        )

        assertTrue(plan.selected.isEmpty())
        assertEquals(2, plan.skipped[TextScanSkipReason.ALREADY_IN_ANKI])
    }
}
