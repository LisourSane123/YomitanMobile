package com.yomitanmobile.util

/**
 * Builds safe HTML for a context sentence and highlights a target token.
 *
 * Matching runs in three passes, from most to least precise:
 *
 *  1. the token itself plus the surface forms produced by
 *     [JapaneseConjugator] (食べる -> 食べた / 食べています / …), longest first;
 *  2. a reverse scan: every substring of the sentence is fed to
 *     [JapaneseDeconjugator], and the substring whose base form equals one of
 *     the tokens wins. This catches inflections the forward conjugator does
 *     not enumerate (食べさせられなかった, 高くありません, …);
 *  3. a stem fallback: the token minus its inflecting tail, extended over the
 *     okurigana that follows it in the sentence.
 *
 * Every comparison happens on a kana-normalised copy of the text (katakana
 * folded onto hiragana, 1 char -> 1 char so offsets stay valid), so a word
 * written サボる / たべる in the dictionary is still found when the sentence
 * spells it さぼる / タベル. The highlight itself is applied to the ORIGINAL
 * substring, so the card shows the sentence exactly as written.
 */
object SentenceContextHighlighter {

    /** Longest substring the deconjugation scan will consider as one word. */
    private const val MAX_SURFACE_LENGTH = 14

    /**
     * Cap on the okurigana run the stem fallback will swallow. Long enough for
     * a stacked ending like 書か+なければならない, short enough that a runaway
     * kana stretch never eats half the sentence.
     */
    private const val MAX_TAIL_LENGTH = 12

    /**
     * Hiragana that almost always start a following particle rather than
     * continue an inflection — the stem fallback stops before them.
     */
    private const val PARTICLE_STOPPERS = "はがをにへでとやもの"

    fun buildHighlightedSentenceHtml(
        sentence: String,
        preferredTokens: List<String>
    ): String {
        val trimmedSentence = sentence.trim()
        if (trimmedSentence.isBlank()) return ""

        val range = findTargetRange(trimmedSentence, preferredTokens)
            ?: return InputSanitizer.escapeHtml(trimmedSentence)

        return buildString {
            append(InputSanitizer.escapeHtml(trimmedSentence.substring(0, range.first)))
            append("<strong class=\"context-highlight\">")
            append(InputSanitizer.escapeHtml(trimmedSentence.substring(range.first, range.last + 1)))
            append("</strong>")
            append(InputSanitizer.escapeHtml(trimmedSentence.substring(range.last + 1)))
        }
    }

    /**
     * True when the target word can be located in the sentence. Used to pick
     * the most useful context sentence out of several candidates.
     */
    fun containsTarget(sentence: String, preferredTokens: List<String>): Boolean =
        findTargetRange(sentence.trim(), preferredTokens) != null

    /**
     * Character range of the target word inside [sentence], or null when the
     * word cannot be located. Indices refer to the untouched input string.
     */
    fun findTargetRange(sentence: String, preferredTokens: List<String>): IntRange? {
        if (sentence.isBlank()) return null
        val tokens = preferredTokens
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
        if (tokens.isEmpty()) return null

        val haystack = normalizeForMatch(sentence)
        val baseForms = tokens.mapTo(HashSet()) { normalizeForMatch(it) }

        val direct = matchInflectedForms(haystack, tokens)
        if (direct != null) {
            val expanded = expandToLongerInflection(haystack, direct, baseForms)
            return if (expanded != direct) expanded else expandWithAuxiliary(haystack, direct)
        }

        return matchByDeconjugation(haystack, baseForms)
            ?: matchByStem(haystack, tokens)
    }

    /**
     * A generated surface form can be a prefix of the form the sentence
     * actually uses (さぼって inside さぼっていた). Starting from the matched
     * position, take the longest substring that still deconjugates back to the
     * word so the whole inflection is highlighted, not just its head.
     */
    private fun expandToLongerInflection(
        haystack: String,
        match: IntRange,
        baseForms: Set<String>
    ): IntRange {
        val start = match.first
        val matchedLength = match.last + 1 - start
        val maxLength = minOf(MAX_SURFACE_LENGTH, haystack.length - start)
        for (length in maxLength downTo matchedLength + 1) {
            val candidate = haystack.substring(start, start + length)
            if (candidate.any { !isJapanese(it) }) continue
            val matches = JapaneseDeconjugator.candidateForms(candidate)
                .any { normalizeForMatch(it) in baseForms }
            if (matches) return start until start + length
        }
        return match
    }

    /**
     * Helper verbs that hang off a te-form or a finite form and belong to the
     * same word for highlighting purposes (さぼって + いた, 食べて + しまった).
     * Only accepted when what follows them can't be another word's opening
     * kana, so 見る + たび is not read as 見る + た.
     */
    private val AUXILIARY_TAILS = listOf(
        "いる", "います", "いました", "いません", "いた", "いて", "いない", "いなかった",
        "います", "る", "ます", "ました", "ません", "ませんでした",
        "た", "て", "ない", "なかった", "たい", "たかった",
        "おく", "おいた", "おきます",
        "しまう", "しまった", "しまいました",
        "みる", "みた", "みます",
        "くる", "きた", "きます", "いく", "いった", "いきます",
        "ある", "あった", "あります",
        "ください", "くれる", "くれた", "もらう", "もらった", "あげる", "あげた"
    ).distinct().sortedByDescending { it.length }

