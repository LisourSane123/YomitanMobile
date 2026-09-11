package com.yomitanmobile.domain.usecase

import com.yomitanmobile.domain.model.FrequencyTier
import com.yomitanmobile.domain.model.GrammarSource
import com.yomitanmobile.domain.model.MergedWordEntry
import com.yomitanmobile.domain.model.ScanToken
import com.yomitanmobile.domain.model.TextScanFilters
import com.yomitanmobile.domain.model.TextScanSkipReason
import com.yomitanmobile.domain.model.TextScanSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    @Test
    fun `common grammar is dropped but rare grammar becomes a card`() {
        // The reason the tag rule is gated on frequency: a particle ranked 15
        // is met on every page, a construction ranked 8000 twice a book — and
        // the second is exactly the one the reader does not know yet.
        val everyday = entry("には", frequency = 22, partsOfSpeech = listOf("1 exp prt, ⭐ spec"))
        val rare = entry("ものを", frequency = 8000, partsOfSpeech = listOf("prt"))
        val result = plan(
            tokens("には" to 90, "ものを" to 2),
            mapOf("には" to everyday, "ものを" to rare)
        )

        assertEquals(1, result.skipped[TextScanSkipReason.FUNCTION_WORD])
        assertEquals(listOf("ものを"), result.selected.map { it.entry.primaryExpression })
    }

    @Test
    fun `priority badges in the tag string do not save a function word`() {
        // JMdict ships "1 prt, ⭐ spec": a sense number and a corpus badge. They
        // used to count as content tags, so the "every tag is grammar" test
        // never fired and こと was card number one of every deck.
        val entry = entry("こと", frequency = 15, partsOfSpeech = listOf("1 prt", "2 prt fem, ⭐ spec"))
        val result = plan(tokens("こと" to 198), mapOf("こと" to entry))

        assertEquals(1, result.skipped[TextScanSkipReason.FUNCTION_WORD])
        assertTrue(result.selected.isEmpty())
    }

    @Test
    fun `a tiny unranked kana word is treated as segmentation noise`() {
        // があ is a real JMdict entry ("see ガー") and longest match ate the が
        // of 必要がある with it 65 times in one novel.
        val noise = entry("があ", frequency = 0, partsOfSpeech = listOf("n"))
        val real = entry("学校", frequency = 616, partsOfSpeech = listOf("n"))
        val result = plan(
            tokens("があ" to 65, "学校" to 10),
            mapOf("があ" to noise, "学校" to real)
        )

        assertEquals(1, result.skipped[TextScanSkipReason.UNRANKED])
        assertEquals(listOf("学校"), result.selected.map { it.entry.primaryExpression })
    }

    @Test
    fun `two spellings of one word make one card, spelled the way the book spells it`() {
        // The text writes 持ってくる eight times and 持って来る twice; both
        // resolve to the same entry. One card, and the front is the spelling
        // the reader will meet again on the next page.
        val dictionary = entry("持って来る", reading = "もってくる", frequency = 900)
        val result = plan(
            tokens("持ってくる" to 8, "持って来る" to 2),
            mapOf("持ってくる" to dictionary, "持って来る" to dictionary)
        )

        assertEquals(1, result.selected.size)
        assertEquals("持ってくる", result.selected.single().entry.primaryExpression)
        assertEquals(10, result.selected.single().occurrences)
        assertEquals(1, result.distinctWordCount)
    }

    @Test
    fun `a tie between spellings goes to the one with kanji`() {
        val dictionary = entry("去る", reading = "さる", frequency = 1055)
        val result = plan(
            tokens("さる" to 3, "去る" to 3),
            mapOf("さる" to dictionary, "去る" to dictionary)
        )

        assertEquals(listOf("去る"), result.selected.map { it.entry.primaryExpression })
    }

    @Test
    fun `the grammar counter reports every structure and which rule judged it`() {
        val stoplisted = entry("こと", frequency = 15, partsOfSpeech = listOf("n"))
        val tagged = entry("には", frequency = 22, partsOfSpeech = listOf("1 exp prt, ⭐ spec"))
        val rare = entry("ものを", frequency = 8000, partsOfSpeech = listOf("prt"))
        val vocabulary = entry("学校", frequency = 616, partsOfSpeech = listOf("n"))
        val result = plan(
            tokens("こと" to 198, "には" to 93, "ものを" to 2, "学校" to 30),
            mapOf("こと" to stoplisted, "には" to tagged, "ものを" to rare, "学校" to vocabulary)
        )

        val byForm = result.grammarUses.associateBy { it.form }
        assertEquals(listOf("こと", "には", "ものを"), result.grammarUses.map { it.form })
        assertEquals(GrammarSource.STOPLIST, byForm.getValue("こと").source)
        assertEquals(GrammarSource.TAG_RULE, byForm.getValue("には").source)
        // Rare grammar became a card, and the counter says so rather than
        // pretending it was filtered.
        assertEquals(GrammarSource.KEPT, byForm.getValue("ものを").source)
        assertEquals(198, byForm.getValue("こと").occurrences)
        // Vocabulary is not grammar and stays out of the counter.
        assertTrue("学校" !in byForm)
    }

    @Test
    fun `an unranked blend is scaffolding while an unranked construction met twice is not`() {
        // これは is a JMdict entry that no frequency list ranks, because it is
        // これ plus は — and the text used it 21 times. くせに is equally
        // unranked and was met twice, which is the shape of grammar the reader
        // has not learned yet.
        val blend = entry("これは", frequency = 0, partsOfSpeech = listOf("1 exp uk", "2 int"))
        val construction = entry("くせに", frequency = 0, partsOfSpeech = listOf("conj"))
        val result = plan(
            tokens("これは" to 21, "くせに" to 2),
            mapOf("これは" to blend, "くせに" to construction)
        )

        assertEquals(1, result.skipped[TextScanSkipReason.FUNCTION_WORD])
        assertEquals(listOf("くせに"), result.selected.map { it.entry.primaryExpression })
    }

    @Test
    fun `the counter says which structures actually became cards`() {
        // KEPT means the grammar rules let it through, not that a card came
        // out: 今日は passes them and is then dropped for being ranked 296 050.
        val rare = entry("今日は", frequency = 296050, partsOfSpeech = listOf("int"))
        val kept = entry("くせに", frequency = 0, partsOfSpeech = listOf("conj"))
        val result = plan(
            tokens("今日は" to 2, "くせに" to 2),
            mapOf("今日は" to rare, "くせに" to kept),
            filters = TextScanFilters(tier = FrequencyTier.TOP_20K)
        )

        val byForm = result.grammarUses.associateBy { it.form }
        assertEquals(GrammarSource.KEPT, byForm.getValue("今日は").source)
        assertFalse(byForm.getValue("今日は").becameCard)
        assertTrue(byForm.getValue("くせに").becameCard)
    }
}
