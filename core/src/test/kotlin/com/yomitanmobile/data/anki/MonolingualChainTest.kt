package com.yomitanmobile.data.anki

import com.yomitanmobile.domain.model.WordEntry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MonolingualChainTest {

    private fun word(expression: String, reading: String, vararg definitions: String) =
        WordEntry(expression = expression, reading = reading, definitions = definitions.toList())

    private val pixiv = listOf(
        word("猫", "ねこ", "ネコ科の動物。"),
        word("どきどき", "どきどき", "「ドキドキ」の表記揺れ")
    )
    private val obunsha = listOf(
        word("どきどき", "どきどき", "激しい運動や不安などで心臓の鼓動が速くなるさま。"),
        word("走る", "はしる", "足を速く動かして進む。")
    )

    private fun run(entries: List<WordEntry>, asked: MutableList<Pair<String, List<String>>> = ArrayList()) = runBlocking {
        MonolingualChain.apply(entries, listOf("Pixiv", "旺文社"), lookup = { name, expressions ->
            asked += name to expressions
            (if (name == "Pixiv") pixiv else obunsha).filter { it.expression in expressions }
        })
    }

    @Test
    fun `first dictionary that has the word answers, the next only for the rest`() {
        val asked = ArrayList<Pair<String, List<String>>>()
        val out = run(listOf(word("猫", "ねこ", "cat"), word("走る", "はしる", "to run")), asked)
        assertEquals(listOf("ネコ科の動物。"), out[0].definitions)
        assertEquals("Pixiv", out[0].meaningSource)
        assertEquals(listOf("足を速く動かして進む。"), out[1].definitions)
        assertEquals("旺文社", out[1].meaningSource)
        assertEquals(listOf("旺文社" to listOf("走る")), asked.drop(1))
    }

    @Test
    fun `a spelling pointer counts as lacking`() {
        val out = run(listOf(word("どきどき", "どきどき", "thump-thump")))
        assertEquals("旺文社", out[0].meaningSource)
    }

    @Test
    fun `a word no dictionary has keeps English and no source`() {
        val out = run(listOf(word("犬", "いぬ", "dog")))
        assertEquals(listOf("dog"), out[0].definitions)
        assertEquals("", out[0].meaningSource)
    }

    @Test
    fun `chain setting is one name per line`() {
        assertEquals(listOf("A", "B"), MonolingualChain.parse(MonolingualChain.serialize(listOf("A", " B ", "A"))))
        assertEquals(listOf("三省堂"), MonolingualChain.parse("三省堂"))
    }

    @Test
    fun `refresh keeps a Japanese meaning when the rebuild only has English`() {
        val merged = RefreshMerge.merge(
            mapOf("Meaning" to "ネコ科の動物。SNSでも人気。"),
            mapOf("Meaning" to "cat (esp. the domestic cat, Felis catus)")
        )
        assertEquals("ネコ科の動物。SNSでも人気。", merged["Meaning"])
        assertTrue(RefreshMerge.isJapaneseMeaning("心臓の鼓動が速くなるさま。"))
        assertFalse(RefreshMerge.isJapaneseMeaning("to eat (see 食う)"))
        // English → English still refreshes.
        assertEquals("cat", RefreshMerge.merge(mapOf("Meaning" to "kitty"), mapOf("Meaning" to "cat"))["Meaning"])
    }
}
