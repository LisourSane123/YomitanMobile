package com.yomitanmobile.domain.usecase

import com.yomitanmobile.domain.model.MergedWordEntry
import com.yomitanmobile.domain.model.WordEntry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NoiseRulesTest {

    private fun merged(
        expression: String,
        reading: String = expression,
        frequency: Int = 0,
        alternatives: List<String> = emptyList(),
        usage: List<String> = emptyList(),
        definitions: List<String> = listOf("meaning")
    ) = MergedWordEntry(
        primaryId = 1,
        primaryExpression = expression,
        reading = reading,
        definitions = definitions,
        alternativeExpressions = alternatives,
        frequency = frequency,
        usageTags = usage
    )

    @Test
    fun `sounds of dialogue are noise`() {
        val stoplist = setOf("よ", "な", "は", "ああ")
        for (word in listOf("ああああ", "くくくく", "はっはっ", "ちょっ", "にやっ", "よー", "なー", "はー", "ーーー")) {
            assertTrue(word, NoiseRules.isEmphaticNoise(word) { it in stoplist })
        }
    }

    @Test
    fun `real words with stretches and repeats are not`() {
        val stoplist = setOf("こと", "ほら", "ふん", "ああ")
        for (word in listOf("コート", "ホラー", "ファン", "ドキドキ", "ぎょっと", "すごーい", "いい", "ゆったり")) {
            assertFalse(word, NoiseRules.isEmphaticNoise(word) { it in stoplist })
        }
    }

    @Test
    fun `a rare kana piece of a kanji word is a fragment, a usually-kana word is not`() {
        assertTrue(NoiseRules.isKanaFragment("おり", merged("おり", frequency = 5595, alternatives = listOf("折"))))
        assertFalse(
            NoiseRules.isKanaFragment(
                "よだれ",
                merged("よだれ", frequency = 12760, alternatives = listOf("涎"), usage = listOf("usually kana, food"))
            )
        )
        assertFalse(NoiseRules.isKanaFragment("いい", merged("いい", frequency = 33, alternatives = listOf("良い"))))
    }

    @Test
    fun `bare numbers and number-plus-counter are noise, dates and 一 words are not`() {
        for (word in listOf("四十", "三百", "十人", "三十分", "二本")) assertTrue(word, NoiseRules.isBareNumber(word))
        for (word in listOf("一人", "一度", "二十日", "十一月", "二度と")) assertFalse(word, NoiseRules.isBareNumber(word))
    }

    @Test
    fun `a redirect is followed to its target`() = runBlocking {
        val redirect = WordEntry(
            expression = "じーちゃん",
            reading = "じーちゃん",
            definitions = listOf("⟶爺ちゃん", "爺ちゃん (redirected from じーちゃん)")
        )
        val target = WordEntry(expression = "爺ちゃん", reading = "じいちゃん", definitions = listOf("grandpa"), frequency = 17337)
        val all = listOf(redirect, target)

        val resolved = ScanEntryResolver.resolve(
            words = listOf("じーちゃん"),
            byExpressions = { list -> all.filter { it.expression in list } },
            byReadings = { emptyList() }
        )

        assertEquals("爺ちゃん", resolved["じーちゃん"]?.primaryExpression)
    }

    @Test
    fun `a redirect reached from a word fragment is dropped, not followed`() = runBlocking {
        val redirect = WordEntry(expression = "引", reading = "ひき", definitions = listOf("⟶引き"))
        val target = WordEntry(expression = "引き", reading = "ひき", definitions = listOf("pull"))
        val all = listOf(redirect, target)

        val resolved = ScanEntryResolver.resolve(
            words = listOf("引"),
            byExpressions = { list -> all.filter { it.expression in list } },
            byReadings = { emptyList() }
        )

        assertNull(resolved["引"])
    }
}
