package com.yomitanmobile.util

/**
 * Utility for sanitizing user input to prevent XSS, SQL injection,
 * and other injection attacks throughout the application.
 */
object InputSanitizer {

    /**
     * Sanitize text for safe inclusion in HTML content (Anki cards).
     * Escapes HTML special characters to prevent XSS.
     */
    fun escapeHtml(text: String): String {
        return text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#x27;")
    }

    /**
     * Sanitize a user-typed query for SQLite FTS4 MATCH.
     *
     * The entity is declared as `@Fts4`, which only supports per-token prefix
     * syntax (`term*`) — phrase-prefix (`"foo"*`) is an FTS5 feature and
     * raises a syntax error on FTS4. The previous implementation emitted
     * exactly that, every search threw, the repository's `.catch` swallowed
     * it, and users saw empty result pages with no indication why.
     *
     * Strategy:
     *  1. Strip FTS operators and control characters — anything left is plain
     *     term content. Operators are replaced with spaces so adjacent tokens
     *     don't fuse into a single garbage term.
     *  2. Collapse internal whitespace; cap length so a pasted novel doesn't
     *     produce a 50k-clause query plan.
     *  3. Append `*` to each surviving token to get prefix-match behaviour
     *     consistent with what users expect from a dictionary lookup. Tokens
     *     are joined by spaces, which FTS4 treats as implicit AND.
     *
     * Returns an empty string when the input has no usable content; callers
     * short-circuit blank queries to an empty result list.
     */
    fun sanitizeFtsQuery(query: String): String {
        val trimmed = query.trim()
        if (trimmed.isBlank()) return ""

        val stripped = buildString(trimmed.length) {
            for (c in trimmed) {
                when {
                    c.code < 0x20 -> append(' ')
                    // FTS4 operators that change query meaning or trigger
                    // syntax errors when present in user input.
                    c in "\"()^:*-+\\" -> append(' ')
                    else -> append(c)
                }
            }
        }.trim().replace(Regex("\\s+"), " ")
        if (stripped.isBlank()) return ""

        // Length cap: a 200-char query has at most ~50 tokens, well within
        // what FTS4 plans efficiently. Pathological inputs (huge pastes,
        // accidental clipboard contents) get truncated rather than DOS'ing
        // the search thread.
        val capped = if (stripped.length > 200) stripped.substring(0, 200) else stripped

        return capped.splitToSequence(' ')
            .filter { it.isNotBlank() }
            .joinToString(" ") { "$it*" }
            .ifBlank { "" }
    }

    /**
     * Sanitize a general text query (for LIKE queries).
     * Prevents SQL wildcard injection via LIKE patterns.
     */
    fun sanitizeLikeQuery(query: String): String {
        return query.trim()
            .replace("\\", "\\\\")
            .replace("%", "\\%")
            .replace("_", "\\_")
    }

    /**
     * Sanitize deck name input for Anki operations.
     * Removes potentially dangerous characters while preserving
     * valid deck naming characters (including ::  for sub-decks).
     */
    fun sanitizeDeckName(name: String): String {
        val trimmed = name.trim()
        if (trimmed.isBlank()) return "Mining Deck"
        // Allow alphanumeric, spaces, hyphens, underscores, colons (for sub-decks), Japanese chars
        return trimmed
            .replace(Regex("[<>\"';&|`\$\\\\]"), "")
            .take(200) // Reasonable max length
            .ifBlank { "Mining Deck" }
    }

    /**
     * Sanitize a file name to prevent path traversal attacks.
     */
    fun sanitizeFileName(name: String): String {
        return name
            .replace("..", "")
            .replace("/", "")
            .replace("\\", "")
            .replace("\u0000", "")
            .take(255)
    }
}
