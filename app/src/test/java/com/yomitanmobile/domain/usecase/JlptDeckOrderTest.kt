package com.yomitanmobile.domain.usecase

import com.yomitanmobile.domain.model.JlptDeckFilters
import com.yomitanmobile.domain.model.MergedWordEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * AnkiDroid introduces new cards in the order they were written, so the order
 * the planner hands to the batch writer IS the study order of the generated
 * deck. It has to be most-frequent-first from the plan all the way to the
 * WordEntry list that goes into `exportBatchToAnki`.
 */
class JlptDeckOrderTest {

    private fun word(expression: String, frequency: Int) = MergedWordEntry(
        primaryId = frequency.toLong(),
        primaryExpression = expression,
        reading = expression,
        definitions = listOf("gloss"),
        alternativeExpressions = emptyList(),
        frequency = frequency,
        partsOfSpeech = listOf("n")
    )

    private val candidates = listOf(
        word("稀語", 0),          // unranked
        word("食欲", 8000),
        word("日本", 120),
        word("珍語", 0),          // unranked
        word("学校", 900),
        word("人", 40)
    )

    private fun plan(maxWords: Int = 0, seed: Int = 1) = JlptDeckPlanner.plan(
        level = 5,
        candidates = candidates,
        filters = JlptDeckFilters(
            maxFrequencyRank = 0,
            maxWords = maxWords,
            skipAlreadyInAnki = false,
            skipAlreadyMined = false
        ),
        random = Random(seed)
    )

    @Test
    fun `ranked words come first, commonest first`() {
        val order = plan().selected.map { it.primaryExpression }

        assertEquals(listOf("人", "日本", "学校", "食欲"), order.take(4))
    }

    @Test
    fun `unranked words go last, and not in alphabetical order`() {
        // Alphabetical would put every あ-word at the front of the tail and
        // every わ-word at its end, so a run of new cards would all start with
        // the same kana. They are shuffled instead — the tail is the same SET
        // every time, in an order that depends on the draw.
        val tail = plan().selected.drop(4).map { it.primaryExpression }
        assertEquals(setOf("珍語", "稀語"), tail.toSet())

        val manyUnranked = (1..40).map { word("語$it", 0) }
        val shuffledA = JlptDeckPlanner.plan(
            level = 5,
            candidates = manyUnranked,
            filters = JlptDeckFilters(maxFrequencyRank = 0, skipAlreadyInAnki = false, skipAlreadyMined = false),
            random = Random(1)
        ).selected.map { it.primaryExpression }
        val shuffledB = JlptDeckPlanner.plan(
            level = 5,
            candidates = manyUnranked,
            filters = JlptDeckFilters(maxFrequencyRank = 0, skipAlreadyInAnki = false, skipAlreadyMined = false),
            random = Random(2)
        ).selected.map { it.primaryExpression }

        assertEquals(shuffledA.toSet(), shuffledB.toSet())
        assertTrue("two draws should not produce the same order", shuffledA != shuffledB)
        assertTrue(
            "the shuffle must not come out sorted",
            shuffledA != manyUnranked.map { it.primaryExpression }.sorted()
        )
    }

    @Test
    fun `the order survives the conversion the generator writes from`() {
        // JlptDeckViewModel.generate() maps the plan exactly like this before
        // handing it to the monolingual resolver and the batch writer, both of
        // which preserve list order.
        val written = plan().selected.map { it.toWordEntry() }

        assertEquals(listOf("人", "日本", "学校", "食欲"), written.take(4).map { it.expression })
        assertEquals(listOf(40, 120, 900, 8000), written.take(4).map { it.frequency })
        assertEquals(listOf(0, 0), written.drop(4).map { it.frequency })
    }

    @Test
    fun `a capped deck keeps the most frequent words, in order`() {
        assertEquals(listOf("人", "日本", "学校"), plan(maxWords = 3).selected.map { it.primaryExpression })
    }
}
