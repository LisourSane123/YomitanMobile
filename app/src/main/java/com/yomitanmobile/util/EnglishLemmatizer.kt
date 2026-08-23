package com.yomitanmobile.util

/**
 * Reduces an inflected English word to the base forms a dictionary lists.
 *
 * The English counterpart of [JapaneseDeconjugator], and deliberately the
 * same shape: rule-based, no external library, returning CANDIDATES rather
 * than one answer. English inflection is ambiguous without a lexicon —
 * "saw" is both a noun and the past of "see", "leaves" is both — so the
 * search runs every candidate and lets the dictionary decide which ones
 * exist. A wrong candidate simply matches nothing.
 *
 * Rules cover what regular English inflection actually does:
 *   • plural / 3rd person: -s, -es, -ies → -y
 *   • past / participle: -ed, -ied → -y, plus consonant de-doubling
 *   • progressive: -ing, with a restored -e (making → make) and de-doubling
 *   • comparative / superlative: -er, -est
 *
 * Irregular verbs (went → go, better → good) are NOT handled: there are a
 * few hundred of them, they cannot be derived, and a table of them is a
 * dictionary in its own right. They are the one case where the user has to
 * type the base form — and kty-en-pl lists many irregular forms as their own
 * headwords anyway, so the lookup usually still lands somewhere useful.
 */
object EnglishLemmatizer {

    private const val VOWELS = "aeiou"

    /** Longest first, so the most specific suffix rule gets its turn. */
    private val SUFFIXES = listOf("iest", "ies", "ied", "est", "ing", "ed", "er", "es", "s")

    /**
     * Base-form candidates for [word], most likely first, never including
     * the input itself (the caller always searches that separately).
     *
     * Capped like the deconjugator's candidate list: each candidate costs a
     * parallel query, and beyond a handful they are noise.
     */
    fun analyze(word: String, limit: Int = 8): List<String> {
        val normalized = word.trim().lowercase()
        if (normalized.length < 3) return emptyList()
        if (!normalized.all { it.isLetter() || it == '-' || it == '\'' }) return emptyList()

        val candidates = LinkedHashSet<String>()

        for (suffix in SUFFIXES) {
            if (!normalized.endsWith(suffix)) continue
            val stem = normalized.dropLast(suffix.length)
            if (stem.length < 2) continue

            when (suffix) {
                // studies → study, tried → try
                "ies", "ied", "iest" -> candidates.add(stem + "y")
                else -> {
                    candidates.add(stem)
                    // making → make, larger → large: the silent -e that the
                    // suffix displaced.
                    if (suffix == "ing" || suffix == "ed" || suffix == "er" || suffix == "est") {
                        candidates.add(stem + "e")
                    }
                    // running → run, bigger → big.
                    undoubleFinalConsonant(stem)?.let { candidates.add(it) }
                }
            }
        }

        // boxes → box, watches → watch. Handled after the generic -es rule
        // above so "boxe" is offered first only where it is a real stem.
        if (normalized.endsWith("es") && normalized.length > 3) {
            candidates.add(normalized.dropLast(2))
        }

        candidates.remove(normalized)
        return candidates.take(limit)
    }

    /**
     * "running" → "run" once the -ing is gone: a stem ending in a doubled
     * consonant after a single vowel.
     *
     * This cannot be decided correctly on its own — "falling" has exactly
     * the same shape, and there "fall" is the answer. Both are emitted, with
     * the plain stem first (see [analyze]); the dictionary is what settles
     * it, and the wrong one matches nothing. The preceding-vowel check still
     * earns its keep by rejecting the many stems where the doubling isn't
     * inflectional at all ("miss", "add", "off").
     */
    private fun undoubleFinalConsonant(stem: String): String? {
        if (stem.length < 3) return null
        val last = stem[stem.lastIndex]
        val secondLast = stem[stem.lastIndex - 1]
        if (last != secondLast) return null
        if (last in VOWELS) return null
        val beforePair = stem[stem.lastIndex - 2]
        if (beforePair !in VOWELS) return null
        return stem.dropLast(1)
    }
}
