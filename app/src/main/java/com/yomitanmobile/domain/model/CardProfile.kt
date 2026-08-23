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
        // No pitch diagram and no kanji breakdown exist outside Japanese, so
        // the template must not reference fields the note type does not have
        // — AnkiDroid renders an unknown `{{Field}}` as literal text.
        ENGLISH, SPANISH -> section != CardSection.PITCH && section != CardSection.KANJI
    }

    companion object {
        fun forLanguage(language: AppLanguage): CardProfile = when (language) {
            AppLanguage.JAPANESE -> JAPANESE
            AppLanguage.ENGLISH -> ENGLISH
            AppLanguage.SPANISH -> SPANISH
        }
    }
}
