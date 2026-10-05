package com.yomitanmobile.util

/**
 * Splits Jitendex-style usage-hint prefixes out of definition strings so the UI
 * can render them as a separate chip instead of leaving them inline with the
 * gloss.
 *
 * Real-world Jitendex variants emit these prefixes in two shapes:
 *
 * 1. **Parenthesised**:   `(usually kana) but, however, on the other hand`
 *    — produced when the parser's `extractSenseGloss` harvests the tag
 *    separately and prepends it.
 *
 * 2. **Concatenated**:    `kanabut, however, on the other hand`
 *    `notesusually kanabut, however`
 *    — produced when the `<span class="tag">` content bleeds into the gloss
 *    flat-text (no whitespace between the tag label and the next word).
 *    Often preceded by a literal `notes` / `note` wrapper label.
 *
 * Both shapes peel off here, so existing imports light up without anyone
 * re-importing their dictionary.
 */
object UsageTagExtractor {

    /**
     * Short tag labels as Jitendex renders them (the visible `content` of the
     * `<span class="tag">` element, plus a couple of verbose `title` fallbacks
     * we want to normalise). Kept lowercase-sensitive on purpose — the labels
     * are stable across Jitendex versions.
     *
     * Order in the map doesn't matter for matching; we always try the longest
     * label first so "usually kana" wins over "kana".
     */
    private val TAG_LABELS: Set<String> = setOf(
        "usually kana", "usually kanji",
        "word usually written using kana alone",
        "word usually written using kanji alone",
        "kana", "kanji",
        "obsolete", "archaic", "rare",
        "slang", "colloq.", "colloquial",
        "familiar", "honorific", "humble", "polite", "vulgar",
        "euph.", "euphemistic",
        "derog.", "derogatory", "joc.", "jocular",
        "abbr.", "abbreviation",
        "yoji.", "yojijukugo",
        "idiom", "idiomatic",
        "proverb", "quote", "quotation",
        "onomat.", "onomatopoeia", "mimetic", "on-mim",
        "formal", "male", "fem.", "female",
        "children", "X-rated",
        "comp.", "computing", "math", "med.", "medical", "law",
        "Buddh.", "Buddhism", "Shinto",
        "phys.", "physics", "chem.", "chemistry", "biol.", "biology",
        "bus.", "business", "econ.", "economics",
        "food", "sports", "music"
    ) + PartsOfSpeechFormatter.dialectLabels

    /**
     * Wrapper labels that Jitendex prints before the actual tag list ("notes",
     * "usage notes", …). They carry no semantic value on their own, so we
     * strip them without contributing to the chip output.
     */
    private val NOTE_WRAPPERS: List<String> = listOf(
        "usage notes", "usage note", "notes", "note", "more info", "info"
    )

    /**
     * Labels sorted longest-first. We want "usually kana" to win over "kana"
     * when both could match at position 0.
     */
    private val LABELS_LONGEST_FIRST: List<String> =
        TAG_LABELS.sortedByDescending { it.length }
    private val WRAPPERS_LONGEST_FIRST: List<String> =
        NOTE_WRAPPERS.sortedByDescending { it.length }

    data class Extracted(val tags: List<String>, val definition: String)

    /**
     * Peel any leading tag prefix(es) off [definition]. Loops until no further
     * label matches, so `noteskanabut, however` → tags=[usually kana? no,
     * kana], def=`but, however`. The original string is returned untouched
     * when nothing recognisable sits at the front (natural glosses like
     * "(the) world" survive).
     */
    fun extract(definition: String): Extracted {
        var rest = definition
        val tags = LinkedHashSet<String>()
        // Bound the loop defensively — at most a few labels can stack.
        for (i in 0 until 8) {
            val peeled = peelOnce(rest) ?: break
            if (peeled.tag.isNotEmpty()) tags.add(normalize(peeled.tag))
            rest = peeled.remaining
        }
        return finalize(tags, rest, definition)
    }

    private fun finalize(
        tags: LinkedHashSet<String>,
        rest: String,
        original: String
    ): Extracted {
        if (tags.isEmpty()) return Extracted(emptyList(), original)
        val cleaned = rest.trim().trimStart(',', ';', ':', '.', ' ').trim()
        if (cleaned.isEmpty()) return Extracted(emptyList(), original)
        return Extracted(tags.toList(), cleaned)
    }

    private data class Peeled(val tag: String, val remaining: String)

