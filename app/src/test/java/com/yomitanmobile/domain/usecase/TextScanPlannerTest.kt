package com.yomitanmobile.domain.usecase

import com.yomitanmobile.domain.model.FrequencyTier
import com.yomitanmobile.domain.model.MergedWordEntry
import com.yomitanmobile.domain.model.ScanToken
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

    private fun tokens(vararg pairs: Pair<String, Int>): List<ScanToken> =
        pairs.map { (word, count) -> ScanToken(word, count) }

    private fun plan(
        words: List<ScanToken>,
        entries: Map<String, MergedWordEntry>,
        filters: TextScanFilters = TextScanFilters(),
        totalTokens: Int = words.sumOf { it.occurrences },
        isInAnki: (MergedWordEntry) -> Boolean = { false },
        isMined: (MergedWordEntry) -> Boolean = { false }
    ) = TextScanPlanner.plan(
        sources = listOf(source),
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
            words = tokens("学校" to 5, "洗濯" to 2),
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
            words = tokens("問題" to 1, "燦爛" to 1),
            entries = mapOf("問題" to common, "燦爛" to rare),
            filters = TextScanFilters(tier = FrequencyTier.TOP_10K)
        )

        assertEquals(listOf("問題"), result.selected.map { it.entry.primaryExpression })
        assertEquals(1, result.skipped[TextScanSkipReason.TOO_RARE])
    }

    @Test
    fun `the new 30k tier sits between 20k and 50k`() {
        val word = entry("辛辣", frequency = 27_000)
        val words = tokens("辛辣" to 1)
        val entries = mapOf("辛辣" to word)

        assertTrue(plan(words, entries, TextScanFilters(tier = FrequencyTier.TOP_20K)).selected.isEmpty())
        assertEquals(1, plan(words, entries, TextScanFilters(tier = FrequencyTier.TOP_30K)).selectedCount)
        assertEquals(1, plan(words, entries, TextScanFilters(tier = FrequencyTier.TOP_50K)).selectedCount)
    }

    @Test
    fun `every candidate is counted exactly once`() {
        val result = plan(
            words = tokens(
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
            words = tokens("洗濯" to 1, "問題" to 4),
            entries = mapOf("洗濯" to entry("洗濯"), "問題" to entry("問題")),
            filters = TextScanFilters(minOccurrences = 2)
        )

        assertEquals(listOf("問題"), result.selected.map { it.entry.primaryExpression })
        assertEquals(1, result.skipped[TextScanSkipReason.TOO_FEW_OCCURRENCES])
    }

    @Test
    fun `most frequent in the text comes first and survives the cap`() {
        val result = plan(
            words = tokens("稀語" to 1, "頻語" to 9, "中語" to 4),
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
            words = tokens("は" to 5, "学校" to 3, "燦爛" to 1, "洗濯" to 1),
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
    fun `a word common in the language beats one only common in this text`() {
        // 稀語 shows up more often here, but 常語 is a word the learner will
        // meet everywhere — global frequency carries the most weight.
        val result = plan(
            words = tokens("稀語" to 12, "常語" to 4),
            entries = mapOf(
                "稀語" to entry("稀語", frequency = 40_000),
                "常語" to entry("常語", frequency = 300)
            ),
            filters = TextScanFilters(tier = FrequencyTier.TOP_50K)
        )

        assertEquals(listOf("常語", "稀語"), result.selected.map { it.entry.primaryExpression })
    }

    @Test
    fun `among equals the word appearing earlier in the series wins`() {
        val early = ScanToken("巻頭語", occurrences = 3, earliness = 1f)
        val late = ScanToken("巻末語", occurrences = 3, earliness = 0.05f)
        val result = plan(
            words = listOf(late, early),
            entries = mapOf(
                "巻頭語" to entry("巻頭語", frequency = 900),
                "巻末語" to entry("巻末語", frequency = 900)
            )
        )

        assertEquals(listOf("巻頭語", "巻末語"), result.selected.map { it.entry.primaryExpression })
    }

    @Test
    fun `the source sentence travels with the kept word`() {
        val result = plan(
            words = listOf(ScanToken("洗濯", 2, sentence = "洗濯物を干した。")),
            entries = mapOf("洗濯" to entry("洗濯"))
        )

        assertEquals("洗濯物を干した。", result.selected.single().sentence)
    }

    @Test
    fun `compound grammar is dropped on its tags, not on a word list`() {
        // それでも and ということ are single dictionary entries, so no literal
        // stoplist reaches them — only their JMdict tags do. 事 is the control:
        // an ordinary noun that must survive.
        val result = plan(
            words = tokens("それでも" to 12, "ということ" to 9, "事" to 5),
            entries = mapOf(
                "それでも" to entry("それでも", partsOfSpeech = listOf("conj")),
                "ということ" to entry("ということ", partsOfSpeech = listOf("exp, prt")),
                "事" to entry("事", partsOfSpeech = listOf("n"))
            )
        )

        assertEquals(listOf("事"), result.selected.map { it.entry.primaryExpression })
        assertEquals(2, result.skipped[TextScanSkipReason.FUNCTION_WORD])
    }

    @Test
    fun `a word with one grammatical sense and one real one is kept`() {
        // 自分 is `pn` (oneself) in one sense and `n` (one's own) in another;
        // the merged entry carries both, and an "any function tag" rule would
        // eat it. Same for an ordinary i-adjective, whose only tag is an
        // inflection paradigm.
        val result = plan(
            words = tokens("自分" to 6, "高い" to 6),
            entries = mapOf(
                "自分" to entry("自分", partsOfSpeech = listOf("pn, n")),
                "高い" to entry("高い", partsOfSpeech = listOf("adj-i"))
            )
        )

        assertEquals(2, result.selectedCount)
        assertEquals(null, result.skipped[TextScanSkipReason.FUNCTION_WORD])
    }

    @Test
    fun `an auxiliary keeps its inflection tags and is still dropped`() {
        // たがる is `aux-v, v5r` — the paradigm tag must not rescue it.
        val result = plan(
            words = tokens("たがる" to 30),
            entries = mapOf("たがる" to entry("たがる", partsOfSpeech = listOf("aux-v, v5r")))
        )

        assertEquals(0, result.selectedCount)
        assertEquals(1, result.skipped[TextScanSkipReason.FUNCTION_WORD])
    }

    @Test
    fun `the assumed-known cut drops the commonest words and counts them as known`() {
        val words = tokens("学校" to 6, "洗濯" to 4)
        val entries = mapOf(
            "学校" to entry("学校", frequency = 120),
            "洗濯" to entry("洗濯", frequency = 4_500)
        )

        assertEquals(2, plan(words, entries).selectedCount)

        val result = plan(words, entries, TextScanFilters(assumeKnownTopRank = 2_000), totalTokens = 10)
        assertEquals(listOf("洗濯"), result.selected.map { it.entry.primaryExpression })
        assertEquals(1, result.skipped[TextScanSkipReason.ASSUMED_KNOWN])
        assertEquals(6, result.knownTokenCount)
    }

    @Test
    fun `the assumed-known cut leaves unranked words alone`() {
        val result = plan(
            words = tokens("新語" to 2),
            entries = mapOf("新語" to entry("新語", frequency = 0)),
            filters = TextScanFilters(assumeKnownTopRank = 5_000)
        )

        assertEquals(1, result.selectedCount)
    }

    @Test
    fun `unranked words are kept by default and cut on request`() {
        val words = tokens("新語" to 2)
        val entries = mapOf("新語" to entry("新語", frequency = 0))

        assertEquals(1, plan(words, entries).selectedCount)
        val strict = plan(words, entries, TextScanFilters(includeUnranked = false))
        assertEquals(0, strict.selectedCount)
        assertEquals(1, strict.skipped[TextScanSkipReason.UNRANKED])
    }
}
