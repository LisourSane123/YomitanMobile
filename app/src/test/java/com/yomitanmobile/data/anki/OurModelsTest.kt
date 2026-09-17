package com.yomitanmobile.data.anki

import com.yomitanmobile.domain.model.CardProfile
import com.yomitanmobile.domain.model.CardSection
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which note types this app may rewrite, and what it writes into a lean one.
 *
 * `AnkiCardCreator.ourModels()` is the gate in front of every write into an
 * existing collection — restyling templates, rewriting note fields — and a
 * false positive there damages cards the app did not create. The provider is
 * not reachable from a JVM test, so the rules are asserted directly, on the
 * names real collections actually carry.
 */
class OurModelsTest {

    /**
     * The name rule, mirroring `AnkiCardCreator.isOurModelName`.
     *
     * The blocklist is checked first there for the same reason it is here: the
     * prefix rule is the kind that gets loosened later, and these are the names
     * it must never reach.
     */
    private fun nameMatches(name: String, profile: CardProfile = CardProfile.JAPANESE): Boolean {
        val trimmed = name.trim()
        if (STANDARD.any { it.equals(trimmed, ignoreCase = true) }) return false
        val base = profile.modelName
        return trimmed == base || trimmed.startsWith("$base-")
    }

    private val STANDARD = listOf(
        "Basic", "Basic (and reversed card)", "Cloze", "Image Occlusion",
        "Yomitan", "Yomichan", "Japanese", "Japanese (recognition)", "Mining",
        "JP Mining Note", "Lapis", "Core 2000", "Core 2k/6k Optimized",
        "Core 6000", "Kaishi 1.5k", "Tango N5"
    )

    @Test
    fun `our own note type matches`() {
        assertTrue(nameMatches("Yomitan-Mobile-v8"))
    }

    @Test
    fun `the strays our old refusal path minted match`() {
        // These are the shapes from the real collection that started all this.
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
            "yomitan-mobile-v8 extra",
            "Yomitan Mobile v8",
            "My Yomitan-Mobile-v8"
        )) {
            assertFalse("'$name' must not be treated as ours", nameMatches(name))
        }
    }

    @Test
    fun `shared decks and Anki's own note types are not ours`() {
        for (name in listOf(
            "Core 2000",
            "Core 2k/6k Optimized",
            "Core 6000",
            "Kaishi 1.5k",
            "Tango N5",
            "Basic",
            "Basic (and reversed card)",
            "Cloze",
            "Image Occlusion"
        )) {
            assertFalse("'$name' must not be treated as ours", nameMatches(name))
        }
    }

    @Test
    fun `an English note type is not touched by a Japanese session`() {
        assertFalse(nameMatches(CardProfile.ENGLISH.modelName, CardProfile.JAPANESE))
    }

    /**
     * The point of dropping the field test: a note type from this app's own
     * development has fewer fields, and it is exactly the one worth fixing. It
     * must be recognised as ours, and the template written to it must name
     * only fields it has — AnkiDroid prints an unknown `{{Field}}` verbatim.
     */
    @Test
    fun `a lean note type from an older version is still ours`() {
        assertTrue(nameMatches("Yomitan-Mobile-v8-3"))
    }

    @Test
    fun `the template for a lean note type names only its own fields`() {
        // No Summary, no FrontContext, no KanjiBreakdown, no Frequency —
        // roughly what an early version of the note type looked like.
        val lean = setOf("Front", "Reading", "Meaning", "PitchAccent", "Audio", "Sentence")
        val back = AnkiCardCreator.buildBackTemplate(
            CardSection.defaultOrder(),
            CardProfile.JAPANESE,
            lean
        )
        for (missing in listOf("Summary", "KanjiBreakdown", "Frequency", "FrontContext")) {
            assertFalse(
                "the back template must not name $missing",
                back.contains("{{$missing}}") || back.contains("{{#$missing}}")
            )
        }
        // …and it still renders the card: word, reading, meaning.
        assertTrue(back.contains("{{Front}}"))
        assertTrue(back.contains("{{Reading}}"))
        assertTrue(back.contains("{{Meaning}}"))

        val front = AnkiCardCreator.buildFrontTemplate(lean)
        assertTrue(front.contains("{{Front}}"))
        assertFalse(front.contains("FrontContext"))
    }

    @Test
    fun `a full note type gets the full template`() {
        val full = CardProfile.JAPANESE.fieldNames.toSet()
        val back = AnkiCardCreator.buildBackTemplate(
            CardSection.defaultOrder(),
            CardProfile.JAPANESE,
            full
        )
        for (field in listOf("Summary", "KanjiBreakdown", "Frequency", "Meaning")) {
            assertTrue("the back template must name $field", back.contains(field))
        }
    }

    @Test
    fun `a note type that lost its Front field is fronted with its first one`() {
        val odd = setOf("Expression", "Meaning")
        val front = AnkiCardCreator.buildFrontTemplate(odd)
        assertTrue(front.contains("{{Expression}}"))
        assertFalse(front.contains("{{Front}}"))
    }
}
