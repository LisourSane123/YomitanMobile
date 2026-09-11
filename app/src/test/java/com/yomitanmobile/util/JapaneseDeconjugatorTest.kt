package com.yomitanmobile.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class JapaneseDeconjugatorTest {

    @Test
    fun politePast_returnsIchidanBase() {
        val forms = JapaneseDeconjugator.candidateForms("食べました")
        assertTrue(forms.contains("食べる"))
    }

    @Test
    fun plainPastGodan_returnsDictionaryForm() {
        val forms = JapaneseDeconjugator.candidateForms("飲んだ")
        assertTrue(forms.contains("飲む"))
    }

    @Test
    fun adjectivePast_returnsIAdjectiveBase() {
        val forms = JapaneseDeconjugator.candidateForms("高かった")
        assertTrue(forms.contains("高い"))
    }

    @Test
    fun causativePassivePast_recoversBaseForm() {
        val forms = JapaneseDeconjugator.candidateForms("食べさせられた")
        assertTrue(forms.contains("食べる"))
    }

    @Test
    fun analysis_doesNotEchoOriginalInput() {
        val analyzed = JapaneseDeconjugator.analyze("食べて")
        assertFalse(analyzed.any { it.baseForm == "食べて" })
    }

    // ---------------------------------------------------------------------
    // The forms below are what Japanese actually looks like in a sentence.
    // Every one of them used to return NOTHING, which meant a word copied out
    // of a subtitle or a book could not be looked up, the text scanner could
    // not reach its dictionary form, and no furigana was drawn for it.
    // ---------------------------------------------------------------------

    private fun assertReaches(base: String, vararg forms: String) {
        for (form in forms) {
            val candidates = JapaneseDeconjugator.candidateForms(form)
            assertTrue(
                "$form should deconjugate to $base, got $candidates",
                candidates.contains(base)
            )
        }
    }

    @Test
    fun teFormPlusAuxiliary_reachesTheDictionaryForm() {
        assertReaches(
            "食べる",
            "食べている", "食べてる", "食べていた", "食べてた", "食べています",
            "食べてしまった", "食べておく", "食べてみる"
        )
        assertReaches("走る", "走っています", "走っていた")
        assertReaches("読む", "読んでいる", "読んでみる")
    }

    @Test
    fun desiderative_reachesTheDictionaryForm() {
        assertReaches("食べる", "食べたい", "食べたかった", "食べたくない")
        assertReaches("行く", "行きたい")
        assertReaches("読む", "読みたい")
    }

    @Test
    fun conditionalAndVolitional_reachTheDictionaryForm() {
        assertReaches("食べる", "食べれば", "食べよう")
        assertReaches("行く", "行けば", "行こう")
        assertReaches("読む", "読めば", "読もう")
    }

    @Test
    fun godanPotential_reachesTheDictionaryForm() {
        assertReaches("読む", "読める")
        assertReaches("行く", "行ける")
        assertReaches("話す", "話せる")
        assertReaches("泳ぐ", "泳げる")
    }

    @Test
    fun suruVerbs_reachBothTheVerbAndItsNoun() {
        for (form in listOf("勉強した", "勉強して", "勉強します", "勉強しない", "勉強しよう")) {
            val candidates = JapaneseDeconjugator.candidateForms(form)
            assertTrue("$form -> 勉強する, got $candidates", candidates.contains("勉強する"))
            // Plenty of dictionaries list only the noun, so it is offered too.
            assertTrue("$form -> 勉強, got $candidates", candidates.contains("勉強"))
        }
    }

    @Test
    fun iku_isIrregularInItsTeAndPastForms() {
        // 行った comes from 行く, not from the 行う / 行つ / 行る the ~った rule
        // offers for every other godan verb.
        assertReaches("行く", "行った", "行って")
        assertReaches("持って行く", "持って行った")
    }

    @Test
    fun kuru_isIrregularInKana() {
        assertReaches("くる", "きた", "きて", "きました", "こない")
    }

    @Test
    fun copulaTails_leaveTheWordInFrontOfThem() {
        assertReaches("静か", "静かだった", "静かじゃない", "静かではない", "静かでした")
        assertReaches("元気", "元気だった", "元気です")
    }

    @Test
    fun spokenNegativeContractions_reachTheDictionaryForm() {
        assertReaches("食べる", "食べなきゃ")
        assertReaches("行く", "行かなくちゃ")
    }
}