    private fun peelOnce(input: String): Peeled? {
        // Strip leading whitespace AND any separator punctuation left over
        // from the previous peel (`notes:`, `notes,`, `notes;`).
        val s = input.trimStart().trimStart(':', ',', ';', '.').trimStart()
        if (s.isEmpty()) return null

        // 1. Parenthesised list: "(tag, tag, …) rest"
        peelParenthesised(s)?.let { return it }

        // 2. Wrapper label that introduces tag(s): "notes…" / "usage notes…"
        peelWrapperLabel(s)?.let { return it }

        // 3. Bare or concatenated single tag: "kanabut…" / "formal to come into…"
        peelBareTag(s)?.let { return it }

        return null
    }

    private val PAREN_REGEX = Regex("""^\(([^()]{1,120})\)\s*(.*)$""", RegexOption.DOT_MATCHES_ALL)

    private fun peelParenthesised(s: String): Peeled? {
        val m = PAREN_REGEX.matchEntire(s) ?: return null
        val raw = m.groupValues[1].trim()
        val rest = m.groupValues[2]
        if (rest.isBlank()) return null

        val tokens = raw.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return null
        // Strict: every token has to look like a recognised tag, otherwise this
        // is probably a natural parenthetical like "(the) world".
        if (tokens.any { it !in TAG_LABELS && it !in NOTE_WRAPPERS }) return null

        // Treat wrapper-only parens (e.g. "(notes) …") as a label peel.
        val tagTokens = tokens.filter { it in TAG_LABELS }
        return Peeled(tag = tagTokens.joinToString(", "), remaining = rest)
    }

    private fun peelWrapperLabel(s: String): Peeled? {
        for (wrapper in WRAPPERS_LONGEST_FIRST) {
            if (!s.startsWith(wrapper, ignoreCase = true)) continue
            val after = s.substring(wrapper.length)
            if (looksLikeAttachedTail(after)) {
                return Peeled(tag = "", remaining = after)
            }
        }
        return null
    }

    private fun peelBareTag(s: String): Peeled? {
        for (label in LABELS_LONGEST_FIRST) {
            if (!s.startsWith(label, ignoreCase = false)) continue
            val after = s.substring(label.length)
            if (looksLikeAttachedTail(after)) {
                return Peeled(tag = label, remaining = after)
            }
        }
        return null
    }

    /**
     * `after` is the substring immediately following a candidate label match.
     * We only accept the peel when `after` clearly continues the gloss — either
     * starts with a separator (`:`, `,`, `;`, whitespace) or with a lowercase
     * letter (concatenation evidence: `kanabut`, `formal to`). Anything else
     * (uppercase letter, digit, end-of-string) is rejected because it suggests
     * the candidate match was actually the start of a natural-language word.
     */
    private fun looksLikeAttachedTail(after: String): Boolean {
        if (after.isEmpty()) return false
        val first = after[0]
        return when {
            first.isWhitespace() -> after.trimStart().isNotEmpty()
            first == ':' || first == ',' || first == ';' || first == '.' -> true
            // Concatenated form: tag glued directly to the next gloss word.
            // Restrict to lowercase letters — uppercase suggests a proper noun
            // ("musicAfrican" wouldn't be a real concatenation, but "kanabut" is).
            first in 'a'..'z' -> true
            else -> false
        }
    }

    /**
     * Run [extract] across every definition. Returns (deduplicated tag list in
     * first-seen order, cleaned definition list).
     */
    fun extractAll(definitions: List<String>): Pair<List<String>, List<String>> {
        val tags = LinkedHashSet<String>()
        val cleaned = ArrayList<String>(definitions.size)
        for (def in definitions) {
            val r = extract(def)
            tags.addAll(r.tags)
            cleaned.add(r.definition)
        }
        return tags.toList() to cleaned
    }

    /** Collapse Jitendex's verbose `title` variants to the compact label. */
    private fun normalize(tag: String): String {
        // Tag may already be a comma-joined list (from peelParenthesised).
        if (tag.contains(',')) {
            return tag.split(',').joinToString(", ") { normalizeOne(it.trim()) }
        }
        return normalizeOne(tag)
    }

    private fun normalizeOne(tag: String): String = when (tag) {
        "word usually written using kana alone" -> "usually kana"
        "word usually written using kanji alone" -> "usually kanji"
        "kana" -> "usually kana"
        "kanji" -> "usually kanji"
        "colloquial" -> "colloq."
        "derogatory" -> "derog."
        "jocular" -> "joc."
        "abbreviation" -> "abbr."
        "yojijukugo" -> "yoji."
        "idiomatic" -> "idiom"
        "quotation" -> "quote"
        "onomatopoeia" -> "onomat."
        "on-mim" -> "mimetic"
        "female" -> "fem."
        "computing" -> "comp."
        "medical" -> "med."
        "Buddhism" -> "Buddh."
        "physics" -> "phys."
        "chemistry" -> "chem."
        "biology" -> "biol."
        "business" -> "bus."
        "economics" -> "econ."
        else -> tag
    }
}
