package com.yomitanmobile.domain.model

data class WordEntry(
    val id: Long = 0,
    val expression: String,
    val reading: String,
    val definitions: List<String>,
    // How common the word is, 1 = commonest (see DictionaryEntry.frequency).
    val frequency: Int = 0,
    // The leading list's own number, verbatim; "" when it does not know the word.
    val frequencyValue: String = "",
    // JMdict sequence of the source entry (0 when the dictionary has none).
    // Orders homographs: the core entry (バス "bus", 1098390) was added long
    // before the rare one sharing its spelling (バス "bath", 2845312).
    val sequenceNumber: Int = 0,
    val pitchAccent: String = "",
    val partsOfSpeech: String = "",
    val dictionaryName: String = "",
    val exampleSentence: String = "",
    val exampleSentenceTranslation: String = "",
    val audioFile: String = "",
    // 0 = no JLPT data; 1-5 = N1-N5
    val jlptLevel: Int = 0,
    // Examples from the dictionary (Jitendex) — first one mirrors the legacy
    // exampleSentence / exampleSentenceTranslation fields.
    val examples: List<ExamplePair> = emptyList(),
    // Sense-level usage hints peeled out of the gloss text by the mapper
    // (e.g. "usually kana", "formal", "archaic"). The UI renders these as
    // a chip rather than letting them clutter the definition line.
    val usageTags: List<String> = emptyList(),
    // Cross-references and ad-hoc usage notes peeled out of the gloss text
    // by NotesExtractor (e.g. "see also 食べる", "cf. 召し上がる",
    // "Note: usually written in kana"). Displayed in a dedicated card at
    // the bottom of the detail screen, away from the meaning column.
    val notes: List<String> = emptyList()
) {
    fun definitionText(): String = definitions.joinToString("; ")

    fun displayText(): String = expression.ifBlank { reading }

    /** Tier label, on the leading list's authority only; "" otherwise. */
    fun frequencyLabel(): String = FrequencyTier.label(frequency, frequencyValue)
}
