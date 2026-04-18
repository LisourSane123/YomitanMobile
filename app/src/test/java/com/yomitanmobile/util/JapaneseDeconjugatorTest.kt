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
}
