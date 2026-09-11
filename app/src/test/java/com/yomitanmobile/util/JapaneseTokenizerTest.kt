package com.yomitanmobile.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class JapaneseTokenizerTest {

    private fun lexiconOf(vararg words: String): JapaneseTokenizer.Lexicon {
        val set = words.toSet()
        return JapaneseTokenizer.Lexicon { it in set }
    }

    private fun baseForms(text: String, lexicon: JapaneseTokenizer.Lexicon): List<String> =
        JapaneseTokenizer.tokenize(text, lexicon).map { it.baseForm }

    @Test
    fun `a name is not chopped into the single kanji it is made of`() {
        // 朱音 is a character's name: no dictionary has it, but both halves are
        // entries of their own. Counting them turned a novel's heroine into 700
        // cards for 朱 "unit of weight" and 700 for 音 "sound".
        val lexicon = lexiconOf("朱", "音", "は", "笑う")
        val bases = baseForms("朱音は笑った。", lexicon)

        assertFalse(bases.toString(), "朱" in bases)
        assertFalse(bases.toString(), "音" in bases)
        assertTrue(bases.toString(), "笑う" in bases)
    }

    @Test
    fun `a single kanji standing between kana is still a word`() {
        val lexicon = lexiconOf("人", "を", "見る")
        assertTrue("人" in baseForms("人を見る。", lexicon))
    }

    @Test
    fun `adverbial ku resolves instead of leaving a kana tail behind`() {
        // 優しく used to fail, fall back to the single kanji 優, and leave しく
        // to be matched as 敷く — a card for "to spread out" in every novel
        // containing a kind character.
        val lexicon = lexiconOf("優しい", "敷く", "語りかける", "が", "千代")
        val bases = baseForms("千代が優しく語りかける。", lexicon)

        assertTrue(bases.toString(), "優しい" in bases)
        assertFalse(bases.toString(), "敷く" in bases)
    }

    @Test
    fun `longest match wins over shorter words inside it`() {
        val lexicon = lexiconOf("東京", "都", "東京都", "行く")
        assertEquals(listOf("東京都"), baseForms("東京都", lexicon))
    }

    @Test
    fun `inflected verbs resolve to their dictionary form`() {
        val lexicon = lexiconOf("食べる", "食", "寿司", "を")
        val tokens = JapaneseTokenizer.tokenize("寿司を食べました。", lexicon)
        val byBase = tokens.associateBy { it.baseForm }

        assertTrue("食べる missing: ${tokens.map { it.baseForm }}", "食べる" in byBase)
        assertEquals("食べました", byBase.getValue("食べる").surface)
        assertTrue(byBase.getValue("食べる").wasInflected)
        // 食 must NOT be counted separately — it was consumed by 食べました.
        assertFalse("食" in byBase)
    }

    @Test
    fun `occurrences are counted across inflections`() {
        val lexicon = lexiconOf("走る")
        val tokens = JapaneseTokenizer.tokenize("走る、走った、走ります", lexicon)
        assertEquals(1, tokens.size)
        assertEquals(3, tokens.first().count)
    }

    @Test
    fun `non-japanese text and unknown words are skipped`() {
        val lexicon = lexiconOf("猫")
        val tokens = JapaneseTokenizer.tokenize("Hello, 世界! 猫 123", lexicon)
        assertEquals(listOf("猫"), tokens.map { it.baseForm })
    }

    @Test
    fun `single hiragana particles are not counted as words`() {
        // They are genuine dictionary entries, so the lexicon matches them —
        // the tokeniser has to drop them itself or every deck starts with は.
        val lexicon = lexiconOf("は", "が", "を", "猫")
        assertEquals(listOf("猫"), baseForms("猫は", lexicon))
    }

    @Test
    fun `a match never runs past the end of a japanese stretch`() {
        val lexicon = lexiconOf("犬", "猫", "犬猫")
        // "犬 猫" separated by a space must not merge into 犬猫.
        assertEquals(listOf("犬", "猫"), baseForms("犬 猫", lexicon))
    }

    @Test
    fun `kana spelling of a kanji word is found through the reading`() {
        // The lexicon holds readings too, so a text writing みる resolves.
        val lexicon = lexiconOf("見る", "みる")
        assertEquals(listOf("みる"), baseForms("みるだけ", lexicon))
    }

    @Test
    fun `each word carries the sentence it was met in`() {
        val lexicon = lexiconOf("洗濯", "干す", "散歩")
        val tokens = JapaneseTokenizer.tokenize(
            "朝から洗濯物を干していた。それから散歩に出かけた。",
            lexicon
        ).associateBy { it.baseForm }

        assertEquals("朝から洗濯物を干していた。", tokens.getValue("洗濯").sentence)
        assertEquals("それから散歩に出かけた。", tokens.getValue("散歩").sentence)
    }

    @Test
    fun `an unusable first sentence does not block a later one`() {
        // The first hit sits in a one-word sentence, too short for a card
        // front; the next occurrence provides a usable sentence.
        val lexicon = lexiconOf("猫")
        val token = JapaneseTokenizer.tokenize("猫。庭に猫がすわっていた。", lexicon).single()

        assertEquals("庭に猫がすわっていた。", token.sentence)
        assertEquals(2, token.count)
    }

    @Test
    fun `several documents merge into one word list`() {
        val lexicon = lexiconOf("洗濯", "散歩")
        val accumulator = JapaneseTokenizer.Accumulator()
        accumulator.add("庭で洗濯をしていた。", lexicon)
        accumulator.add("公園まで散歩した。洗濯も済ませた。", lexicon)

        val tokens = accumulator.tokens().associateBy { it.baseForm }

        // Counts add up across files…
        assertEquals(2, tokens.getValue("洗濯").count)
        // …and the first occurrence (file 1) decides how early the word is.
        assertTrue(tokens.getValue("洗濯").firstOffset < tokens.getValue("散歩").firstOffset)
        assertEquals("庭で洗濯をしていた。", tokens.getValue("洗濯").sentence)
    }

    @Test
    fun `the copula is kept whole instead of being deconjugated into a lookalike`() {
        // だつ is a real dictionary reading, and the ~った rule offers it for
        // だった. Neither the grammar table nor the deconjugator may propose
        // that card.
        val lexicon = lexiconOf("だつ", "だる", "元気")
        val tokens = baseForms("元気だった。", lexicon)

        // 元気だった is one word wearing the copula, and that is how it is
        // counted — the copula no longer breaks off as its own token now that
        // the deconjugator strips it.
        assertEquals(listOf("元気"), tokens)
        assertFalse("だつ" in tokens)
        assertFalse("だる" in tokens)
    }

    @Test
    fun `a grammar form never eats the front of a content word`() {
        // たいへん and ないよう start with auxiliary-looking kana; that is why
        // たい and ない are not in the table.
        val lexicon = lexiconOf("たいへん", "ないよう")
        assertEquals(listOf("たいへん", "ないよう"), baseForms("たいへんないようだ。", lexicon))
    }

    @Test
    fun `empty text yields nothing`() {
        assertTrue(JapaneseTokenizer.tokenize("", lexiconOf("猫")).isEmpty())
    }

    @Test
    fun `a single katakana is not a word`() {
        // シ and セ are dictionary entries (musical notes, league abbreviations)
        // and longest match reaches them whenever a katakana name it does not
        // know is cut short.
        val lexicon = lexiconOf("シ", "セ", "は", "笑う")
        val bases = baseForms("シセは笑った。", lexicon)

        assertFalse(bases.toString(), "シ" in bases)
        assertFalse(bases.toString(), "セ" in bases)
    }

    /** A lexicon that also knows which surfaces a frequency list ranks. */
    private fun rankedLexicon(all: Set<String>, common: Set<String>) =
        object : JapaneseTokenizer.Lexicon {
            override fun contains(surface: String) = surface in all
            override fun isCommon(surface: String) = surface in common
        }

    @Test
    fun `a word with a case particle stuck to it is split`() {
        // JMdict lists 今日は — it is こんにちは, the greeting — and longest
        // match took it over 今日 + は, so 今日 lost half its occurrences.
        val lexicon = lexiconOf("今日", "今日は", "は", "暑い")
        val bases = baseForms("今日は暑い。", lexicon)

        assertTrue(bases.toString(), "今日" in bases)
        assertFalse(bases.toString(), "今日は" in bases)
    }

    @Test
    fun `a word that only looks like word plus particle is left alone`() {
        // こんにち is not a spelling of anything, so こんにちは stays whole.
        val lexicon = lexiconOf("こんにちは", "言う")
        assertTrue("こんにちは" in baseForms("こんにちはと言った。", lexicon))
    }

    @Test
    fun `a rare frozen inflection loses to the common verb behind it`() {
        // 急いで is a JMdict entry ranked 81 833; 急ぐ is ranked 1 800. The
        // text means the verb.
        val lexicon = rankedLexicon(
            all = setOf("急いで", "急ぐ", "帰る"),
            common = setOf("急ぐ", "帰る")
        )
        val bases = JapaneseTokenizer.tokenize("急いで帰った。", lexicon).map { it.baseForm }

        assertTrue(bases.toString(), "急ぐ" in bases)
        assertFalse(bases.toString(), "急いで" in bases)
    }

    @Test
    fun `a common entry keeps its own reading even if it could be deconjugated`() {
        // つまらない is the trap: it looks like the negative of 詰まる and is a
        // word in its own right. The frequency list says it is common, so it
        // is never re-read as something else.
        val lexicon = rankedLexicon(
            all = setOf("つまらない", "詰まる", "話"),
            common = setOf("つまらない", "詰まる", "話")
        )
        val bases = JapaneseTokenizer.tokenize("つまらない話。", lexicon).map { it.baseForm }

        assertTrue(bases.toString(), "つまらない" in bases)
        assertFalse(bases.toString(), "詰まる" in bases)
    }

    @Test
    fun `a two-character frozen form gives way to the verb`() {
        // 来た is a JMdict entry (an interjection) and was taking 50
        // occurrences off 来る in one novel.
        val lexicon = rankedLexicon(all = setOf("来た", "来る"), common = setOf("来る"))
        assertEquals(listOf("来る"), JapaneseTokenizer.tokenize("来た。", lexicon).map { it.baseForm })
    }

    @Test
    fun `the common deconjugation wins over the first one offered`() {
        // 続けている offers 続けて — an adverb JMdict lists, ranked nowhere —
        // one step before 続ける, ranked 196. Taking the first hit spent the
        // verb's occurrences on the adverb.
        val lexicon = rankedLexicon(
            all = setOf("続けて", "続ける", "いる"),
            common = setOf("続ける", "いる")
        )
        val bases = JapaneseTokenizer.tokenize("続けている。", lexicon).map { it.baseForm }

        assertTrue(bases.toString(), "続ける" in bases)
        assertFalse(bases.toString(), "続けて" in bases)
    }

    @Test
    fun `a two-character blend splits only when the word carries kanji`() {
        val lexicon = lexiconOf("何", "何を", "を", "なに", "な", "に", "話す")
        assertFalse("何を" in baseForms("何を話す。", lexicon))
        assertTrue("何" in baseForms("何を話す。", lexicon))
        // なに must not come apart into な and に.
        assertTrue("なに" in baseForms("なにを話す。", lexicon))
    }

    @Test
    fun `an honorific after a word is counted as evidence of a name`() {
        val lexicon = lexiconOf("池", "さん", "くん", "は", "笑う")
        val tokens = JapaneseTokenizer.tokenize("池くんと池さんは笑った。", lexicon)
        val ike = tokens.first { it.baseForm == "池" }

        assertEquals(2, ike.honorificHits)
    }
}
