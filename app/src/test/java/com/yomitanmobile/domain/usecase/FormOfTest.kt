package com.yomitanmobile.domain.usecase

import com.yomitanmobile.domain.model.MergedWordEntry
import kotlinx.coroutines.runBlocking
import com.yomitanmobile.domain.model.WordEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Spanish inflection path, on the shapes kty-es-en actually ships (checked
 * against the release: hablando, hablé, casas, buenas, dijo, fue, tenía).
 */
class FormOfTest {

    private fun entry(
        expression: String,
        definitions: List<String>,
        partsOfSpeech: List<String> = listOf("non-lemma")
    ) = MergedWordEntry(
        primaryId = 0,
        primaryExpression = expression,
        reading = expression,
        definitions = definitions,
        alternativeExpressions = emptyList(),
        frequency = 0,
        partsOfSpeech = partsOfSpeech,
        dictionaryName = "kty-es-en",
        usageTags = emptyList()
    )

    @Test
    fun `a conjugated form names its lemma`() {
        assertEquals("hablar", FormOf.baseOf(entry("hablando", listOf("hablar (gerund)"))))
        assertEquals(
            "decir",
            FormOf.baseOf(entry("dijo", listOf("decir (third-person singular preterite indicative)")))
        )
        assertEquals("casa", FormOf.baseOf(entry("casas", listOf("casa (plural)", "casar (second-person singular present indicative)"))))
    }

    @Test
    fun `a word that also exists in its own right is not replaced`() {
        // "buenas" is the feminine plural of bueno AND a greeting of its own.
        val buenas = entry(
            "buenas",
            definitions = listOf("hello, hi", "bueno (feminine plural)"),
            partsOfSpeech = listOf("intj inf", "non-lemma")
        )
        assertNull(FormOf.baseOf(buenas))
    }

    @Test
    fun `an apocopic form names the word it is short for`() {
        val gran = entry(
            "gran",
            definitions = listOf("(before the noun) Apocopic form of grande; great.", "grande (alternative)"),
            partsOfSpeech = listOf("adj abbv fem masc", "non-lemma")
        )
        assertEquals("grande", FormOf.baseOf(gran))
        // buen carries no pointer at all — only the English sentence.
        val buen = entry(
            "buen",
            definitions = listOf("(before the noun) Apocopic form of bueno (\u201cgood, fine\u201d)"),
            partsOfSpeech = listOf("adj abbv masc")
        )
        assertEquals("bueno", FormOf.baseOf(buen))
    }

    @Test
    fun `a word that is also a form of another word keeps its own entry`() {
        // "parte" is a noun and the third person of "partir"; "puesto" is a
        // market stall and the participle of "poner". Neither is replaced.
        val parte = entry(
            "parte",
            definitions = listOf("part; section; portion", "partir (third-person singular present indicative)"),
            partsOfSpeech = listOf("n masc", "non-lemma")
        )
        assertNull(FormOf.baseOf(parte))
    }

    @Test
    fun `an ordinary entry is not a form of anything`() {
        assertNull(FormOf.baseOf(entry("casa", listOf("house"), partsOfSpeech = listOf("n"))))
        // A gloss that merely carries a parenthesis is not a pointer.
        assertNull(FormOf.baseOf(entry("mesa", listOf("table (furniture)"), partsOfSpeech = listOf("n"))))
    }

    @Test
    fun `a sentence in brackets is not a headword`() {
        assertNull(FormOf.baseOfDefinition("a kind of tree found in the Andes (regional)"))
        assertEquals("darse cuenta", FormOf.baseOfDefinition("darse cuenta (first-person singular)"))
    }

    @Test
    fun `a single row answers the same question`() {
        val row = WordEntry(
            expression = "hablé",
            reading = "hablé",
            definitions = listOf("hablar (first-person singular preterite indicative)"),
            partsOfSpeech = "non-lemma"
        )
        assertEquals("hablar", FormOf.baseOf(row))
    }

    @Test
    fun `the scan resolver follows the form to its lemma`() = runBlocking {
        val hablando = WordEntry(
            expression = "hablando",
            reading = "hablando",
            definitions = listOf("hablar (gerund)"),
            partsOfSpeech = "non-lemma"
        )
        val hablar = WordEntry(
            expression = "hablar",
            reading = "hablar",
            definitions = listOf("to speak"),
            partsOfSpeech = "v"
        )
        val resolved = ScanEntryResolver.resolve(
            words = listOf("hablando"),
            byExpressions = { asked ->
                listOfNotNull(
                    hablando.takeIf { "hablando" in asked },
                    hablar.takeIf { "hablar" in asked }
                )
            },
            byReadings = { emptyList() }
        )
        assertEquals("hablar", resolved["hablando"]?.primaryExpression)
        assertTrue(resolved["hablando"]!!.definitions.contains("to speak"))
    }
}