    /** Pass 1 — dictionary form + forward-generated inflections, longest first. */
    private fun matchInflectedForms(haystack: String, tokens: List<String>): IntRange? {
        val surfaces = tokens
            .flatMap { JapaneseConjugator.inflectedForms(it) }
            .map { normalizeForMatch(it) }
            .filter { it.isNotBlank() }
            .distinct()
            .sortedByDescending { it.length }

        for (surface in surfaces) {
            val index = haystack.indexOf(surface)
            if (index >= 0) return index until index + surface.length
        }
        return null
    }

    /** Appends a trailing helper verb to an already-matched form, if present. */
    private fun expandWithAuxiliary(haystack: String, match: IntRange): IntRange {
        val end = match.last + 1
        for (tail in AUXILIARY_TAILS) {
            if (!haystack.startsWith(tail, end)) continue
            val after = end + tail.length
            val boundary = after >= haystack.length ||
                !isHiragana(haystack[after]) ||
                haystack[after] in PARTICLE_STOPPERS
            if (boundary) return match.first until after
        }
        return match
    }

    /**
     * Pass 2 — walk the sentence and deconjugate each substring back to its
     * dictionary form. Positions are scanned left to right and lengths longest
     * first, so the leftmost occurrence is highlighted in full.
     */
    private fun matchByDeconjugation(haystack: String, baseForms: Set<String>): IntRange? {
        for (start in haystack.indices) {
            if (!isJapanese(haystack[start])) continue
            val maxLength = minOf(MAX_SURFACE_LENGTH, haystack.length - start)
            for (length in maxLength downTo 2) {
                val candidate = haystack.substring(start, start + length)
                if (candidate.any { !isJapanese(it) }) continue
                val matches = JapaneseDeconjugator.candidateForms(candidate)
                    .any { normalizeForMatch(it) in baseForms }
                if (matches) return start until start + length
            }
        }
        return null
    }

    /**
     * Pass 3 — drop the token's inflecting tail and match the stem, then swallow
     * the okurigana that follows it in the sentence. Requires a non-empty kana
     * tail so a stem like 食べ does not light up inside 食べ物.
     */
    private fun matchByStem(haystack: String, tokens: List<String>): IntRange? {
        val stems = tokens
            .mapNotNull { stemOf(it) }
            .map { normalizeForMatch(it) }
            // A single kana is far too generic to anchor on; a single kanji is
            // distinctive enough (書 for 書く), and the required kana tail
            // keeps it out of compounds like 食べ物 / 見物.
            .filter { it.length >= 2 || it.any { ch -> isKanji(ch) } }
            .distinct()
            .sortedByDescending { it.length }

        for (stem in stems) {
            val index = haystack.indexOf(stem)
            if (index < 0) continue
            var end = index + stem.length
            val limit = minOf(haystack.length, end + MAX_TAIL_LENGTH)
            while (end < limit &&
                isHiragana(haystack[end]) &&
                haystack[end] !in PARTICLE_STOPPERS
            ) {
                end++
            }
            if (end > index + stem.length) return index until end
        }
        return null
    }

    /**
     * The token without its single inflecting kana (食べる -> 食べ, 書く -> 書,
     * 高い -> 高, さぼる -> さぼ). Only the last character is dropped: cutting
     * every trailing kana would turn 食べる into 食 and let it light up inside
     * 食べ物.
     */
    private fun stemOf(token: String): String? {
        if (token.length < 2) return null
        if (!isHiragana(token.last()) && !isKatakana(token.last())) return null
        return token.dropLast(1)
    }

    /**
     * Folds katakana onto hiragana and upper- onto lowercase so kana-script
     * differences never block a match. Strictly 1 char -> 1 char: the result
     * shares its indices with the input.
     */
    private fun normalizeForMatch(text: String): String = buildString(text.length) {
        for (ch in text) append(normalizeChar(ch))
    }

    private fun normalizeChar(ch: Char): Char = when {
        // Katakana block (ァ..ヶ, incl. ヴ) sits exactly 0x60 above hiragana.
        ch in 'ァ'..'ヶ' -> ch - 0x60
        ch == 'ヽ' -> 'ゝ' // ヽ -> ゝ
        ch == 'ヾ' -> 'ゞ' // ヾ -> ゞ
        ch in 'A'..'Z' -> ch.lowercaseChar()
        else -> ch
    }

    private fun isHiragana(ch: Char): Boolean = ch in 'ぁ'..'ゟ'

    private fun isKatakana(ch: Char): Boolean = ch in 'ァ'..'ヿ'

    private fun isKanji(ch: Char): Boolean = ch in '一'..'鿿'

    private fun isJapanese(ch: Char): Boolean =
        isHiragana(ch) || isKatakana(ch) || isKanji(ch) || ch == 'ー'
}
