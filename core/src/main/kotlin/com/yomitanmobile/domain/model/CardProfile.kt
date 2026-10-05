package com.yomitanmobile.domain.model

/**
 * The Anki note type a card is written to, per study language.
 *
 * Two note types rather than one shared schema with blank columns. A Japanese
 * card's PitchAccent and KanjiBreakdown have no English counterpart at all —
 * not "empty for now", but permanently meaningless — and a note type whose
 * fields are half unused is one the user has to work around forever in
 * browse, search and card templates.
 *
 * The English Reading field holds the IPA pronunciation. It is the same slot
 * in the same place on the card: on a Japanese card it answers "how is this
 * said" with furigana, here with /ˈwɜːd/. The word itself never goes there —
 * it is already the front — which is why an English row's `reading` column
 * (a copy of the expression, as the parser writes it) is deliberately not
 * what gets exported.
 */
/**
 * Shared by every non-Japanese profile: the Japanese field list minus
 * PitchAccent and KanjiBreakdown, with Reading holding the IPA
 * transcription. A top-level value rather than a companion one — enum
 * constructors run before the companion object is initialised.
 */
private val LATIN_FIELDS = arrayOf(
    "Front", "FrontContext", "Reading", "Meaning",
    "Frequency", "Audio", "Sentence", "Summary"
)

enum class CardProfile(
    val modelName: String,
    val fieldNames: Array<String>
) {
    JAPANESE(
        modelName = "Yomitan-Mobile-v8",
        fieldNames = arrayOf(
            "Front", "FrontContext", "Reading", "Meaning", "PitchAccent",
            "Frequency", "Audio", "Sentence", "KanjiBreakdown", "Summary"
        )
    ),

    ENGLISH(
        modelName = "Yomitan-Mobile-EN-v1",
        fieldNames = LATIN_FIELDS
    ),

    /**
     * Same field set as English, separate note type on purpose: the two are
     * different decks with different card styling in practice, and merging
     * them would mean a user studying both cannot tell their notes apart in
     * the AnkiDroid browser.
     */
    SPANISH(
        modelName = "Yomitan-Mobile-ES-v1",
        fieldNames = LATIN_FIELDS
    );

    /** Sections this profile can actually render; the rest are dropped. */
    fun supports(section: CardSection): Boolean = when (this) {
        JAPANESE -> true
        // No kanji breakdown exists outside Japanese, and the template must
        // not reference a field the note type lacks — AnkiDroid renders an
        // unknown `{{Field}}` as literal text. PITCH survives because for
        // these profiles it is the pronunciation block, reading {{Reading}}
        // (the IPA) instead of {{PitchAccent}}.
        ENGLISH, SPANISH -> section != CardSection.KANJI
    }

    /**
     * Whether the reading belongs in the header, directly under the word.
     *
     * It does for Japanese: the kana ARE how the word is read, and a learner
     * looks at them together with the kanji. IPA is not that — it is a
     * footnote to the entry, and putting /ˈwɜːd/ under an English word pushes
     * the meaning down for something the reader rarely needs. So for the
     * Latin-script profiles the header is the word alone and the
     * pronunciation moves down into its own section.
     */
    val readingInHeader: Boolean get() = this == JAPANESE

    /**
     * The section order actually used, given the user's [order].
     *
     * The stored order is shared across languages and its default puts the
     * pronunciation block second, which is right for pitch accent (read with
     * the word) and wrong for IPA (read after the meaning, if at all). Rather
     * than keeping a separate saved order per language, the block is moved to
     * just after the meaning for the profiles where it is IPA.
     */
    fun orderSections(order: List<CardSection>): List<CardSection> {
        val supported = order.filter { supports(it) }
        if (this == JAPANESE || !supported.contains(CardSection.PITCH)) return supported
        val withoutPitch = supported.filterNot { it == CardSection.PITCH }
        val meaningAt = withoutPitch.indexOf(CardSection.MEANING)
        if (meaningAt < 0) return supported
        return withoutPitch.toMutableList().apply { add(meaningAt + 1, CardSection.PITCH) }
    }

    companion object {
        fun forLanguage(language: AppLanguage): CardProfile = when (language) {
            AppLanguage.JAPANESE -> JAPANESE
            AppLanguage.ENGLISH -> ENGLISH
            AppLanguage.SPANISH -> SPANISH
        }
    }
}
