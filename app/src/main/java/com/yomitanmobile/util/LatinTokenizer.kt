package com.yomitanmobile.util

/**
 * Splits English or Spanish text into the dictionary words it contains.
 *
 * The counterpart of [JapaneseTokenizer] for a language that writes its word
 * boundaries down, so almost none of the Japanese machinery applies: there is
 * no longest-match segmentation, no lexicon-driven choice between two readings
 * of the same characters, and no rule keeping a particle from being swallowed.
 * What is left is the part the Japanese tokeniser also does — count each word,
 * remember the sentence it was first met in and how early it appears, and
 * reduce a surface form to the headword a dictionary actually lists.
 *
 * Three things do need deciding, and each is a rule here:
 *
 *  * **Inflection.** "children", "ran" and "studies" are not headwords. The
 *    surface is offered to the dictionary first (some inflected forms ARE
 *    entries), then to [EnglishLemmatizer], and the first candidate the
 *    dictionary knows wins — the same "let the lexicon decide" contract
 *    search uses. Spanish gets no lemmatiser (none exists in the app), so its
 *    words resolve as written; that is stated rather than hidden, because the
 *    result is a thinner Spanish deck, not a wrong one.
 *  * **Clitics.** "don't", "she's", "Ana's" are one token to a regex and two
 *    words to a reader. The apostrophe is cut where the dictionary confirms
 *    the stem, so a possessive reaches its noun and a contraction its verb.
 *  * **Names.** English marks them with a capital letter, and mid-sentence is
 *    the only place that means anything (every sentence starts with one).
 *    The count goes to [Token.nameHits], where the planner weighs it against
 *    the frequency lists exactly as it weighs a Japanese honorific.
 *
 * Deliberately one token per word: "give up" and "in spite of" are dictionary
 * entries, but matching multi-word entries by longest run would also merge
 * "out of", "of the" and every other pair the dictionary happens to list, and
 * the frequency lists — single words only — could not rank the result to tell
 * the difference. A phrasal verb therefore becomes a card for its verb.
 */
object LatinTokenizer {

    /** What the installed dictionaries can answer about a word. */
    fun interface Lexicon {
        fun contains(word: String): Boolean
    }

    /** Base forms to try when the surface itself is not a headword. */
    fun interface Lemmatizer {
        fun basesOf(word: String): List<String>
    }

    val ENGLISH = Lemmatizer { EnglishLemmatizer.analyze(it) }

    /** For a language with no lemmatiser: the surface is the only candidate. */
    val NONE = Lemmatizer { emptyList() }

    data class Token(
        /** Headword the card would be made for; the lowercased surface when no dictionary knows it. */
        val baseForm: String,
        /** Surface as it first appeared. */
        val surface: String,
        val count: Int,
        /** True when the surface had to be reduced to reach the base form. */
        val wasInflected: Boolean,
        /** Sentence it was first met in, or empty when none was of a usable length. */
        val sentence: String = "",
        val firstOffset: Int = 0,
        /**
         * How often the word was capitalised in the middle of a sentence —
         * what a person's, a place's or a brand's name looks like in a book.
         */
        val nameHits: Int = 0
    )

    class Accumulator(private val lemmatizer: Lemmatizer = ENGLISH) {
        private val counts = LinkedHashMap<String, MutableToken>()
        private var consumed = 0

        val totalLength: Int get() = consumed

        fun add(text: String, lexicon: Lexicon, offsetBase: Int = consumed) {
            // Resolution is the hot path and a book repeats its words
            // constantly, so every surface → base decision is memoised for
            // the whole document, exactly as the Japanese tokeniser does.
            val resolved = HashMap<String, List<String>>()
            forEachSentence(text) { sentence, start ->
                scanSentence(sentence, start + offsetBase, lexicon, resolved)
            }
            consumed = offsetBase + text.length
        }

        fun tokens(): List<Token> = counts.map { (base, value) ->
            Token(
                baseForm = base,
                surface = value.surface,
                count = value.count,
                wasInflected = value.wasInflected,
                sentence = value.sentence,
                firstOffset = value.firstOffset,
                nameHits = value.nameHits
            )
        }

        private fun scanSentence(
            sentence: String,
            sentenceOffset: Int,
            lexicon: Lexicon,
            resolved: HashMap<String, List<String>>
        ) {
            val usableSentence = sentence.trim()
                .takeIf { it.length in MIN_SENTENCE_LENGTH..MAX_SENTENCE_LENGTH }
                .orEmpty()
            var first = true
            for (match in WORD.findAll(sentence)) {
                val surface = trimEdges(match.value)
                if (surface.isEmpty()) continue
                val opensSentence = first
                first = false
                // A capital that only says "a sentence starts here" says
                // nothing about the word.
                val isName = !opensSentence && surface[0].isUpperCase() && surface.any { it.isLowerCase() }
                val bases = resolved.getOrPut(surface) { basesOf(surface, lexicon) }
                for (base in bases) {
                    val token = counts.getOrPut(base) {
                        MutableToken(
                            surface = surface,
                            firstOffset = sentenceOffset + match.range.first,
                            wasInflected = base != surface && base != surface.lowercase()
                        )
                    }
                    token.count++
                    if (isName) token.nameHits++
                    if (token.sentence.isEmpty()) token.sentence = usableSentence
                }
            }
        }

        /**
         * The headwords one surface stands for: normally one, two only when a
         * hyphenated compound no dictionary lists is read as its parts
         * ("half-forgotten" → "half", "forgotten").
         */
        private fun basesOf(surface: String, lexicon: Lexicon): List<String> {
            resolve(surface, lexicon)?.let { return listOf(it) }
            if (HYPHENS.none { it in surface }) return listOf(surface.lowercase())
            val parts = surface.split(*HYPHENS)
                .filter { it.length >= MIN_PART_LENGTH }
                .mapNotNull { resolve(it, lexicon) }
            return parts.ifEmpty { listOf(surface.lowercase()) }
        }

