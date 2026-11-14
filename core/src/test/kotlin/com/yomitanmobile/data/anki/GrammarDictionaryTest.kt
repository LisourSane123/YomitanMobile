package com.yomitanmobile.data.anki

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class GrammarDictionaryTest {

    private val bunpro = """
        [["あくまでも","あくまでも","あくまでも","",0,[
          {"type":"structured-content","content":[
            "【 Meaning 】",{"tag":"div","content":"To the (bitter) end, Stubbornly"},
            "【 Explanation 】",{"tag":"div","content":"飽く is the old-fashioned version of 飽きる."},
            "【 Examples 】",{"tag":"ol","content":[
              {"tag":"li","content":"飽くまでも、不正に立ち向かおうとします。\n I will fight against injustice to the end."},
              {"tag":"li","content":"飽くまでもやると言うなら、私は止めません。\n If you insist, I won't stop you."}]}]},
          {"type":"structured-content","content":[{"tag":"a","href":"https://bunpro.jp/x","content":"Link to Bunpro"}]}
        ],1,"N2"],
        ["くらい","くらい","","",0,["文法項目 | くらい\n [解説]\n Approximate quantity."],0,""]]
    """.trimIndent()

    @Test
    fun `bunpro's parts are read apart and the link is dropped`() {
        val (akumade, kurai) = GrammarDictionary.parseBank(bunpro)
        assertEquals("To the (bitter) end, Stubbornly", akumade.meaning)
        assertEquals("飽く is the old-fashioned version of 飽きる.", akumade.explanation)
        assertEquals("N2", akumade.level)
        assertEquals(2, akumade.examples.size)
        assertEquals("飽くまでも、不正に立ち向かおうとします。" to "I will fight against injustice to the end.", akumade.examples[0])
        // A plain-text dictionary is all explanation.
        assertEquals("", kurai.meaning)
        assertTrue(kurai.explanation.startsWith("文法項目"))
    }

    @Test
    fun `the card's definition carries meaning, explanation and examples, and is recognisable`() {
        val html = GrammarDictionary.definitionHtml(GrammarDictionary.parseBank(bunpro).take(1))
        assertTrue(GrammarDictionary.isGrammarDefinition(html))
        assertTrue("grammar-meaning" in html && "grammar-explanation" in html && "I will fight against injustice" in html)
        assertFalse("Link to Bunpro" in html)
        assertFalse(GrammarDictionary.isGrammarDefinition("<div class=\"pos-line\">noun</div>cat"))
    }

    /** The real zips, when GRAMMAR_DIR names their folder. */
    @Test
    fun realDictionaries() {
        val dir = System.getenv("GRAMMAR_DIR")?.let(::File)
        assumeTrue(dir != null && dir.isDirectory)
        for (zip in dir!!.listFiles { f -> f.name.startsWith("[Grammar]") && f.extension == "zip" }!!.sorted()) {
            val d = GrammarDictionary.read(zip)
            println("${d.title}: ${d.size} points")
            for (key in listOf("既に", "ところで", "くらい", "は")) {
                val found = d.find(listOf(key))
                println("  $key → ${found.size} | ${found.firstOrNull()?.let { "${it.level} | ${it.meaning.take(50)} | ${it.explanation.take(70).replace("\n", "⏎")} | ex=${it.examples.size}" }}")
            }
        }
    }
}
