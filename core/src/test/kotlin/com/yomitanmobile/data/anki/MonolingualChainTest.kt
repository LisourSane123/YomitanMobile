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
    fun `an article about a work is skipped for the word's own article, or the next dictionary`() {
        val pixivWithWorks = listOf(
            word("フラストレーション", "フラストレーション", "«曲»", "フラストレーションとは彩音の楽曲の一つ"),
            word("フラストレーション", "フラストレーションすとれーしょん", "現在直面している出来事などに対して欲求不満が生じる事。"),
            word("チョップ", "チョップ", "«プリキュア»", "チョップとは、映画版オリジナルキャラクターである。")
        )
        val out = runBlocking {
            MonolingualChain.apply(
                listOf(word("フラストレーション", "フラストレーション", "frustration"), word("チョップ", "チョップ", "chop")),
                listOf("Pixiv", "旺文社"),
                lookup = { name, expressions ->
                    if (name == "Pixiv") pixivWithWorks.filter { it.expression in expressions }
                    else listOf(word("チョップ", "チョップ", "手刀で打つこと。")).filter { it.expression in expressions }
                }
            )
        }
        assertEquals(listOf("現在直面している出来事などに対して欲求不満が生じる事。"), out[0].definitions)
        assertEquals("旺文社", out[1].meaningSource)
        assertTrue(MonolingualChain.isWorkEntry(listOf("«曲»", "x")))
        assertFalse(MonolingualChain.isWorkEntry(listOf("猫とは«ネコ科»の動物。")))
    }

    @Test
    fun `pointers and navigation pages count as lacking, real definitions do not`() {
        for (pointer in listOf(
            "「ドキドキ」の表記揺れ", "「つる」のカタカナ表記。", "｢籠｣の平仮名読みである表記揺れ。",
            "噂の別表記である。", "うさぎの表記ゆれ。およびキャラクターの別称。",
            "曖昧さ回避。以下のリンク先を参照。", "人名、あるいはキャラクター名。",
            "ぶ‐かつどう〔‐クワツドウ〕【部活動】\n⇒部活", "「アカン」に同じ", "インター‐ハイ\n《(和)inter＋high schoolの略》⇒全国高等学校総合体育大会",
            "そぐわ◦ない〔そぐはない〕\n［連語］《動詞「そぐう」の未然形＋打消しの助動詞「ない」》\n⇒そぐう",
            "インター‐ハイ《(和)inter＋high schoolの略。「インターカレッジ」にまねた造語》⇒全国高等学校総合体育大会",
            "そぐわ◦ない〔そぐはない〕［連語］《動詞「そぐう」の未然形＋打消しの助動詞「ない」》⇒そぐう", "とうきょう‐と〔トウキヤウ‐〕【東京都】\n⇒東京"
        )) assertTrue(pointer, MonolingualChain.isSpellingPointer(pointer))
        for (definition in listOf(
            "ネコ科の動物名。世界中で飼育されている。",
            "さ‐どう〔‐ダウ〕【茶道】\n① ⇒ちゃどう（茶道）\n② 「茶頭 (さどう) 」に同じ。",
            "かく‐とく〔クワク‐〕【獲得】［名］(スル)手に入れること。",
            "表記揺れとは、同じ語が複数の書き方をされること。",
            "「アカン」に同じ。関西で、だめだという意。"
        )) assertFalse(definition, MonolingualChain.isSpellingPointer(definition))
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
