package com.yomitanmobile.data.anki

import com.yomitanmobile.domain.model.CardProfile
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The `.apkg` format is undocumented, so these are the contract.
 *
 * Every assertion here is something Anki reads on import and fails, or
 * silently mis-handles, when it is wrong — and none of it is visible in the
 * app, so nothing else would catch a regression.
 */
class ApkgSchemaTest {

    @Test
    fun `fields are joined with the separator Anki splits on`() {
        val joined = ApkgSchema.joinFields(arrayOf("食べる", "たべる", "to eat"))
        assertEquals("食べるたべるto eat", joined)
    }

    @Test
    fun `the sort field carries the word, not the markup around it`() {
        val sort = ApkgSchema.stripHtml("<div class=\"x\">食べる</div>[sound:a.mp3]")
        assertEquals("食べる", sort)
    }

    @Test
    fun `the checksum is the first eight hex digits of the stripped field`() {
        // Anki's own value for "a" — sha1("a") starts 86f7e437.
        assertEquals(0x86f7e437L, ApkgSchema.fieldChecksum("a"))
    }

    @Test
    fun `the checksum ignores markup, so a styled duplicate still matches`() {
        assertEquals(
            ApkgSchema.fieldChecksum("食べる"),
            ApkgSchema.fieldChecksum("<b>食べる</b>")
        )
    }

    @Test
    fun `two notes never share a guid`() {
        assertNotEquals(ApkgSchema.guid("食べる", 1), ApkgSchema.guid("食べる", 2))
    }

    @Test
    fun `the note type declares exactly the profile's fields, in order`() {
        val model = ApkgSchema.model(
            modelId = 1L,
            profile = CardProfile.JAPANESE,
            css = ".card {}",
            frontTemplate = "{{Front}}",
            backTemplate = "{{Meaning}}",
            deckId = 2L,
            now = 1_700_000_000_000
        )
        val fields = model.getJSONArray("flds")
        assertEquals(CardProfile.JAPANESE.fieldNames.size, fields.length())
        CardProfile.JAPANESE.fieldNames.forEachIndexed { index, name ->
            assertEquals(name, fields.getJSONObject(index).getString("name"))
            // `ord` is what the note's flds positions are read against: a
            // mismatch silently shuffles every field on every card.
            assertEquals(index, fields.getJSONObject(index).getInt("ord"))
        }
        assertEquals(CardProfile.JAPANESE.modelName, model.getString("name"))
    }

    @Test
    fun `the English profile's note type drops the Japanese-only fields`() {
        val model = ApkgSchema.model(
            1L, CardProfile.ENGLISH, "", "{{Front}}", "{{Meaning}}", 2L, 0
        )
        val names = (0 until model.getJSONArray("flds").length())
            .map { model.getJSONArray("flds").getJSONObject(it).getString("name") }
        assertTrue("PitchAccent" !in names)
        assertTrue("KanjiBreakdown" !in names)
    }

    @Test
    fun `the deck map always carries the default deck Anki looks for`() {
        val decks: JSONObject = ApkgSchema.decks(deckId = 99L, deckName = "JLPT N5", now = 0)
        assertTrue(decks.has("1"))
        assertEquals("JLPT N5", decks.getJSONObject("99").getString("name"))
        // Every deck points at the single config below, which must exist.
        assertEquals(1, decks.getJSONObject("99").getInt("conf"))
        assertTrue(ApkgSchema.deckConfig(0).has("1"))
    }

    @Test
    fun `the template lives on the note type, so one import cannot mint two`() {
        val model = ApkgSchema.model(
            1L, CardProfile.JAPANESE, "css", "FRONT", "BACK", 2L, 0
        )
        val template = model.getJSONArray("tmpls").getJSONObject(0)
        assertEquals("FRONT", template.getString("qfmt"))
        assertEquals("BACK", template.getString("afmt"))
        assertEquals("css", model.getString("css"))
    }
}
