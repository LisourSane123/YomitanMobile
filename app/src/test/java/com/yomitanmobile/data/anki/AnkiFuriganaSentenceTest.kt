package com.yomitanmobile.data.anki

import com.yomitanmobile.domain.model.ExamplePair
import com.yomitanmobile.domain.model.FuriganaSegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The exported Anki card renders example sentences with tap-to-reveal furigana.
 * The behaviour is self-contained in the field HTML (inline onclick + hidden
 * <rt>) so it needs no template/model change.
 */
class AnkiFuriganaSentenceTest {

    @Test
    fun perKanjiCompoundGroupsIntoOneTappableWord() {
        // Real Jitendex splits 果物 into TWO ruby elements (果→くだ, 物→もの);
        // the card must group them so ONE tap reveals the whole word.
        val ex = ExamplePair(
            jp = "もっと果物を食べるべきです。",
            en = "You should eat more fruit.",
            segments = listOf(
                FuriganaSegment("もっと", ""),
                FuriganaSegment("果", "くだ"),
                FuriganaSegment("物", "もの"),
                FuriganaSegment("を", ""),
                FuriganaSegment("食", "た"),
                FuriganaSegment("べるべきです。", "")
            )
        )
        val html = AnkiCardCreator.buildFuriganaSentenceHtml(ex)

        // 果 and 物 collapse into ONE <ruby> (one tap target) with both readings;
        // 食 is its own <ruby>. So exactly two <ruby> openings total.
        assertEquals("one ruby for 果物, one for 食", 2, Regex("<ruby").findAll(html).count())
        assertTrue("果物 group carries both readings", html.contains("くだ") && html.contains("もの"))
        // display:none (not visibility:hidden) so the hidden <rt> reserves no
        // horizontal width — otherwise native ruby pushes the base kanji apart
        // and the untapped sentence looks oddly spaced.
        assertTrue("reading hidden via display:none", html.contains("display:none"))
        assertFalse("must NOT use visibility:hidden (reserves width)", html.contains("visibility:hidden"))
        assertTrue("tap handler present", html.contains("onclick"))
        // The 果物 ruby contains two <rt> (both revealed by one tap).
        val firstRuby = Regex("<ruby.*?</ruby>", RegexOption.DOT_MATCHES_ALL).find(html)!!.value
        assertEquals("果物 ruby has two rt", 2, Regex("<rt").findAll(firstRuby).count())

        val visible = html.replace(Regex("<rt[^>]*>.*?</rt>"), "").replace(Regex("<[^>]+>"), "")
        assertEquals("visible text reproduces the sentence", "もっと果物を食べるべきです。", visible)
    }

    @Test
    fun furiganaColorDefaultsToInheritAndHonorsOverride() {
        val ex = ExamplePair(
            jp = "水。", en = "",
            segments = listOf(FuriganaSegment("水", "みず"))
        )
        // Blank ⇒ same color as text (inherit).
        val def = AnkiCardCreator.buildFuriganaSentenceHtml(ex, "")
        assertTrue("default inherits text color", def.contains("color:inherit"))

        // Explicit color is applied.
        val colored = AnkiCardCreator.buildFuriganaSentenceHtml(ex, "#ffb74d")
        assertTrue("explicit color applied", colored.contains("color:#ffb74d"))
        assertFalse("no inherit when overridden", colored.contains("color:inherit"))
    }

    @Test
    fun plainSentenceWithoutRubyStaysEscapedText() {
        val ex = ExamplePair(
            jp = "これはテスト。",
            en = "",
            segments = listOf(FuriganaSegment("これはテスト。", ""))
        )
        val html = AnkiCardCreator.buildFuriganaSentenceHtml(ex)
        assertFalse("no ruby for reading-less sentence", html.contains("<ruby"))
        assertEquals("これはテスト。", html)
    }

    @Test
    fun noSegmentsFallsBackToEscapedJp() {
        val ex = ExamplePair(jp = "A & B <x>", en = "", segments = emptyList())
        val html = AnkiCardCreator.buildFuriganaSentenceHtml(ex)
        assertFalse(html.contains("<ruby"))
        // HTML-escaped so the card can't be broken by punctuation.
        assertTrue("escaped", html.contains("&amp;") && html.contains("&lt;x&gt;"))
    }
}
