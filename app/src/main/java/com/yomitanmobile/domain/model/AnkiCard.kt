package com.yomitanmobile.domain.model

data class AnkiCard(
    val front: String,
    val frontContext: String,
    val reading: String,
    val meaning: String,
    val pitchAccent: String,
    val frequency: String,
    val audioFileName: String,
    val sentence: String,
    val kanjiBreakdown: String = "",
    /**
     * Optional AI-generated summary. Rendered between pitch/frequency
     * and the meaning column on the back of the card. Empty string when
     * the user hasn't enabled the AI integration or when the request
     * failed — Anki's mustache `{{#Summary}}…{{/Summary}}` block then
     * collapses the divider too, so the card stays clean.
     */
    val summary: String = ""
) {
    /**
     * Field values in the order [profile] declares them. The two orders are
     * NOT a prefix of one another — English drops PitchAccent from the
     * middle — so this has to be built per profile rather than truncated.
     */
    fun toFieldArray(profile: CardProfile = CardProfile.JAPANESE): Array<String> =
        when (profile) {
            CardProfile.JAPANESE -> arrayOf(
                front, frontContext, reading, meaning, pitchAccent, frequency,
                audioFileName, sentence, kanjiBreakdown, summary
            )
            CardProfile.ENGLISH -> arrayOf(
                front, frontContext, reading, meaning, frequency,
                audioFileName, sentence, summary
            )
        }
}
