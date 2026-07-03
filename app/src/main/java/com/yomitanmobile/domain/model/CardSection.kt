package com.yomitanmobile.domain.model

/**
 * Reorderable back-side sections of an Anki card. The header block
 * (expression + reading + frequency) is fixed at the top — the user can
 * only reorder the blocks BELOW the always-on layer divider.
 *
 * [storageValue] is what we persist to DataStore (a single
 * comma-separated string). Display labels live next to it so the screen
 * doesn't have to maintain a parallel translation map.
 */
enum class CardSection(
    val storageValue: String,
    val polishLabel: String,
    val englishLabel: String
) {
    PITCH("pitch", "Akcent toniczny", "Pitch accent"),
    SUMMARY("summary", "Streszczenie AI", "AI summary"),
    MEANING("meaning", "Znaczenie", "Meaning"),
    SENTENCE("sentence", "Przykładowe zdanie", "Example sentence"),
    AUDIO("audio", "Audio", "Audio"),
    KANJI("kanji", "Rozkład kanji", "Kanji breakdown");

    companion object {
        fun defaultOrder(): List<CardSection> =
            listOf(SUMMARY, PITCH, MEANING, SENTENCE, KANJI, AUDIO)

        fun encode(order: List<CardSection>): String =
            order.joinToString(",") { it.storageValue }

        /**
         * Decode a stored order string back into a list. Missing sections
         * (e.g. when a future release adds a new one) are appended at the
         * end so an old preferences file doesn't silently hide them.
         * Duplicates in the saved string are deduplicated.
         */
        fun decode(stored: String?): List<CardSection> {
            if (stored.isNullOrBlank()) return defaultOrder()
            val recognized = stored.split(",")
                .mapNotNull { tok ->
                    val trimmed = tok.trim()
                    values().firstOrNull { it.storageValue == trimmed }
                }
                .distinct()
            val missing = values().filter { it !in recognized }
            return recognized + missing
        }
    }
}
