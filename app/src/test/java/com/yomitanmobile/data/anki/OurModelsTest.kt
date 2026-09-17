package com.yomitanmobile.data.anki

import com.yomitanmobile.domain.model.CardProfile
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which note types this app may rewrite.
 *
 * `AnkiCardCreator.ourModels()` is the gate in front of every write into an
 * existing collection — restyling templates, rewriting note fields — and a
 * false positive there damages cards the app did not create. The provider is
 * not reachable from a JVM test, so the two rules it applies are asserted
 * directly here, on the names real collections actually carry.
 */
class OurModelsTest {

    /** The name half of `ourModels()`, as that function applies it. */
    private fun nameMatches(name: String, profile: CardProfile = CardProfile.JAPANESE): Boolean {
        val base = profile.modelName
        return name == base || name.startsWith("$base-")
    }

    /** The field half: every field of the profile has to be there. */
    private fun fieldsMatch(
        fields: List<String>,
        profile: CardProfile = CardProfile.JAPANESE
    ): Boolean = fields.containsAll(profile.fieldNames.toList())

    @Test
    fun `our own note type matches`() {
        assertTrue(nameMatches("Yomitan-Mobile-v8"))
    }

    @Test
    fun `the strays our old refusal path minted match`() {
        // These are the names from the real collection that started all this.
        assertTrue(nameMatches("Yomitan-Mobile-v8-1"))
        assertTrue(nameMatches("Yomitan-Mobile-v8-1-a3f9c2"))
        assertTrue(nameMatches("Yomitan-Mobile-v8-1129"))
    }

    @Test
    fun `desktop Yomitan note types are not ours`() {
        // What people actually name the note type Yomitan/Yomichan writes to.
        for (name in listOf(
            "Yomitan",
            "Yomichan",
            "Japanese",
            "Japanese (recognition)",
            "Mining",
            "JP Mining Note",
            "Lapis",
            "yomitan-mobile-v8",
            "Yomitan Mobile v8",
            "My Yomitan-Mobile-v8"
        )) {
            assertFalse("'$name' must not be treated as ours", nameMatches(name))
        }
    }

    @Test
    fun `shared decks are not ours`() {
        for (name in listOf(
            "Core 2000",
            "Core 2k/6k Optimized",
            "Kaishi 1.5k",
            "Tango N5",
            "Basic",
            "Basic (and reversed card)",
            "Cloze"
        )) {
            assertFalse("'$name' must not be treated as ours", nameMatches(name))
        }
    }

    @Test
    fun `a note type carrying our name but not our fields is refused`() {
        // The second gate: if a name ever collided, the templates we would
        // write refer to fields that type does not have, and AnkiDroid renders
        // an unknown {{Field}} as literal text.
        assertFalse(fieldsMatch(listOf("Front", "Back")))
        assertFalse(fieldsMatch(listOf("Expression", "Meaning", "Reading")))
    }

    @Test
    fun `our own field set passes, and extra fields do not disqualify it`() {
        val ours = CardProfile.JAPANESE.fieldNames.toList()
        assertTrue(fieldsMatch(ours))
        // A user who added a field of their own to our note type still has
        // our note type — every field the templates name is present.
        assertTrue(fieldsMatch(ours + "My Notes"))
    }

    @Test
    fun `an English note type is not rewritten by a Japanese session`() {
        // ourModels() asks the CURRENT profile, so a Japanese session sees
        // neither the English name nor the English field set.
        assertFalse(nameMatches(CardProfile.ENGLISH.modelName, CardProfile.JAPANESE))
        assertFalse(fieldsMatch(CardProfile.ENGLISH.fieldNames.toList(), CardProfile.JAPANESE))
    }
}