        /** The headword this surface is, or null when no installed dictionary has one. */
        private fun resolve(surface: String, lexicon: Lexicon): String? {
            if (lexicon.contains(surface)) return surface
            val lower = surface.lowercase()
            if (lower != surface && lexicon.contains(lower)) return lower
            clitic(lower)?.let { stem ->
                if (lexicon.contains(stem)) return stem
                // "doesn't" → "does" → "do": a clitic can leave an inflected
                // form behind, so the lemmatiser gets its turn on it too.
                lemmatizer.basesOf(stem).firstOrNull { lexicon.contains(it) }?.let { return it }
            }
            return lemmatizer.basesOf(lower).firstOrNull { lexicon.contains(it) }
        }
    }

    /**
     * What is left of a word once an attached clitic is cut off: a possessive
     * ("Ana's" → "Ana"), a negative ("don't" → "do", and the three that hide
     * a letter) or one of the verb contractions.
     */
    fun clitic(lower: String): String? {
        val word = lower.replace('’', '\'')
        if ('\'' !in word) return null
        IRREGULAR_CONTRACTIONS[word]?.let { return it }
        if (word.endsWith("n't")) return word.dropLast(3).takeIf { it.length >= 2 }
        for (suffix in CLITIC_SUFFIXES) {
            if (word.endsWith(suffix)) return word.dropLast(suffix.length).takeIf { it.isNotEmpty() }
        }
        return word.substringBefore('\'').takeIf { it.isNotEmpty() }
    }

    /** Cutting "n't" off these leaves "ca", "wo", "sha". */
    private val IRREGULAR_CONTRACTIONS = mapOf(
        "can't" to "can", "cannot" to "can", "won't" to "will", "shan't" to "shall",
        "ain't" to "be", "let's" to "let", "o'clock" to "o'clock"
    )

    /** "'s" covers both the possessive and "is"; the stem is the word wanted either way. */
    private val CLITIC_SUFFIXES = listOf("'ve", "'ll", "'re", "'d", "'s", "'m")

    /**
     * A word: letters, and the marks that live inside one. The apostrophe and
     * the hyphen are included so "don't" and "well-known" arrive whole and are
     * cut by a rule that can check the dictionary, rather than by the regex.
     */
    private val WORD = Regex("""\p{L}[\p{L}\p{M}'’\-]*""")

    private val HYPHENS = charArrayOf('-', '‐', '–')

    /** Below this a hyphen's part is a prefix or a letter, not a word ("e-mail", "x-ray"). */
    private const val MIN_PART_LENGTH = 3

    /** A card's context sentence: long enough to carry meaning, short enough to read. */
    const val MIN_SENTENCE_LENGTH = 12
    const val MAX_SENTENCE_LENGTH = 220

    private fun trimEdges(value: String): String =
        value.trim('-', '‐', '–', '\'', '’')

    // Spanish's ¡ and ¿ open a sentence rather than closing one, so
    // they are not here: splitting on them would cut every question in half.
    private const val SENTENCE_ENDS = ".!?…"
    private const val CLOSERS = "\"'”’)]}»"

    /**
     * Sentence by sentence, with its offset in [text].
     *
     * A line break ends a sentence too: subtitles, verse and Markdown lists
     * are written one phrase per line with no full stop at all, and without
     * this the whole file would be one "sentence" and no card would get a
     * context line.
     */
    fun forEachSentence(text: String, block: (sentence: String, start: Int) -> Unit) {
        var start = 0
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '\n' || (c in SENTENCE_ENDS && endsSentence(text, i))) {
                var end = i + 1
                while (end < text.length && text[end] in CLOSERS) end++
                if (end > start) block(text.substring(start, end), start)
                start = end
                i = end
                continue
            }
            i++
        }
        if (start < text.length) block(text.substring(start), start)
    }

    /**
     * Whether a full stop closes a sentence rather than sitting inside a
     * number ("3.5"), an abbreviation ("Mr. Darcy", "e.g.") or an ellipsis
     * that the text continues through.
     */
    private fun endsSentence(text: String, at: Int): Boolean {
        var next = at + 1
        while (next < text.length && (text[next] in CLOSERS || text[next] == text[at])) next++
        if (next < text.length && !text[next].isWhitespace()) return false
        if (text[at] != '.') return true
        val before = text.getOrNull(at - 1) ?: return true
        if (before.isDigit() && text.getOrNull(at + 1)?.isDigit() == true) return false
        // The word the stop is attached to, lowercased: "mr", "dr", "e.g" → "g".
        var wordStart = at
        while (wordStart > 0 && text[wordStart - 1].isLetter()) wordStart--
        val word = text.substring(wordStart, at).lowercase()
        if (word.length == 1 && text.getOrNull(wordStart - 1) == '.') return false
        return word !in ABBREVIATIONS
    }

    /** Titles and the handful of abbreviations a novel actually uses. */
    private val ABBREVIATIONS = setOf(
        "mr", "mrs", "ms", "dr", "prof", "st", "jr", "sr", "vs", "etc", "e.g", "i.e",
        "fig", "no", "vol", "ch", "p", "pp", "ca", "cf", "al", "sra", "srta", "ud", "uds"
    )

    private class MutableToken(
        var surface: String,
        var firstOffset: Int,
        var wasInflected: Boolean
    ) {
        var count: Int = 0
        var nameHits: Int = 0
        var sentence: String = ""
    }
}
