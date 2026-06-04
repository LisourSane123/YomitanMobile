package com.yomitanmobile.util

/**
 * Peels cross-references and ad-hoc usage notes out of definition strings so
 * the DetailScreen can show them in a dedicated "see also / notes" card at
 * the bottom of the page — keeps the meaning column focused on the actual
 * gloss while still preserving the auxiliary information.
 *
 * Three families of pattern are recognised:
 *
 * 1. **Cross-references**: `see X`, `see also X, Y`, `cf. X`, `→ X`, or a
 *    standalone parenthetical like `(see also 食べる)` tacked onto the gloss.
 *
 * 2. **Explicit note prefixes**: `Note: …`, `Notes: …`, `Usage: …`,
 *    `Usage note: …` at the start (or after a separator) of a definition.
 *
 * 3. **Stand-alone reference entries**: an entire definition that is just a
 *    cross-reference (`see 食べる`) — moved to the notes list, removed from
 *    the cleaned definitions.
 *
 * The peel is conservative: when nothing recognisable is found, the original
 * definition passes through untouched.
 */
object NotesExtractor {

    data class Extracted(val definitions: List<String>, val notes: List<String>)

    /**
     * Sentinel that YomitanDictionaryParser stamps on the front of any
     * "extra-info" note it harvests out of a Jitendex sense. Kept in sync
     * with YomitanDictionaryParser.NOTE_MARKER — duplicated as a literal
     * here so this util has no parser dependency (the parser module is far
     * heavier and we want the extractor to remain a leaf utility).
     */
    const val NOTE_MARKER = "⟦note⟧ "

    /**
     * Trailing/parenthesised cross-reference, e.g.
     *   `to eat (see also 食べる)`               → note: "see also 食べる"
     *   `to eat (cf. 召し上がる)`                → note: "cf. 召し上がる"
     *   `bowing repeatedly → ペコペコ`           → note: "→ ペコペコ"
     *   `to eat; see also 食べる`                → note: "see also 食べる"
     *
     * The leading capture is the gloss to keep; the second capture is the note text.
     */
    private val TRAILING_REF_REGEX = Regex(
        """^(.+?)\s*(?:[;,]\s*|\()?(see also[^()]*?|see\s+[^()]*?|cf\.\s*[^()]*?|→\s*[^()]*?)\)?\s*$""",
        RegexOption.IGNORE_CASE
    )

    /**
     * Definition that is entirely a cross-reference. We pull these out
     * wholesale so they don't waste a numbered slot in the meaning column.
     */
    private val WHOLE_REF_REGEX = Regex(
        """^\s*\(?\s*(see also\s+.+?|see\s+.+?|cf\.\s*.+?|→\s*.+?)\s*\)?\s*$""",
        RegexOption.IGNORE_CASE
    )

    /**
     * Explicit "Note:" / "Notes:" / "Usage:" prefix at the head of a gloss.
     * The first capture is the label (we keep it so the chip reads "Note:
     * …"); the second is the body to display.
     */
    private val NOTE_PREFIX_REGEX = Regex(
        """^\s*(notes?|usage(?:\s+notes?)?)\s*[:\-—]\s*(.+)\s*$""",
        RegexOption.IGNORE_CASE
    )

    fun extractAll(definitions: List<String>): Extracted {
        if (definitions.isEmpty()) return Extracted(definitions, emptyList())
        val cleaned = ArrayList<String>(definitions.size)
        val notes = LinkedHashSet<String>()
        outer@ for (raw in definitions) {
            val def = raw.trim()
            if (def.isEmpty()) continue@outer

            // Case 0: parser-marked extra-info note. The Jitendex parser
            // tags every harvested note with NOTE_MARKER as the prefix so
            // we can route it here unambiguously, no regex guessing needed.
            if (def.startsWith(NOTE_MARKER)) {
                val body = def.removePrefix(NOTE_MARKER).trim()
                if (body.isNotEmpty()) notes.add(body)
                continue@outer
            }

            // Case 1: whole definition is a cross-reference → move to notes.
            val whole = WHOLE_REF_REGEX.matchEntire(def)
            if (whole != null) {
                notes.add(whole.groupValues[1].trim())
                continue@outer
            }

            // Case 2: explicit "Note:" / "Usage:" prefix → strip and collect.
            val notePref = NOTE_PREFIX_REGEX.matchEntire(def)
            if (notePref != null) {
                val label = notePref.groupValues[1].trim().replaceFirstChar { it.uppercase() }
                val body = notePref.groupValues[2].trim()
                if (body.isNotEmpty()) notes.add("$label: $body")
                continue@outer
            }

            // Case 3: trailing cross-reference clinging to a real gloss.
            val trailing = TRAILING_REF_REGEX.matchEntire(def)
            if (trailing != null) {
                val keep = trailing.groupValues[1].trim().trimEnd(',', ';', ' ')
                val ref = trailing.groupValues[2].trim()
                if (keep.isNotEmpty() && looksLikeReference(ref)) {
                    cleaned.add(keep)
                    notes.add(ref)
                    continue@outer
                }
            }

            cleaned.add(def)
        }
        return Extracted(cleaned, notes.toList())
    }

    /**
     * Guards against false positives: the trailing-reference regex is greedy
     * and might capture natural-language text that happens to start with
     * "see". We only treat the capture as a note when it begins with one of
     * the known reference markers.
     */
    private fun looksLikeReference(s: String): Boolean {
        val lower = s.lowercase()
        return lower.startsWith("see also") ||
                lower.startsWith("see ") ||
                lower.startsWith("cf.") ||
                lower.startsWith("→")
    }
}
