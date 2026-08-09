package com.yomitanmobile.domain.usecase

import com.yomitanmobile.domain.model.FrequencyTier
import com.yomitanmobile.domain.model.MergedWordEntry
import com.yomitanmobile.domain.model.TextScanFilters
import com.yomitanmobile.domain.model.TextScanSkipReason
import com.yomitanmobile.domain.model.TextScanSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TextScanPlannerTest {

    private val source = TextScanSource(
        fileName = "episode01.srt",
        formatLabel = "SubRip (.srt)",
        charsetName = "UTF-8",
        characterCount = 1000,
        partCount = 300
    )

    private fun entry(
        expression: String,
        reading: String = expression,
        frequency: Int = 500,
        definitions: List<String> = listOf("meaning"),
        usageTags: List<String> = emptyList(),
        partsOfSpeech: List<String> = listOf("n"),
        dictionaryName: String = "Jitendex"
    ) = MergedWordEntry(
        primaryId = 0,
        primaryExpression = expression,
        reading = reading,
        definitions = definitions,
        alternativeExpressions = emptyList(),
        frequency = frequency,
        partsOfSpeech = partsOfSpeech,
        dictionaryName = dictionaryName,
        usageTags = usageTags
    )

    private fun plan(
        words: Map<String, Int>,
        entries: Map<String, MergedWordEntry>,
        filters: TextScanFilters = TextScanFilters(),
        totalTokens: Int = words.values.sum(),
        isInAnki: (MergedWordEntry) -> Boolean = { false },
        isMined: (MergedWordEntry) -> Boolean = { false }
    ) = TextScanPlanner.plan(
        source = source,
        words = words,
        entries = entries,
        filters = filters,
        totalTokenCount = totalTokens,
        isInAnki = isInAnki,
        isMined = isMined
    )

    @Test
    fun `keeps unknown words and drops the ones already in anki`() {
        val known = entry("学校", "がっこう")
        val unknown = entry("洗濯", "せんたく")
        val result = plan(
            words = mapOf("学校" to 5, "洗濯" to 2),
            entries = mapOf("学校" to known, "洗濯" to unknown),
            isInAnki = { it.primaryExpression == "学校" }
        )

        assertEquals(listOf("洗濯"), result.selected.map { it.entry.primaryExpression })
        assertEquals(1, result.skipped[TextScanSkipReason.ALREADY_IN_ANKI])
    }

    @Test
    fun `frequency tier cuts the long tail`() {
        val common = entry("問題", frequency = 800)
        val rare = entry("燦爛", frequency = 45_000)
        val result = plan(
            words = mapOf("問題" to 1, "燦爛" to 1),
            entries = mapOf("問題" to common, "燦爛" to rare),
            filters = TextScanFilters(tier = FrequencyTier.TOP_10K)
        )

        assertEquals(listOf("問題"), result.selected.map { it.entry.primaryExpression })
        assertEquals(1, result.skipped[TextScanSkipReason.TOO_RARE])
    }

    @Test
    fun `the new 30k tier sits between 20k and 50k`() {
        val word = entry("辛辣", frequency = 27_000)
        val words = mapOf("辛辣" to 1)
        val entries = mapOf("辛辣" to word)

        assertTrue(plan(words, entries, TextScanFilters(tier = FrequencyTier.TOP_20K)).selected.isEmpty())
        assertEquals(1, plan(words, entries, TextScanFilters(tier = FrequencyTier.TOP_30K)).selectedCount)
        assertEquals(1, plan(words, entries, TextScanFilters(tier = FrequencyTier.TOP_50K)).selectedCount)
    }

    @Test
    fun `every candidate is counted exactly once`() {
        val result = plan(
            words = mapOf(
                "は" to 40,          // function word
                "未知語" to 1,        // not in any dictionary
                "洗濯" to 1,          // kept
                "古語" to 3,          // archaic
                "田中" to 2           // proper name
            ),
            entries = mapOf(
                "は" to entry("は"),
                "洗濯" to entry("洗濯"),
                "古語" to entry("古語", usageTags = listOf("archaic")),
                "田中" to entry("田中", partsOfSpeech = listOf("surname"))
            )
        )

        assertEquals(5, result.selectedCount + result.skippedCount)
        assertEquals(1, result.skipped[TextScanSkipReason.NOT_IN_DICTIONARY])
        assertEquals(1, result.skipped[TextScanSkipReason.FUNCTION_WORD])
        assertEquals(1, result.skipped[TextScanSkipReason.ARCHAIC])
        assertEquals(1, result.skipped[TextScanSkipReason.PROPER_NAME])
        assertEquals(listOf("洗濯"), result.selected.map { it.entry.primaryExpression })
    }

    @Test
    fun `minimum occurrences filters one-off words`() {
        val result = plan(
            words = mapOf("洗濯" to 1, "問題" to 4),
            entries = mapOf("洗濯" to entry("洗濯"), "問題" to entry("問題")),
            filters = TextScanFilters(minOccurrences = 2)
        )

        assertEquals(listOf("問題"), result.selected.map { it.entry.primaryExpression })
        assertEquals(1, result.skipped[TextScanSkipReason.TOO_FEW_OCCURRENCES])
    }

    @Test
    fun `most frequent in the text comes first and survives the cap`() {
        val result = plan(
            words = mapOf("稀語" to 1, "頻語" to 9, "中語" to 4),
            entries = mapOf(
                "稀語" to entry("稀語"),
                "頻語" to entry("頻語"),
                "中語" to entry("中語")
            ),
            filters = TextScanFilters(maxWords = 2)
        )

        assertEquals(listOf("頻語", "中語"), result.selected.map { it.entry.primaryExpression })
        assertEquals(1, result.skipped[TextScanSkipReason.OVER_LIMIT])
    }

    @Test
    fun `known coverage counts only demonstrably known words`() {
        // 8 of 10 running words are function words or already in Anki; the
        // word dropped for being too rare is unknown and must not inflate it.
        val result = plan(
            words = mapOf("は" to 5, "学校" to 3, "燦爛" to 1, "洗濯" to 1),
            entries = mapOf(
                "は" to entry("は"),
                "学校" to entry("学校"),
                "燦爛" to entry("燦爛", frequency = 45_000),
                "洗濯" to entry("洗濯")
            ),
            filters = TextScanFilters(tier = FrequencyTier.TOP_10K),
            totalTokens = 10,
            isInAnki = { it.primaryExpression == "学校" }
        )

        assertEquals(8, result.knownTokenCount)
        assertEquals(0.8f, result.knownCoverage, 0.001f)
    }

    @Test
    fun `unranked words are kept by default and cut on request`() {
        val words = mapOf("新語" to 2)
        val entries = mapOf("新語" to entry("新語", frequency = 0))

        assertEquals(1, plan(words, entries).selectedCount)
        val strict = plan(words, entries, TextScanFilters(includeUnranked = false))
        assertEquals(0, strict.selectedCount)
        assertEquals(1, strict.skipped[TextScanSkipReason.UNRANKED])
    }
}
