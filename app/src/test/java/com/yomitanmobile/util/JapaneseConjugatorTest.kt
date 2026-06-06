package com.yomitanmobile.util

import org.junit.Assert.assertTrue
import org.junit.Test

class JapaneseConjugatorTest {

    @Test
    fun ichidanProducesCommonInflections() {
        val forms = JapaneseConjugator.inflectedForms("たべる")
        assertTrue(forms.containsAll(listOf("たべる", "たべた", "たべて", "たべない", "たべます")))
    }

    @Test
    fun godanMuProducesEuphonicForms() {
        val forms = JapaneseConjugator.inflectedForms("のむ")
        assertTrue(forms.containsAll(listOf("のむ", "のんだ", "のんで", "のまない", "のみます", "のめる")))
    }

    @Test
    fun godanKuProducesIteForms() {
        val forms = JapaneseConjugator.inflectedForms("かく")
        assertTrue(forms.containsAll(listOf("かく", "かいた", "かいて", "かかない", "かきます")))
    }

    @Test
    fun iAdjectiveProducesPastAndNegative() {
        val forms = JapaneseConjugator.inflectedForms("たかい")
        assertTrue(forms.containsAll(listOf("たかい", "たかかった", "たかくない", "たかくて")))
    }

    @Test
    fun suruIsHandledIrregularly() {
        val forms = JapaneseConjugator.inflectedForms("べんきょうする")
        assertTrue(forms.containsAll(listOf("べんきょうする", "べんきょうした", "べんきょうして", "べんきょうしない")))
    }

    @Test
    fun kuruIsHandledIrregularly() {
        val forms = JapaneseConjugator.inflectedForms("くる")
        assertTrue(forms.containsAll(listOf("くる", "きた", "きて", "こない")))
    }

    @Test
    fun nonInflectableWordComesBackAsItself() {
        val forms = JapaneseConjugator.inflectedForms("ねこ")
        assertTrue(forms.contains("ねこ"))
    }
}
